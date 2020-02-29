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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RadiationPlugin extends JavaPlugin {
    private static final char COLOR_CODE = '&';

    public static String colorize(String input) {
        return input == null ? null : ChatColor.translateAlternateColorCodes(COLOR_CODE, input);
    }

    private static final Flag<Boolean> RADIATION_FLAG = new BooleanFlag("radiation");

    private final List<Radiation> radiations = new ArrayList<>();

    private RadiationNmsBridge radiationNmsBridge;
    private Flag<Boolean> radiationFlag;
    private Config config;

    private LugolsIodineEffect effect;
    private LugolsIodinePotion potion;
    private LugolsIodineDisplay display;

    private CraftserveListener craftserveListener;
    private MetricsHandler metricsHandler;

    private RadiationNmsBridge initializeNmsBridge() {
        String serverVersion = RadiationNmsBridge.getServerVersion(getServer());
        this.getLogger().log(Level.INFO, "Detected server version: {0}", serverVersion);

        switch (serverVersion) {
            case "v1_14_R1":
            case "v1_15_R1":
                return new V1_14ToV1_15NmsBridge(this, serverVersion);
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

        this.radiationFlag = this.getOrCreateRadiationFlag(flagRegistry);
    }

    @Override
    public void onEnable() {
        Server server = this.getServer();
        Logger logger = this.getLogger();
        this.saveDefaultConfig();

        try {
            this.radiationNmsBridge = this.initializeNmsBridge();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to launch CraftserveRadiation. Plausibly your server version is unsupported.", e);
            this.setEnabled(false);
            return;
        }

        //
        // Configuration
        //

        FileConfiguration config = this.getConfig();
        this.migrate(config, config.getInt("file-protocol-version-dont-touch", -1));

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

        this.radiations.add(new Radiation(this, new Radiation.FlagMatcher(this.radiationFlag), this.config.radiation()));

        this.effect = new LugolsIodineEffect(this);
        this.potion = new LugolsIodinePotion(this, this.effect, this.config.lugolsIodinePotion());
        this.display = new LugolsIodineDisplay(this, this.effect, this.config.lugolsIodineDisplay());

        this.radiations.forEach(Radiation::enable);

        this.effect.enable();
        this.potion.enable(this.radiationNmsBridge);
        this.display.enable();

        this.craftserveListener = new CraftserveListener(this);
        this.craftserveListener.enable();

        this.metricsHandler = new MetricsHandler(this, server, logger, this.effect, this.potion);
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

        if (this.display != null) {
            this.display.disable();
        }

        if (this.potion != null) {
            this.potion.disable(this.radiationNmsBridge);
        }

        if (this.effect != null) {
            this.effect.disable();
        }

        this.radiations.forEach(Radiation::disable);
        this.radiations.clear();
    }

    public Flag<Boolean> getRadiationFlag() {
        return this.radiationFlag;
    }

    public Config getPluginConfig() {
        return this.config;
    }

    @SuppressWarnings("unchecked")
    private Flag<Boolean> getOrCreateRadiationFlag(FlagRegistry flagRegistry) {
        Objects.requireNonNull(flagRegistry, "flagRegistry");

        Flag<Boolean> flag = (Flag<Boolean>) flagRegistry.get(RADIATION_FLAG.getName());
        if (flag != null) {
            return flag;
        }

        flag = RADIATION_FLAG;
        flagRegistry.register(flag);
        return flag;
    }

    //
    // Migrations
    //

    private void migrate(ConfigurationSection section, int protocol) {
        Objects.requireNonNull(section, "section");

        if (protocol == -1) {
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
            String legacyRegionId = section.getString("region-name");
            AtomicBoolean logged = new AtomicBoolean();
            section.getStringList("world-names").forEach(worldName -> {
                if (logged.compareAndSet(false, true)) {
                    this.getLogger().warning(
                            "Enabling in legacy region-name mode! The plugin will try to automatically migrate to the new flag-based system.\n" +
                            "If everything went fine please completely remove your config.yml file.");
                }

                this.migrateFromRegionId(worldName, legacyRegionId);
            });
        }
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
            this.getLogger().warning(error + ": the world is unloaded.");
            return;
        }

        Radiation.WorldGuardMatcher matcher = (player, regionContainer) -> {
            throw new UnsupportedOperationException();
        };

        RegionContainer regionContainer = matcher.getRegionContainer();
        if (regionContainer == null) {
            this.getLogger().warning(error + "region container is not present.");
            return;
        }

        RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            this.getLogger().warning(error + "region manager for the world is not present.");
            return;
        }

        ProtectedRegion legacyRegion = regionManager.getRegion(regionId);
        if (legacyRegion == null) {
            this.getLogger().warning(error + "legacy region is not present.");
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
        this.getLogger().info("Region " + regionId + " in world " + worldName +
                " has been successfully migrated to the new flag-based system.");
    }

    //
    // Config
    //

    public static class Config {
        private final BarConfig lugolsIodineDisplay;
        private final LugolsIodinePotion.Config lugolsIodinePotion;
        private final Radiation.Config radiation;

        public Config(BarConfig lugolsIodineDisplay, LugolsIodinePotion.Config lugolsIodinePotion, Radiation.Config radiation) {
            this.lugolsIodineDisplay = Objects.requireNonNull(lugolsIodineDisplay, "lugolsIodineDisplay");
            this.lugolsIodinePotion = Objects.requireNonNull(lugolsIodinePotion, "lugolsIodinePotion");
            this.radiation = Objects.requireNonNull(radiation, "radiation");
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
                this.radiation = new Radiation.Config(section.getConfigurationSection("radiation"));
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

        public Radiation.Config radiation() {
            return this.radiation;
        }
    }
}
