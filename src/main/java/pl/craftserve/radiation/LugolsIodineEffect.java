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

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

public class LugolsIodineEffect implements Listener {
    private NamespacedKey initialSecondsKey;
    private NamespacedKey secondsLeftKey;

    private final RadiationPlugin plugin;
    private Task task;

    public LugolsIodineEffect(RadiationPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void enable() {
        this.initialSecondsKey = this.plugin.createKey("initial_seconds");
        this.secondsLeftKey = this.plugin.createKey("seconds_left");

        this.task = new Task();
        this.task.runTaskTimer(this.plugin, 0L, 20L);

        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);

        if (this.task != null) {
            this.task.cancel();
        }
    }

    public Effect getEffect(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        PersistentDataContainer container = entity.getPersistentDataContainer();
        int initialSeconds = container.getOrDefault(this.initialSecondsKey, PersistentDataType.INTEGER, -1);
        int secondsLeft = container.getOrDefault(this.secondsLeftKey, PersistentDataType.INTEGER, -1);

        if (initialSeconds != -1 && secondsLeft != -1) {
            return new Effect(initialSeconds, secondsLeft);
        }

        return null;
    }

    public void setEffect(Entity entity, int seconds) {
        Objects.requireNonNull(entity, "entity");
        this.setEffect(entity, new Effect(seconds, seconds));
    }

    public void setEffect(Entity entity, Effect effect) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(effect, "effect");

        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(this.initialSecondsKey, PersistentDataType.INTEGER, effect.initialSeconds);
        container.set(this.secondsLeftKey, PersistentDataType.INTEGER, effect.secondsLeft);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMilkBucketConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType().equals(Material.MILK_BUCKET)) {
            PersistentDataContainer container = event.getPlayer().getPersistentDataContainer();
            container.remove(this.initialSecondsKey);
            container.remove(this.secondsLeftKey);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRadiation(RadiationEvent event) {
        Effect effect = this.getEffect(event.getPlayer());
        if (effect != null && effect.getSecondsLeft() > 0) {
            event.setCancelled(true);
            event.setShowWarning(true);
        }
    }

    public static class Effect {
        private final int initialSeconds;
        private final int secondsLeft;

        public Effect(int initialSeconds, int secondsLeft) {
            this.initialSeconds = initialSeconds;
            this.secondsLeft = secondsLeft;
        }

        public int getInitialSeconds() {
            return this.initialSeconds;
        }

        public int getSecondsLeft() {
            return this.secondsLeft;
        }
    }

    class Task extends BukkitRunnable {
        @Override
        public void run() {
            plugin.getServer().getOnlinePlayers().forEach(this::tick);
        }

        void tick(Player player) {
            Objects.requireNonNull(player, "player");

            PersistentDataContainer container = player.getPersistentDataContainer();
            if (!container.has(secondsLeftKey, PersistentDataType.INTEGER)) {
                return;
            }

            Integer secondsLeft = container.getOrDefault(secondsLeftKey, PersistentDataType.INTEGER, 0);
            if (secondsLeft > 0) {
                container.set(secondsLeftKey, PersistentDataType.INTEGER, --secondsLeft);
            } else {
                container.remove(initialSecondsKey);
                container.remove(secondsLeftKey);
            }
        }
    }
}
