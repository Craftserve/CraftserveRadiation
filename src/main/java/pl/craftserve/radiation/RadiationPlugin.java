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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.craftserve.radiation.nms.RadiationNmsBridge;
import pl.craftserve.radiation.nms.V1_14ToV1_15NmsBridge;

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

public final class RadiationPlugin extends JavaPlugin {
    static final Logger logger = LoggerFactory.getLogger(RadiationPlugin.class);

    private static final char COLOR_CODE = '&';

    public static String colorize(String input) {
        return input == null ? null : ChatColor.translateAlternateColorCodes(COLOR_CODE, input);
    }

    private static final int CURRENT_PROTOCOL_VERSION = 3;
    private static final Flag<Boolean> RADIATION_FLAG = new BooleanFlag("radiation");
    private static final Flag<String> RADIATION_TYPE_FLAG = new StringFlag("radiation-type");

    private Flag<Boolean> radiationFlag;
    private Flag<String> radiationTypeFlag;
    private RadiationNmsBridge radiationNmsBridge;
    private Config config;

    private LugolsIodineEffect effect;
    private LugolsIodinePotion potion;
    private LugolsIodineDisplay display;

    private final Map<String, Radiation> activeRadiations = new LinkedHashMap<>();

    private CraftserveListener craftserveListener;
    private MetricsHandler metricsHandler;

    private RadiationNmsBridge initializeNmsBridge() {
        String serverVersion = RadiationNmsBridge.getServerVersion(this.getServer());
        logger.info("Detected server version: {}", serverVersion);

        switch (serverVersion) {
            case "v1_14_R1":
            case "v1_15_R1":
            case "v1_16_R1":
                return new V1_14ToV1_15NmsBridge(serverVersion);
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
            logger.error("Failed to launch {}. Plausibly your server version is unsupported.", this.getName(), e);
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
            logger.error("Could not load configuration file.", e);
            this.setEnabled(false);
            return;
        }

        //
        // Enabling
        //

        this.effect = new LugolsIodineEffect(this);
        this.potion = new LugolsIodinePotion(this, this.effect, this.config.lugolsIodinePotion());
        this.display = new LugolsIodineDisplay(this, this.effect, this.config.lugolsIodineDisplay());

        for (Radiation.Config radiationConfig : this.config.radiations()) {
            String id = radiationConfig.id();
            Radiation.Matcher matcher = new Radiation.FlagMatcher(this.radiationFlag, this.radiationTypeFlag, Collections.singleton(id));

            this.activeRadiations.put(id, new Radiation(this, matcher, radiationConfig));
        }

        RadiationCommandHandler radiationCommandHandler = new RadiationCommandHandler(this.radiationFlag, this.potion);
        radiationCommandHandler.register(this.getCommand("radiation"));

        this.craftserveListener = new CraftserveListener(this);
        this.metricsHandler = new MetricsHandler(this, server, this.effect, this.potion);

        this.effect.enable();
        this.potion.enable(this.radiationNmsBridge);
        this.display.enable();

        this.activeRadiations.forEach((id, radiation) -> radiation.enable());

        Set<String> radiationIds = new TreeSet<>(Comparator.naturalOrder());
        radiationIds.addAll(this.activeRadiations.keySet());
        logger.info("Loaded and enabled {} radiation(s): {}", this.activeRadiations.size(), String.join(", ", radiationIds));

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

        if (this.display != null) {
            this.display.disable();
        }
        if (this.potion != null) {
            this.potion.disable(this.radiationNmsBridge);
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

    public LugolsIodinePotion getPotionHandler() {
        return this.potion;
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
            logger.error("Your configuration file's protocol version \"{}\" is invalid. Are you trying to load it using a newer version of the plugin?", protocol);
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
                    logger.warn(
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
            logger.warn(error + "the world is unloaded.");
            return;
        }

        Radiation.WorldGuardMatcher matcher = (player, regionContainer) -> {
            throw new UnsupportedOperationException();
        };

        RegionContainer regionContainer = matcher.getRegionContainer();
        if (regionContainer == null) {
            logger.warn(error + "region container is not present.");
            return;
        }

        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            logger.warn(error + "region manager for the world is not present.");
            return;
        }

        ProtectedRegion legacyRegion = regionManager.getRegion(regionId);
        if (legacyRegion == null) {
            logger.warn(error + "legacy region is not present.");
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
        logger.info("Region {} in world {} has been successfully migrated to the new flag-based system.", regionId, worldName);
    }

    //
    // Config
    //

    public static class Config {
        private final BarConfig lugolsIodineDisplay;
        private final LugolsIodinePotion.Config lugolsIodinePotion;
        private final Iterable<Radiation.Config> radiations;

        public Config(BarConfig lugolsIodineDisplay, LugolsIodinePotion.Config lugolsIodinePotion, Iterable<Radiation.Config> radiations) {
            this.lugolsIodineDisplay = Objects.requireNonNull(lugolsIodineDisplay, "lugolsIodineDisplay");
            this.lugolsIodinePotion = Objects.requireNonNull(lugolsIodinePotion, "lugolsIodinePotion");
            this.radiations = Objects.requireNonNull(radiations, "radiations");
        }

        public Config(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            try {
                this.lugolsIodineDisplay = new BarConfig(section.getConfigurationSection("lugols-iodine-bar"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-bar section.", e);
            }

            try {
                this.lugolsIodinePotion = new LugolsIodinePotion.Config(section.getConfigurationSection("lugols-iodine-potion"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-potion section.", e);
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
                throw new InvalidConfigurationException("Could not parse radiation section.", e);
            }
        }

        public BarConfig lugolsIodineDisplay() {
            return this.lugolsIodineDisplay;
        }

        public LugolsIodinePotion.Config lugolsIodinePotion() {
            return this.lugolsIodinePotion;
        }

        public Iterable<Radiation.Config> radiations() {
            return this.radiations;
        }
    }
}
