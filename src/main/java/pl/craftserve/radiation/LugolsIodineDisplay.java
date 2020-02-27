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

import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class LugolsIodineDisplay implements Listener {
    private final Map<UUID, BossBar> displayMap = new HashMap<>(128);
    private final Plugin plugin;
    private final LugolsIodineEffect effect;
    private final BaseConfig.BarConfig config;

    private Task task;

    public LugolsIodineDisplay(Plugin plugin, LugolsIodineEffect effect, BaseConfig.BarConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void enable() {
        this.task = new Task();
        this.task.runTaskTimer(this.plugin, 20L, 20L);

        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);

        if (this.task != null) {
            this.task.cancel();
        }

        this.displayMap.values().forEach(BossBar::removeAll);
        this.displayMap.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        BossBar bossBar = this.displayMap.get(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    private void add(Player player, LugolsIodineEffect.Effect effect) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(effect, "effect");

        BossBar bossBar = this.displayMap.computeIfAbsent(player.getUniqueId(), playerId -> this.createBossBar());
        bossBar.setProgress(effect.getSecondsLeft() / (double) effect.getInitialSeconds());
        bossBar.addPlayer(player);
    }

    private void remove(Player player) {
        Objects.requireNonNull(player, "player");

        UUID playerId = player.getUniqueId();
        BossBar bossBar = this.displayMap.get(playerId);

        if (bossBar != null) {
            bossBar.removePlayer(player);
            this.displayMap.remove(playerId);
        }
    }

    private BossBar createBossBar() {
        return this.config.create(this.plugin.getServer());
    }

    class Task extends BukkitRunnable {
        @Override
        public void run() {
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                LugolsIodineEffect.Effect playerEffect = effect.getEffect(player);

                if (playerEffect != null && playerEffect.getSecondsLeft() > 0) {
                    add(player, playerEffect);
                    return;
                }

                remove(player);
            });
        }
    }
}
