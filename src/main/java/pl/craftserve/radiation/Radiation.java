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

import com.google.common.collect.ImmutableSet;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

public class Radiation implements Listener {
    static final PotionEffect[] EFFECTS = new PotionEffect[] {
            createWitherEffect(),
            createHungerEffect()
    };

    private static PotionEffect createWitherEffect() {
        return new PotionEffect(
                PotionEffectType.WITHER, // type
                20 * 5, // duration, ticks
                4, // amplifier, Wither V
                false, // ambient
                false, // particles
                false // icon
        );
    }

    private static PotionEffect createHungerEffect() {
        return new PotionEffect(
                PotionEffectType.HUNGER, // type
                20 * 5, // duration, ticks
                0, // amplifier, Hunger I
                false, // ambient
                false, // particles
                false // icon
        );
    }

    private static final String TITLE = ChatColor.DARK_RED + "Strefa radiacji";

    private final Set<UUID> affectedPlayers = new HashSet<>(128);

    private final Plugin plugin;
    private final Matcher matcher;

    private BossBar bossBar;
    private Task task;

    public Radiation(Plugin plugin, Matcher matcher) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    public void enable() {
        Server server = this.plugin.getServer();
        this.bossBar = server.createBossBar(TITLE, BarColor.RED, BarStyle.SOLID, BarFlag.DARKEN_SKY);

        this.task = new Task();
        this.task.runTaskTimer(this.plugin, 20L, 20L);

        server.getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);

        if (this.task != null) {
            this.task.cancel();
        }

        if (this.bossBar != null) {
            this.bossBar.removeAll();
        }

        this.affectedPlayers.clear();
    }

    public boolean addAffectedPlayer(Player player, boolean addBossBar) {
        Objects.requireNonNull(player, "player");

        boolean ok = this.affectedPlayers.add(player.getUniqueId());
        if (ok && addBossBar) {
            boolean contains = this.bossBar.getPlayers().contains(player);

            if (!contains) {
                this.addBossBar(player);
                this.broadcastEscape(player);
            }
        }

        return ok;
    }

    private void addBossBar(Player player) {
        Objects.requireNonNull(player, "player");
        this.bossBar.addPlayer(player);
    }

    private void broadcastEscape(Player player) {
        Objects.requireNonNull(player, "player");

        String message = ChatColor.RED + player.getDisplayName() + ChatColor.RESET + ChatColor.RED + " uciekł/a do strefy radioaktywnej.";
        this.plugin.getLogger().log(Level.INFO, message);

        for (Player online : this.plugin.getServer().getOnlinePlayers()) {
            if (online.canSee(player)) {
                online.sendMessage(message);
            }
        }
    }

    public Set<UUID> getAffectedPlayers() {
        return ImmutableSet.copyOf(this.affectedPlayers);
    }

    public boolean removeAffectedPlayer(Player player, boolean removeBossBar) {
        Objects.requireNonNull(player, "player");

        boolean ok = this.affectedPlayers.remove(player.getUniqueId());
        if (removeBossBar) {
            this.removeBossBar(player);
        }

        return ok;
    }

    public void removeBossBar(Player player) {
        Objects.requireNonNull(player, "player");
        this.bossBar.removePlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeAffectedPlayer(event.getPlayer(), true);
    }

    class Task extends BukkitRunnable {
        @Override
        public void run() {
            Server server = plugin.getServer();

            server.getOnlinePlayers().forEach(player -> {
                if (matcher.test(player)) {
                    RadiationEvent event = new RadiationEvent(player);
                    server.getPluginManager().callEvent(event);

                    boolean showBossBar = event.shouldShowWarning();
                    boolean cancel = event.isCancelled();

                    if (!cancel) {
                        this.hurt(player);
                        addAffectedPlayer(player, showBossBar);
                        return;
                    }

                    if (showBossBar) {
                        addBossBar(player);
                    }
                } else {
                    removeAffectedPlayer(player, true);
                }
            });
        }

        private void hurt(Player player) {
            Objects.requireNonNull(player, "player");

            for (PotionEffect effect : EFFECTS) {
                player.addPotionEffect(effect, true);
            }
        }
    }

    /**
     * Something that tests if the player can be affected by the radiation.
     */
    public interface Matcher extends Predicate<Player> {
    }

    /**
     * Base interface for all matchers using WorldGuard to test the radiation.
     */
    public interface WorldGuardMatcher extends Matcher {
        @Override
        default boolean test(Player player) {
            WorldGuardPlatform platform = WorldGuard.getInstance().getPlatform();
            if (platform == null) {
                return false;
            }

            RegionContainer regionContainer = platform.getRegionContainer();
            if (regionContainer == null) {
                return false;
            }

            return this.test(player, regionContainer);
        }

        boolean test(Player player, RegionContainer regionContainer);
    }

    /**
     * Tests if the given flag matches radiation.
     */
    public static class FlagMatcher implements WorldGuardMatcher {
        private final Flag<Boolean> flag;

        public FlagMatcher(Flag<Boolean> flag) {
            this.flag = Objects.requireNonNull(flag, "flag");
        }

        @Override
        public boolean test(Player player, RegionContainer regionContainer) {
            Location location = BukkitAdapter.adapt(player.getLocation());
            ApplicableRegionSet regions = regionContainer.createQuery().getApplicableRegions(location);

            Boolean value = regions.queryValue(WorldGuardPlugin.inst().wrapPlayer(player), this.flag);
            return value != null && value;
        }
    }

    /**
     * Tests if the given region ID not matches radiation.
     */
    public static class NotRegionIdMatcher implements WorldGuardMatcher {
        private final String worldName;
        private final String regionId;

        public NotRegionIdMatcher(String worldName, String regionId) {
            this.worldName = Objects.requireNonNull(worldName, "worldName");
            this.regionId = Objects.requireNonNull(regionId, "regionId");
        }

        @Override
        public boolean test(Player player, RegionContainer regionContainer) {
            World world = player.getWorld();
            if (!world.getName().equals(this.worldName)) {
                return false;
            }

            RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
            if (regionManager == null) {
                return false;
            }

            ProtectedRegion region = regionManager.getRegion(this.regionId);
            if (region == null) {
                return false;
            }

            return !region.contains(BukkitAdapter.asBlockVector(player.getLocation()));
        }
    }
}
