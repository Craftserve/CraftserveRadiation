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

import com.google.common.io.Closer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class LugolsIodineEffect implements Listener {
    static final Logger logger = Logger.getLogger(LugolsIodineEffect.class.getName());

    private static final Duration TASK_PERIOD = Duration.ofSeconds(1);

    private NamespacedKey entityStorageKey;
    private NamespacedKey legacyInitialSecondsKey;
    private NamespacedKey legacySecondsLeftKey;

    private final Plugin plugin;
    private Task task;

    public LugolsIodineEffect(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void enable() {
        this.entityStorageKey = new NamespacedKey(this.plugin, "effect_data");
        this.legacyInitialSecondsKey = new NamespacedKey(this.plugin, "initial_seconds");
        this.legacySecondsLeftKey = new NamespacedKey(this.plugin, "seconds_left");

        this.task = new Task();
        this.task.runTaskTimer(this.plugin, 0L, TASK_PERIOD.toMillis() / 50L);

        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);

        if (this.task != null) {
            this.task.cancel();
        }
    }

    public void appendEffect(Entity entity, Effect effect) throws IOException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(effect, "effect");

        PersistentDataContainer container = entity.getPersistentDataContainer();
        List<Effect> effectList = new ArrayList<>(this.readEffects(container));
        ListIterator<Effect> iterator = effectList.listIterator();

        AtomicBoolean replaced = new AtomicBoolean();
        while (iterator.hasNext()) {
            Effect next = iterator.next();
            if (next.getId().equals(effect.getId())) {
                try {
                    iterator.set(this.merge(next, effect)); // merge and replace existing effect if ID matches
                } finally {
                    replaced.set(true);
                }
                break;
            }
        }

        if (!replaced.get()) {
            effectList.add(effect);
        }

        this.writeEffects(container, effectList);
    }

    public List<Effect> getEffects(Entity entity) throws IOException {
        Objects.requireNonNull(entity, "entity");

        try {
            this.migrateLegacyEffect(entity);
        } catch (IOException e) {
            throw new IOException("Could not migrate legacy effects for '" + entity + "'.", e);
        }

        return this.readEffects(entity.getPersistentDataContainer()).stream()
                .filter(effect -> !effect.getTimeLeft().isNegative())
                .collect(Collectors.toList());
    }

    public void removeAllEffects(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        this.removeAllEffects(entity.getPersistentDataContainer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMilkBucketConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType().equals(Material.MILK_BUCKET)) {
            this.removeAllEffects(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        this.removeAllEffects(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemOfUndyingUse(EntityResurrectEvent event) {
        this.removeAllEffects(event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRadiation(RadiationEvent event) {
        Player player = event.getPlayer();
        Radiation radiation = event.getRadiation();

        List<Effect> effects;
        try {
            effects = this.getEffects(player);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not get lugol's iodine effects on '" + player.getName() + "'.", e);
            return;
        }

        for (Effect effect : effects) {
            if (effect.canEnter(radiation)) {
                event.setCancelled(true);
                event.setShowWarning(true);
                break;
            }
        }
    }


    private Effect merge(Effect existing, Effect replacement) {
        Objects.requireNonNull(existing, "exsiting");
        Objects.requireNonNull(replacement, "replacement");

        String id = existing.id;
        Duration initialDuration = replacement.initialDuration;
        Duration timeLeft = Duration.ofMillis(Math.max(existing.timeLeft.toMillis(), replacement.timeLeft.toMillis()));
        List<String> radiationIds = replacement.radiationIds;

        return new Effect(id, initialDuration, timeLeft, radiationIds);
    }

    //
    // Storage Access
    //

    private void migrateLegacyEffect(Entity entity) throws IOException {
        Objects.requireNonNull(entity, "entity");

        PersistentDataContainer container = entity.getPersistentDataContainer();

        int initialSeconds;
        int secondsLeft;
        try {
            initialSeconds = container.getOrDefault(this.legacyInitialSecondsKey, PersistentDataType.INTEGER, -1);
            secondsLeft = container.getOrDefault(this.legacySecondsLeftKey, PersistentDataType.INTEGER, -1);
        } finally {
            container.remove(this.legacyInitialSecondsKey);
            container.remove(this.legacySecondsLeftKey);
        }

        if (initialSeconds == -1 || secondsLeft <= -1) {
            return;
        }

        String id = "__legacy_effect__"; // we hope this is unique
        Duration initialDuration = Duration.ofSeconds(initialSeconds);
        Duration timeLeft = Duration.ofSeconds(secondsLeft);
        List<String> radiationIds = null; // legacy effects will work in all zones

        Effect effect = new Effect(id, initialDuration, timeLeft, radiationIds);
        this.appendEffect(entity, effect);
    }

    private List<Effect> readEffects(PersistentDataContainer container) throws IOException {
        Objects.requireNonNull(container, "container");

        if (!container.has(this.entityStorageKey, PersistentDataType.BYTE_ARRAY)) {
            return Collections.emptyList();
        }

        byte[] bytes = container.get(this.entityStorageKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) {
            return Collections.emptyList();
        }

        List<Effect> effectList = new ArrayList<>();
        try (Closer closer = Closer.create()) {
            ByteArrayInputStream byteArrayInputStream = closer.register(new ByteArrayInputStream(bytes));
            DataInputStream dataInputStream = closer.register(new DataInputStream(byteArrayInputStream));

            short protocolVersion = dataInputStream.readShort();
            if (protocolVersion != 0) {
                throw new IOException("Unsupported protocol version: " + protocolVersion);
            }

            int effectListCount = dataInputStream.readInt();
            for (int i = 0; i < effectListCount; i++) {
                String id = dataInputStream.readUTF();
                Duration initialDuration = Duration.ofMillis(dataInputStream.readLong());
                Duration timeLeft = Duration.ofMillis(dataInputStream.readLong());
                List<String> radiationIds = null;

                int radiationIdCount = dataInputStream.readInt();
                for (int j = 0; j < radiationIdCount; j++) {
                    if (radiationIds == null) {
                        radiationIds = new ArrayList<>();
                    }
                    radiationIds.add(dataInputStream.readUTF());
                }

                effectList.add(new Effect(id, initialDuration, timeLeft, radiationIds));
            }
        }

        return effectList;
    }

    private void writeEffects(PersistentDataContainer container, List<Effect> effectList) throws IOException {
        Objects.requireNonNull(container, "container");

        if (effectList == null || effectList.isEmpty()) {
            this.removeAllEffects(container);
            return;
        }

        byte[] bytes;
        try (Closer closer = Closer.create()) {
            ByteArrayOutputStream byteArrayOutputStream = closer.register(new ByteArrayOutputStream());
            DataOutputStream dataOutputStream = closer.register(new DataOutputStream(byteArrayOutputStream));

            dataOutputStream.writeShort(0); // protocol version, for future changes
            dataOutputStream.writeInt(effectList.size());

            for (Effect effect : effectList) {
                dataOutputStream.writeUTF(effect.id);
                dataOutputStream.writeLong(effect.initialDuration.toMillis());
                dataOutputStream.writeLong(effect.timeLeft.toMillis());

                if (effect.radiationIds == null) {
                    dataOutputStream.writeInt(0);
                } else {
                    dataOutputStream.writeInt(effect.radiationIds.size());
                    for (String radiationId : effect.radiationIds) {
                        dataOutputStream.writeUTF(radiationId);
                    }
                }
            }

            bytes = byteArrayOutputStream.toByteArray();
        }

        container.set(this.entityStorageKey, PersistentDataType.BYTE_ARRAY, bytes);
    }

    private void removeAllEffects(PersistentDataContainer container) {
        Objects.requireNonNull(container, "container");

        container.remove(this.entityStorageKey);
        container.remove(this.legacyInitialSecondsKey);
        container.remove(this.legacySecondsLeftKey);
    }

    public static class Effect {
        private final String id;
        private final Duration initialDuration;
        private final Duration timeLeft;
        private final List<String> radiationIds;

        public Effect(String id, Duration duration, List<String> radiationIds) {
            this(id, duration, duration, radiationIds);
        }

        public Effect(String id, Duration initialDuration, Duration timeLeft, List<String> radiationIds) {
            this.id = Objects.requireNonNull(id, "id");
            this.initialDuration = initialDuration;
            this.timeLeft = timeLeft;
            this.radiationIds = radiationIds;
        }

        public String getId() {
            return this.id;
        }

        public Duration getInitialDuration() {
            return this.initialDuration;
        }

        public Duration getTimeLeft() {
            return this.timeLeft;
        }

        public Effect timePassed(Duration timePassed) {
            Objects.requireNonNull(timePassed, "timePassed");

            Duration timeLeft = this.timeLeft.minus(timePassed);
            return new Effect(this.id, this.initialDuration, timeLeft, this.radiationIds);
        }

        public boolean canEnter(Radiation radiation) {
            Objects.requireNonNull(radiation, "radiation");

            if (this.radiationIds == null) {
                // Null list makes the effect work in all radiation zones.
                return true;
            }

            return this.radiationIds.contains(radiation.getId());
        }
    }

    class Task extends BukkitRunnable {
        @Override
        public void run() {
            for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                try {
                    this.tick(onlinePlayer, TASK_PERIOD);
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Could not tick effects on player '" + onlinePlayer.getName() + "'.", e);
                }
            }
        }

        private void tick(Player player, Duration timePassed) throws IOException {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(timePassed, "timePassed");

            List<Effect> effectList;
            try {
                effectList = getEffects(player);
            } catch (IOException e) {
                throw new IOException("Could not get effects.", e);
            }

            effectList = effectList.stream()
                    .map(effect -> effect.timePassed(timePassed))
                    .collect(Collectors.toList());

            try {
                writeEffects(player.getPersistentDataContainer(), effectList);
            } catch (IOException e) {
                throw new IOException("Could not write effects.", e);
            }
        }
    }
}
