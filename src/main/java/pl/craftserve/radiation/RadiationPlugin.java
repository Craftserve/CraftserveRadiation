/*
 * Copyright 2019 Aleksander Jagiełło <themolkapl@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pl.craftserve.radiation;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.craftserve.radiation.nms.RadiationNmsBridge;
import pl.craftserve.radiation.nms.V1_14ToV1_15NmsBridge;
import pl.craftserve.radiation.nms.V1_17_R1NmsBridge;
import pl.craftserve.radiation.nms.V1_18_R1NmsBridge;
import pl.craftserve.radiation.nms.V1_18_R2NmsBridge;
import pl.craftserve.radiation.nms.V1_19_R1NmsBridge;
import pl.craftserve.radiation.nms.V1_19_R2NmsBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RadiationPlugin extends JavaPlugin {
    static final Logger logger = Logger.getLogger(RadiationPlugin.class.getName());

    private static final char COLOR_CODE = '&';

    public static String colorize(String input) {
        return input == null ? null : ChatColor.translateAlternateColorCodes(COLOR_CODE, input);
    }

    private static final int CURRENT_PROTOCOL_VERSION = 4;
    private static final Flag<Boolean> RADIATION_FLAG = new BooleanFlag("radiation", RegionGroup.NON_MEMBERS);
    private static final Flag<String> RADIATION_TYPE_FLAG = new StringFlag("radiation-type");

    private Flag<Boolean> radiationFlag;
    private Flag<String> radiationTypeFlag;
    private RadiationNmsBridge radiationNmsBridge;
    private Config config;

    private LugolsIodineEffect effect;
    private LugolsIodineDisplay display;

    private final Map<String, LugolsIodinePotion> potions = new LinkedHashMap<>();
    private final Map<String, Radiation> activeRadiations = new LinkedHashMap<>();

    private CraftserveListener craftserveListener;
    private MetricsHandler metricsHandler;

    private RadiationNmsBridge initializeNmsBridge() {
        String serverVersion = RadiationNmsBridge.getServerVersion(this.getServer());
        logger.info("Detected server version: " + serverVersion);

        switch (serverVersion) {
            case "v1_14_R1":
            case "v1_15_R1":
            case "v1_16_R1":
            case "v1_16_R2":
            case "v1_16_R3":
                return new V1_14ToV1_15NmsBridge(serverVersion);
            case "v1_17_R1":
                return new V1_17_R1NmsBridge(serverVersion);
            case "v1_18_R1":
                return new V1_18_R1NmsBridge(serverVersion);
            case "v1_18_R2":
                return new V1_18_R2NmsBridge(serverVersion);
            case "v1_19_R1":
                return new V1_19_R1NmsBridge(serverVersion);
            case "v1_19_R2":
                return new V1_19_R2NmsBridge(serverVersion);
            default:
                throw new RuntimeException("Unsupported server version: " + serverVersion);
        }
    }

    @Override
    public void onLoad() {
        FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
        if (flagRegistry == null) {
            throw new IllegalStateException("Flag registry is not set! Plugin must shut down...");
        }

        this.radiationFlag = this.getOrCreateFlag(flagRegistry, RADIATION_FLAG);
        this.radiationTypeFlag = this.getOrCreateFlag(flagRegistry, RADIATION_TYPE_FLAG);
    }

    @Override
    public void onEnable() {
        Server server = this.getServer();
        this.saveDefaultConfig();

        try {
            this.radiationNmsBridge = this.initializeNmsBridge();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to launch " + this.getName() + ". Plausibly your server version is unsupported.", e);
            this.setEnabled(false);
            return;
        }

        //
        // Configuration
        //

        FileConfiguration config = this.getConfig();
        if (!this.migrate(config, config.getInt("file-protocol-version-dont-touch", -1))) {
            this.setEnabled(false);
            return;
        }

        try {
            this.config = new Config(config);
        } catch (InvalidConfigurationException e) {
            logger.log(Level.SEVERE, "Could not load configuration file.", e);
            this.setEnabled(false);
            return;
        }

        //
        // Enabling
        //

        this.effect = new LugolsIodineEffect(this);
        this.display = new LugolsIodineDisplay(this, this.effect, this.config.lugolsIodineBars());

        for (LugolsIodinePotion.Config potionConfig : this.config.lugolsIodinePotions()) {
            this.potions.put(potionConfig.id(), new LugolsIodinePotion(this, this.effect, potionConfig));
        }

        for (Radiation.Config radiationConfig : this.config.radiations()) {
            String id = radiationConfig.id();
            Radiation.Matcher matcher = new Radiation.FlagMatcher(this.radiationNmsBridge, this.radiationFlag, this.radiationTypeFlag, Collections.singleton(id));

            this.activeRadiations.put(id, new Radiation(this, matcher, radiationConfig));
        }

        RadiationCommandHandler radiationCommandHandler = new RadiationCommandHandler(this.radiationNmsBridge, this.radiationFlag, this.potions::get, () -> {
            return this.potions.values().spliterator();
        });
        radiationCommandHandler.register(this.getCommand("radiation"));

        this.craftserveListener = new CraftserveListener(this);
        this.metricsHandler = new MetricsHandler(this, server, this.radiationNmsBridge.getClass());

        this.effect.enable();
        this.display.enable();

        this.potions.forEach((id, potion) -> potion.enable(this.radiationNmsBridge));
        Set<String> potionIds = new TreeSet<>(Comparator.naturalOrder());
        potionIds.addAll(this.potions.keySet());
        logger.info("Loaded and enabled " + this.potions.size() + " lugol's iodine potion(s): " + String.join(", ", potionIds));

        this.activeRadiations.forEach((id, radiation) -> radiation.enable());
        Set<String> radiationIds = new TreeSet<>(Comparator.naturalOrder());
        radiationIds.addAll(this.activeRadiations.keySet());
        logger.info("Loaded and enabled " + this.activeRadiations.size() + " radiation(s): " + String.join(", ", radiationIds));

        this.craftserveListener.enable();
        this.metricsHandler.start();
    }

    @Override
    public void onDisable() {
        if (this.metricsHandler != null) {
            this.metricsHandler.stop();
        }
        if (this.craftserveListener != null) {
            this.craftserveListener.disable();
        }

        this.activeRadiations.forEach((id, radiation) -> radiation.disable());
        this.activeRadiations.clear();

        this.potions.forEach((id, potion) -> potion.disable(this.radiationNmsBridge));
        this.potions.clear();

        if (this.display != null) {
            this.display.disable();
        }
        if (this.effect != null) {
            this.effect.disable();
        }
    }

    public Flag<Boolean> getRadiationFlag() {
        return this.radiationFlag;
    }

    public Flag<String> getRadiationTypeFlag() {
        return this.radiationTypeFlag;
    }

    public Config getPluginConfig() {
        return this.config;
    }

    public LugolsIodineEffect getEffectHandler() {
        return this.effect;
    }

    public Map<String, LugolsIodinePotion> getPotionHandlers() {
        return Collections.unmodifiableMap(this.potions);
    }

    public Map<String, Radiation> getActiveRadiations() {
        return Collections.unmodifiableMap(this.activeRadiations);
    }

    @SuppressWarnings("unchecked")
    private <T> Flag<T> getOrCreateFlag(FlagRegistry flagRegistry, Flag<T> defaultFlag) {
        Objects.requireNonNull(flagRegistry, "flagRegistry");
        Objects.requireNonNull(defaultFlag, "defaultFlag");

        Flag<T> flag = (Flag<T>) flagRegistry.get(defaultFlag.getName());
        if (flag != null) {
            return flag;
        }

        flag = defaultFlag;
        flagRegistry.register(flag);
        return flag;
    }

    //
    // Migrations
    //

    private boolean migrate(ConfigurationSection section, int protocol) {
        Objects.requireNonNull(section, "section");

        if (protocol > CURRENT_PROTOCOL_VERSION) {
            logger.severe("Your configuration file's protocol version \"" + protocol + "\" is invalid. Configuration file's protocol version is newer than this plugin version can understand. Are you trying to load it using an older version of the plugin?");
            return false;
        }

        if (protocol < 0) {
            section.set("lugols-iodine-bar.title", "Działanie Płynu Lugola");
            section.set("lugols-iodine-bar.color", BarColor.GREEN.name());
            section.set("lugols-iodine-bar.style", BarStyle.SEGMENTED_20.name());
            section.set("lugols-iodine-bar.flags", Collections.emptyList());

            section.set("lugols-iodine-potion.name", "Płyn Lugola");
            section.set("lugols-iodine-potion.description", "Odporność na promieniowanie ({0})");
            section.set("lugols-iodine-potion.duration", TimeUnit.MINUTES.toSeconds(section.getInt("potion-duration", 10)));
            section.set("lugols-iodine-potion.drink-message", "{0}" + ChatColor.RED + " wypił/a {1}.");

            section.set("radiation.bar.title", "Strefa radiacji");
            section.set("radiation.bar.color", BarColor.RED.name());
            section.set("radiation.bar.style", BarStyle.SOLID.name());
            section.set("radiation.bar.flags", Collections.singletonList(BarFlag.DARKEN_SKY.name()));

            section.set("radiation.effects.wither.level", 5);
            section.set("radiation.effects.wither.ambient", false);
            section.set("radiation.effects.wither.has-particles", false);
            section.set("radiation.effects.wither.has-icon", false);

            section.set("radiation.effects.hunger.level", 1);
            section.set("radiation.effects.hunger.ambient", false);
            section.set("radiation.effects.hunger.has-particles", false);
            section.set("radiation.effects.hunger.has-icon", false);

            section.set("radiation.escape-message", "{0}" + ChatColor.RED + " uciekł/a do strefy radiacji.");

            // Migrate from the old region-ID based system.
            String legacyRegionId = section.getString("region-name", "km_safe_from_radiation");
            AtomicBoolean logged = new AtomicBoolean();
            section.getStringList("world-names").forEach(worldName -> {
                if (logged.compareAndSet(false, true)) {
                    logger.warning(
                            "Enabling in legacy region-name mode! The plugin will try to automatically migrate to the new flag-based system.\n" +
                            "If everything went fine please completely remove your config.yml file.");
                }

                this.migrateFromRegionId(worldName, legacyRegionId);
            });
        }

        if (protocol < 1) {
            section.set("lugols-iodine-potion.recipe.enabled", true);
            section.set("lugols-iodine-potion.recipe.base-potion", LugolsIodinePotion.Config.Recipe.DEFAULT_BASE_POTION.name());
            section.set("lugols-iodine-potion.recipe.ingredient", LugolsIodinePotion.Config.Recipe.DEFAULT_INGREDIENT.getKey().getKey());

            section.set("lugols-iodine-potion.color", null);
        }

        if (protocol < 2) {
            MemoryConfiguration defaultRadiation = new MemoryConfiguration();

            ConfigurationSection oldRadiation = section.getConfigurationSection("radiation");
            if (oldRadiation != null) {
                oldRadiation.getValues(true).forEach(defaultRadiation::set);
            }

            section.set("radiation", null); // remove old section
            section.set("radiations.default", defaultRadiation);
        }

        if (protocol < 3) {
            ConfigurationSection radiations = section.getConfigurationSection("radiations");
            if (radiations != null) {
                for (String key : radiations.getKeys(false)) {
                    ConfigurationSection radiation = radiations.getConfigurationSection(key);
                    if (radiation != null) {
                        radiation.set("enter-message", radiation.getString("escape-message"));
                    }
                }
            }
        }

        if (protocol < 4) {
            // lugols-iodine-bars
            MemoryConfiguration defaultBar = new MemoryConfiguration();

            ConfigurationSection oldBar = section.getConfigurationSection("lugols-iodine-bar");
            if (oldBar != null) {
                oldBar.getValues(true).forEach(defaultBar::set);
            }

            section.set("lugols-iodine-bar", null); // remove old section
            section.set("lugols-iodine-bars.default", defaultBar);

            // lugols-iodine-potions
            MemoryConfiguration defaultPotion = new MemoryConfiguration();
            defaultPotion.set("radiation-ids", Collections.emptyList());

            ConfigurationSection oldPotion = section.getConfigurationSection("lugols-iodine-potion");
            if (oldPotion != null) {
                oldPotion.getValues(true).forEach(defaultPotion::set);
            }

            section.set("lugols-iodine-potion", null); // remove old section
            section.set("lugols-iodine-potions.default", defaultPotion);
        }

        return true;
    }

    /**
     * Migrate from region-ID based method to the new flag method.
     *
     * @param worldName Name of the world.
     * @param regionId ID of the region.
     */
    private void migrateFromRegionId(String worldName, String regionId) {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(regionId, "regionId");
        String error = "Could not migrate region " + regionId + " in world " + worldName + ": ";

        World world = this.getServer().getWorld(worldName);
        if (world == null) {
            logger.warning(error + "the world is unloaded.");
            return;
        }

        Radiation.WorldGuardMatcher matcher = (player, regionContainer) -> {
            throw new UnsupportedOperationException();
        };

        RegionContainer regionContainer = matcher.getRegionContainer();
        if (regionContainer == null) {
            logger.warning(error + "region container is not present.");
            return;
        }

        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            logger.warning(error + "region manager for the world is not present.");
            return;
        }

        ProtectedRegion legacyRegion = regionManager.getRegion(regionId);
        if (legacyRegion == null) {
            logger.warning(error + "legacy region is not present.");
            return;
        }

        legacyRegion.setFlag(this.radiationFlag, false);

        // make __global__ radioactive
        ProtectedRegion global = regionManager.getRegion("__global__");
        if (global == null) {
            global = new GlobalProtectedRegion("__global__");
            regionManager.addRegion(global);
        }

        global.setFlag(this.radiationFlag, true);
        logger.info("Region " + regionId + " in world " + worldName + " has been successfully migrated to the new flag-based system.");
    }

    //
    // Config
    //

    public static class Config {
        private final Map<String, BarConfig> lugolsIodineBars;
        private final Iterable<LugolsIodinePotion.Config> lugolsIodinePotions;
        private final Iterable<Radiation.Config> radiations;

        public Config(Map<String, BarConfig> lugolsIodineBars, Iterable<LugolsIodinePotion.Config> lugolsIodinePotions, Iterable<Radiation.Config> radiations) {
            this.lugolsIodineBars = Objects.requireNonNull(lugolsIodineBars, "lugolsIodineBars");
            this.lugolsIodinePotions = Objects.requireNonNull(lugolsIodinePotions, "lugolsIodinePotions");
            this.radiations = Objects.requireNonNull(radiations, "radiations");
        }

        public Config(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            try {
                if (!section.isConfigurationSection("lugols-iodine-bars")) {
                    throw new InvalidConfigurationException("Missing lugols-iodine-bars section.");
                }

                Map<String, BarConfig> bars = new LinkedHashMap<>();

                ConfigurationSection barsSection = Objects.requireNonNull(section.getConfigurationSection("lugols-iodine-bars"));
                for (String key : barsSection.getKeys(false)) {
                    if (!barsSection.isConfigurationSection(key)) {
                        throw new InvalidConfigurationException(key + " is not a lugols-iodine-bar sectino.");
                    }

                    bars.put(key, new BarConfig(barsSection.getConfigurationSection(key)));
                }

                this.lugolsIodineBars = Collections.unmodifiableMap(bars);
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-bars section.", e);
            }

            try {
                if (!section.isConfigurationSection("lugols-iodine-potions")) {
                    throw new InvalidConfigurationException("Missing lugols-iodine-potions section.");
                }

                List<LugolsIodinePotion.Config> potions = new ArrayList<>();

                ConfigurationSection potionsSection = Objects.requireNonNull(section.getConfigurationSection("lugols-iodine-potions"));
                for (String key : potionsSection.getKeys(false)) {
                    if (!potionsSection.isConfigurationSection(key)) {
                        throw new InvalidConfigurationException(key + " is not a lugols-iodine-potion section.");
                    }

                    potions.add(new LugolsIodinePotion.Config(potionsSection.getConfigurationSection(key)));
                }

                this.lugolsIodinePotions = Collections.unmodifiableCollection(potions);
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-potions section.", e);
            }

            try {
                if (!section.isConfigurationSection("radiations")) {
                    throw new InvalidConfigurationException("Missing radiations section.");
                }

                List<Radiation.Config> radiations = new ArrayList<>();

                ConfigurationSection radiationsSection = Objects.requireNonNull(section.getConfigurationSection("radiations"));
                for (String key : radiationsSection.getKeys(false)) {
                    if (!radiationsSection.isConfigurationSection(key)) {
                        throw new InvalidConfigurationException(key + " is not a radiation section.");
                    }

                    radiations.add(new Radiation.Config(radiationsSection.getConfigurationSection(key)));
                }

                this.radiations = Collections.unmodifiableCollection(radiations);
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse radiations section.", e);
            }
        }

        public Map<String, BarConfig> lugolsIodineBars() {
            return this.lugolsIodineBars;
        }

        public Iterable<LugolsIodinePotion.Config> lugolsIodinePotions() {
            return this.lugolsIodinePotions;
        }

        public Iterable<Radiation.Config> radiations() {
            return this.radiations;
        }
    }
}
