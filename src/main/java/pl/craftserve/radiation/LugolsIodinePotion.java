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

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;
import pl.craftserve.radiation.nms.RadiationNmsBridge;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;

public class LugolsIodinePotion implements Listener, Predicate<ItemStack> {
    private static final Material INGREDIENT = Material.GHAST_TEAR;
    private static final PotionType BASE_POTION = PotionType.THICK;

    private static final byte TRUE = 1;

    private final Plugin plugin;
    private final LugolsIodineEffect effect;
    private final Config config;

    private NamespacedKey potionKey;
    private NamespacedKey durationKey;
    private NamespacedKey durationSecondsKey;

    public LugolsIodinePotion(Plugin plugin, LugolsIodineEffect effect, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void enable(RadiationNmsBridge nmsBridge) {
        Objects.requireNonNull(nmsBridge, "nmsBridge");

        this.potionKey = new NamespacedKey(this.plugin, "lugols_iodine");
        this.durationKey = new NamespacedKey(this.plugin, "duration");
        this.durationSecondsKey = new NamespacedKey(this.plugin, "duration_seconds");

        nmsBridge.registerLugolsIodinePotion(this.potionKey);
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable(RadiationNmsBridge nmsBridge) {
        Objects.requireNonNull(nmsBridge, "nmsBridge");

        HandlerList.unregisterAll(this);
        nmsBridge.unregisterLugolsIodinePotion(this.potionKey);
    }

    public Duration getDuration() {
        return this.config.duration();
    }

    @Override
    public boolean test(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");

        if (!itemStack.hasItemMeta()) {
            return false;
        }

        PersistentDataContainer container = itemStack.getItemMeta().getPersistentDataContainer();
        if (container.has(this.potionKey, PersistentDataType.BYTE)) {
            Byte value = container.get(this.potionKey, PersistentDataType.BYTE);
            return value != null && value == TRUE;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!this.test(item)) {
            return;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();

        int durationSeconds = 0;
        if (container.has(this.durationSecondsKey, PersistentDataType.INTEGER)) {
            durationSeconds = container.getOrDefault(this.durationSecondsKey, PersistentDataType.INTEGER, 0);
        } else if (container.has(this.durationKey, PersistentDataType.INTEGER)) {
            durationSeconds = (int) TimeUnit.MINUTES.toSeconds(container.getOrDefault(this.durationKey, PersistentDataType.INTEGER, 0)); // legacy
        }

        if (durationSeconds <= 0) {
            return;
        }

        Player player = event.getPlayer();
        this.effect.setEffect(player, durationSeconds);
        this.broadcastConsumption(player);
    }

    private void broadcastConsumption(Player player) {
        Objects.requireNonNull(player, "player");

        String rawMessage = this.config.drinkMessage();
        if (rawMessage == null) {
            return;
        }

        String message = ChatColor.RED + MessageFormat.format(rawMessage, player.getDisplayName() + ChatColor.RESET, this.config.name());
        this.plugin.getLogger().log(Level.INFO, message);

        for (Player online : this.plugin.getServer().getOnlinePlayers()) {
            if (online.canSee(player)) {
                online.sendMessage(message);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inventory = event.getContents();
        BrewingStandWindow window = BrewingStandWindow.fromArray(inventory.getContents());

        if (!window.ingredient.getType().equals(INGREDIENT)) {
            return;
        }

        boolean[] modified = new boolean[BrewingStandWindow.SLOTS];

        for (int i = 0; i < BrewingStandWindow.SLOTS; i++) {
            ItemStack result = window.results[i];
            if (result == null) {
                continue; // nothing in this slot
            }

            ItemMeta itemMeta = result.getItemMeta();
            if (!(itemMeta instanceof PotionMeta)) {
                continue;
            }

            PotionMeta potionMeta = (PotionMeta) itemMeta;
            if (potionMeta.getBasePotionData().getType().equals(BASE_POTION)) {
                this.convert(potionMeta);
                result.setItemMeta(potionMeta);

                modified[i] = true;
            }
        }

        // delay this, because nms changes item stacks after BrewEvent is called
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            for (int i = 0; i < BrewingStandWindow.SLOTS; i++) {
                if (modified[i]) {
                    ItemStack[] contents = inventory.getContents();
                    contents[i] = window.getResult(i);
                    inventory.setContents(contents);
                }
            }
        });
    }

    private void convert(PotionMeta potionMeta) {
        Objects.requireNonNull(potionMeta, "potionMeta");

        Duration duration = this.config.duration();
        String formattedDuration = this.formatDuration(this.config.duration());

        potionMeta.setDisplayName(ChatColor.AQUA + this.config.name());
        potionMeta.setLore(Collections.singletonList(ChatColor.BLUE + MessageFormat.format(this.config.description(), formattedDuration)));

        PersistentDataContainer container = potionMeta.getPersistentDataContainer();
        container.set(this.potionKey, PersistentDataType.BYTE, TRUE);
        container.set(this.durationSecondsKey, PersistentDataType.INTEGER, (int) duration.getSeconds());
    }

    private String formatDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");

        long seconds = duration.getSeconds();
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long secondsLeft = seconds - (TimeUnit.MINUTES.toSeconds(minutes));

        return (minutes < 10 ? "0" : "") +  minutes + ":" + (secondsLeft < 10 ? "0" : "") + secondsLeft;
    }

    /**
     * Class to simplify brewing stand window fields.
     * {@link BrewerInventory}
     */
    static class BrewingStandWindow {
        static final int SLOTS = 3;

        final ItemStack ingredient;
        final ItemStack fuel;
        final ItemStack[] results;

        BrewingStandWindow(ItemStack ingredient, ItemStack fuel, ItemStack[] results) {
            this.ingredient = ingredient;
            this.fuel = fuel;
            this.results = Objects.requireNonNull(results, "results");

            if (results.length != 3) {
                throw new IllegalArgumentException(results.length + " array length, expected 3");
            }
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", BrewingStandWindow.class.getSimpleName() + "[", "]")
                    .add("ingredient=" + ingredient)
                    .add("fuel=" + fuel)
                    .add("results=" + Arrays.toString(results))
                    .toString();
        }

        static BrewingStandWindow fromArray(ItemStack[] contents) {
            if (contents.length != 5) {
                throw new IllegalArgumentException("length is " + contents.length + ", expected 5!");
            }

            ItemStack ingredient = Objects.requireNonNull(contents[3], "ingredient shouldn't be null, right?");;
            ItemStack fuel = contents[4];

            return new BrewingStandWindow(ingredient, fuel, Arrays.copyOfRange(contents, 0, 3));
        }

        public ItemStack getResult(int index) {
            return this.results[index];
        }
    }

    //
    // Config
    //

    public static class Config {
        private final String name;
        private final String description;
        private final Duration duration;
        private final String drinkMessage;

        public Config(String name, String description, Duration duration, String drinkMessage) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.duration = Objects.requireNonNull(duration, "duration");
            this.drinkMessage = Objects.requireNonNull(drinkMessage, "drinkMessage");
        }

        public Config(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            this.name = section.getString("name", "Płyn Lugola");
            this.description = section.getString("description", "Odporność na promieniowanie ({0})");
            this.duration = Duration.ofSeconds(section.getInt("duration", 600));
            this.drinkMessage = RadiationPlugin.colorize(section.getString("drink-message", ChatColor.RED + "{0}" + ChatColor.RED + " wypił/a {1}."));

            if (this.duration.isZero() || this.duration.isNegative()) {
                throw new InvalidConfigurationException("Given potion duration must be positive.");
            }
        }

        public String name() {
            return this.name;
        }

        public String description() {
            return this.description;
        }

        public Duration duration() {
            return this.duration;
        }

        public String drinkMessage() {
            return this.drinkMessage;
        }
    }
}
