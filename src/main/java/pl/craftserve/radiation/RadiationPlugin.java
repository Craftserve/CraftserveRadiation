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

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.craftserve.radiation.nms.RadiationNmsBridge;
import pl.craftserve.radiation.nms.V1_14ToV1_15NmsBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

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

        FlagRegistry flagRegistry = WorldGuard.getInstance().getFlagRegistry();
        if (flagRegistry == null) {
            throw new IllegalStateException("Flag registry is not set!");
        }

        this.radiationFlag = this.getOrCreateRadiationFlag(flagRegistry);
        this.radiations.add(new Radiation(this, new Radiation.FlagMatcher(this.radiationFlag)));

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

        // Legacy region name support
        if (regionName != null) {
            worldNames.forEach(worldName -> {
                Radiation.Matcher matcher = new Radiation.NotRegionIdMatcher(worldName, regionName);
                this.radiations.add(new Radiation(this, matcher));
            });
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

        this.metricsHandler = new MetricsHandler(this, server, this.getLogger(), this.effect, this.potion);
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
}
