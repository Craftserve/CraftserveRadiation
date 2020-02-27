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
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.craftserve.radiation.nms.RadiationNmsBridge;
import pl.craftserve.radiation.nms.V1_14ToV1_15NmsBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RadiationPlugin extends JavaPlugin {
    private static final Flag<Boolean> RADIATION_FLAG = new BooleanFlag("radiation");

    private final List<Radiation> radiations = new ArrayList<>();

    private RadiationNmsBridge radiationNmsBridge;
    private Flag<Boolean> radiationFlag;

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

        this.radiations.add(new Radiation(this, new Radiation.FlagMatcher(this.radiationFlag)));

        //
        // Loading configuration
        //

        FileConfiguration config = this.getConfig();
        this.migrate(config, config.getInt("file-protocol-version-dont-touch", -1));

        int potionDuration = config.getInt("potion-duration", 10); // in minutes
        if (potionDuration <= 0) {
            logger.log(Level.SEVERE, "\"potion-duration\" option must be positive.");
            this.setEnabled(false);
            return;
        }

        //
        // Enabling
        //

        this.effect = new LugolsIodineEffect(this);
        this.potion = new LugolsIodinePotion(this, this.effect, "Płyn Lugola", potionDuration);
        this.display = new LugolsIodineDisplay(this, this.effect);

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
            int potionDuration = section.getInt("potion-duration", -1); // in minutes
            if (potionDuration > 0) {
                section.set("lugols-iodine-potion.duration", potionDuration);
            }

            // Migrate from the old region-ID based system.
            String legacyRegionId = section.getString("region-name");

            boolean[] logged = new boolean[1];
            section.getStringList("world-names").forEach(worldName -> {
                if (!logged[0]) {
                    this.getLogger().warning(
                            "Enabling in legacy region-name mode! The plugin will try to automatically migrate to the new flag-based system.\n" +
                            "If everything went fine please completely remove your config.yml file.");
                    logged[0] = true;
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

    public interface Config {
        BaseConfig.BarConfig lugolsIodineDisplay();
        LugolsIodinePotion.Config lugolsIodinePotion();
        Radiation.Config radiation();
    }

    public static class ConfigImpl extends BaseConfig implements Config {
        private final BaseConfig.BarConfig lugolsIodineDisplay;
        private final LugolsIodinePotion.Config lugolsIodinePotion;
        private final Radiation.Config radiation;

        public ConfigImpl(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            try {
                this.lugolsIodineDisplay = new BaseConfig.BarConfigImpl(section.getConfigurationSection("lugols-iodine-bar"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-bar section.", e);
            }

            try {
                this.lugolsIodinePotion = new LugolsIodinePotion.ConfigImpl(section.getConfigurationSection("lugols-iodine-potion"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse lugols-iodine-potion section.", e);
            }

            try {
                this.radiation = new Radiation.ConfigImpl(section.getConfigurationSection("radiation"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse radiation section.", e);
            }
        }

        @Override
        public BaseConfig.BarConfig lugolsIodineDisplay() {
            return this.lugolsIodineDisplay;
        }

        @Override
        public LugolsIodinePotion.Config lugolsIodinePotion() {
            return this.lugolsIodinePotion;
        }

        @Override
        public Radiation.Config radiation() {
            return this.radiation;
        }
    }
}
