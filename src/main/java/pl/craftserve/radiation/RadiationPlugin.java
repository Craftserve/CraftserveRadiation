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

import com.google.common.base.Preconditions;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import pl.craftserve.radiation.nms.RadiationNmsBridge;
import pl.craftserve.radiation.nms.V1_14ToV1_15NmsBridge;
import pl.craftserve.metrics.pluginmetrics.Metrics;
import pl.craftserve.metrics.pluginmetrics.RecordFactory;
import pl.craftserve.metrics.pluginmetrics.entity.Entity;
import pl.craftserve.metrics.pluginmetrics.entity.EntityRegistry;
import pl.craftserve.metrics.pluginmetrics.entity.NumberEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;

public final class RadiationPlugin extends JavaPlugin {
    private final List<Radiation> radiations = new ArrayList<>();

    private RadiationNmsBridge radiationNmsBridge;

    private LugolsIodineEffect effect;
    private LugolsIodinePotion potion;
    private LugolsIodineDisplay display;

    private CraftserveListener craftserveListener;
    private Metrics metrics;

    private RadiationNmsBridge initializeNmsBridge() {
        String serverVersion = RadiationNmsBridge.getServerVersion(getServer());
        this.getLogger().log(Level.INFO, "Detected server version: {0}", serverVersion);

        switch (serverVersion) {
            case "v1_14_R1":
                return new V1_14ToV1_15NmsBridge(this, "v1_14_R1");
            case "v1_15_R1":
                return new V1_14ToV1_15NmsBridge(this, "v1_15_R1");
            default:
                throw new RuntimeException("Unsupported server version: " + serverVersion);
        }
    }

    @Override
    public void onEnable() {
        try {
            this.radiationNmsBridge = this.initializeNmsBridge();
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Failed to launch CraftserveRadiation. Plausibly your server version is unsupported.", e);
            this.setEnabled(false);
            return;
        }

        Server server = this.getServer();
        this.saveDefaultConfig();

        //
        // Loading configuration
        //

        FileConfiguration config = this.getConfig();

        int potionDuration = config.getInt("potion-duration", 10); // in minutes
        if (potionDuration <= 0) {
            this.getLogger().log(Level.SEVERE, "\"potion-duration\" option must be positive.");
            this.setEnabled(false);
            return;
        }

        String regionName = config.getString("region-name", "km_safe_from_radiation");

        List<String> worldNames = config.getStringList("world-names");
        if (worldNames.isEmpty()) {
            this.getLogger().log(Level.SEVERE, "No world names defined. Loading in the overworld...");
            worldNames.add(server.getWorlds().get(0).getName()); // overworld is always at index 0
        }

        //
        // Enabling
        //

        RegionContainer regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();

        for (String worldName : worldNames) {
            if (regionName == null) {
                break;
            }

            Function<Player, Boolean> isSafe = player -> {
                if (!player.getWorld().getName().equals(worldName)) {
                    return true;
                }

                World world = player.getServer().getWorld(worldName);
                if (world == null) {
                    return true;
                }

                RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
                if (regionManager == null) {
                    return true;
                }

                ProtectedRegion region = regionManager.getRegion(regionName);
                if (region == null) {
                    return true;
                }

                BlockVector3 playerLocation = BukkitAdapter.asBlockVector(player.getLocation());
                return region.contains(playerLocation);
            };

            this.radiations.add(new Radiation(this, isSafe));
        }

        this.effect = new LugolsIodineEffect(this);
        this.potion = new LugolsIodinePotion(this, this.effect, "Płyn Lugola", potionDuration);
        this.display = new LugolsIodineDisplay(this, this.effect);

        this.radiations.forEach(Radiation::enable);

        this.effect.enable();
        this.potion.enable(this.radiationNmsBridge);
        this.display.enable();

        this.craftserveListener = new CraftserveListener(this);
        this.craftserveListener.enable();

        this.startMetrics();
    }

    @Override
    public void onDisable() {
        if (this.metrics != null) {
            this.metrics.stop();
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

    private void startMetrics() {
        Preconditions.checkArgument(this.metrics == null, "Metrics is already defined");
        this.metrics = Metrics.createWithDefaults(this);

        EntityRegistry entityRegistry = this.metrics.getEntityRegistry();
        try {
            EntityRegistry.ConstantEntityList.collect(MetricsEntities.class).forEach(entityRegistry::register);
        } catch (ReflectiveOperationException e) {
            this.getLogger().log(Level.SEVERE, "Could not collect entities for " + this.getDescription().getFullName() + " plugin metrics.", e);
        }

        this.metrics.start();
    }

    public interface MetricsEntities extends EntityRegistry.ConstantEntityList {
        Entity<Number> LUGOLS_IODINE_DURATION = new NumberEntity(key("lugols_iodine_duration"),
                forPlugin(plugin -> plugin.potion.getDuration().getSeconds()));
        Entity<Number> LUGOLS_IODINE_AFFECTED_COUNT = new NumberEntity(key("lugols_iodine_affected_count"),
                forPlugin(plugin -> (int) plugin.getServer().getOnlinePlayers().stream()
                        .filter(player -> plugin.effect.getEffect(player) != null)
                        .count()));

        static NamespacedKey key(String key) {
            Objects.requireNonNull(key, "key");
            return new NamespacedKey(RadiationPlugin.getPlugin(RadiationPlugin.class), key);
        }

        static <T> RecordFactory<T> forPlugin(Function<RadiationPlugin, T> supplier) {
            Objects.requireNonNull(supplier, "supplier");
            return metrics -> {
                Plugin plugin = metrics.getPlugin();
                return plugin instanceof RadiationPlugin ? supplier.apply((RadiationPlugin) plugin) : null;
            };
        }
    }
}
