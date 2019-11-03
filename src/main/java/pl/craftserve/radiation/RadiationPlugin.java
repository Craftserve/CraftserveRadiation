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
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;

public final class RadiationPlugin extends JavaPlugin {
    private final List<Radiation> radiations = new ArrayList<>();

    private LugolsIodineEffect effect;
    private LugolsIodinePotion potion;
    private LugolsIodineDisplay display;

    private CraftserveListener craftserveListener;

    @Override
    public void onEnable() {
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
        this.potion.enable();
        this.display.enable();

        this.craftserveListener = new CraftserveListener(this);
        this.craftserveListener.enable();
    }

    @Override
    public void onDisable() {
        if (this.craftserveListener != null) {
            this.craftserveListener.disable();
        }

        if (this.display != null) {
            this.display.disable();
        }

        if (this.potion != null) {
            this.potion.disable();
        }

        if (this.effect != null) {
            this.effect.disable();
        }

        this.radiations.forEach(Radiation::disable);
        this.radiations.clear();
    }
}
