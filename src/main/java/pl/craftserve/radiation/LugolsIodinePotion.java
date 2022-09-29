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
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;
import pl.craftserve.radiation.nms.RadiationNmsBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LugolsIodinePotion implements Listener, Predicate<ItemStack> {
    static final Logger logger = Logger.getLogger(LugolsIodinePotion.class.getName());

    private static final byte TRUE = 1;

    private final Plugin plugin;
    private final LugolsIodineEffect effect;
    private final Config config;

    private NamespacedKey potionIdKey;
    private NamespacedKey radiationIdsKey;
    private NamespacedKey durationSecondsKey;
    private NamespacedKey legacyPotionKey;
    private NamespacedKey legacyDurationKey;

    private NamespacedKey recipeKey;

    public LugolsIodinePotion(Plugin plugin, LugolsIodineEffect effect, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void enable(RadiationNmsBridge nmsBridge) {
        Objects.requireNonNull(nmsBridge, "nmsBridge");

        this.potionIdKey = new NamespacedKey(this.plugin, "lugols_iodine_id");
        this.radiationIdsKey = new NamespacedKey(this.plugin, "radiation_ids");
        this.durationSecondsKey = new NamespacedKey(this.plugin, "duration_seconds");
        this.legacyPotionKey = new NamespacedKey(this.plugin, "lugols_iodine");
        this.legacyDurationKey = new NamespacedKey(this.plugin, "duration");

        Config.Recipe recipeConfig = this.config.recipe();
        if (recipeConfig.enabled()) {
            this.recipeKey = NamespacedKey.randomKey();
            nmsBridge.registerLugolsIodinePotion(this.recipeKey, recipeConfig);
        }
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable(RadiationNmsBridge nmsBridge) {
        Objects.requireNonNull(nmsBridge, "nmsBridge");

        HandlerList.unregisterAll(this);
        if (this.config.recipe().enabled()) {
            nmsBridge.unregisterLugolsIodinePotion(this.recipeKey);
        }
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

        String id = this.config.id();

        PersistentDataContainer container = itemStack.getItemMeta().getPersistentDataContainer();
        if (id.equals(Config.DEFAULT_ID) && container.has(this.legacyPotionKey, PersistentDataType.BYTE)) { // legacy
            Byte value = container.get(this.legacyPotionKey, PersistentDataType.BYTE);
            return value != null && value == TRUE;
        }

        if (container.has(this.potionIdKey, PersistentDataType.STRING)) {
            return id.equals(container.get(this.potionIdKey, PersistentDataType.STRING));
        }

        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!this.test(item)) {
            return;
        }

        Player player = event.getPlayer();
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();

        List<String> radiationIds = null;
        if (container.has(this.radiationIdsKey, PersistentDataType.BYTE_ARRAY)) {
            byte[] bytes = container.get(this.radiationIdsKey, PersistentDataType.BYTE_ARRAY);

            if (bytes != null && bytes.length != 0) {
                try {
                    radiationIds = this.readRadiationIds(bytes);
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Could not read radiation IDs from bytes on '" + player.getName() +  "'.", e);
                    return;
                }
            }
        }

        Duration duration = Duration.ZERO;
        if (container.has(this.durationSecondsKey, PersistentDataType.INTEGER)) {
            duration = Duration.ofSeconds(container.getOrDefault(this.durationSecondsKey, PersistentDataType.INTEGER, 0));
        } else if (container.has(this.legacyDurationKey, PersistentDataType.INTEGER)) {
            duration = Duration.ofMinutes(container.getOrDefault(this.legacyDurationKey, PersistentDataType.INTEGER, 0)); // legacy
        }

        if (duration.isNegative() || duration.isZero()) {
            return;
        }

        try {
            this.effect.appendEffect(player, new LugolsIodineEffect.Effect(this.getId(), duration, radiationIds));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not set lugol's iodine potion effect on '" + player.getName() + "'.");
            return;
        }

        this.broadcastConsumption(player, duration);
    }

    private void broadcastConsumption(Player player, Duration duration) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(duration, "duration");

        String name = this.config.name();
        logger.info(player.getName() + " has consumed " + name + " with a duration of " + duration.getSeconds() + " seconds");

        this.config.drinkMessage().ifPresent(rawMessage -> {
            String message = ChatColor.RED + MessageFormat.format(rawMessage, player.getDisplayName() + ChatColor.RESET, name);
            for (Player online : this.plugin.getServer().getOnlinePlayers()) {
                if (online.canSee(player)) {
                    online.sendMessage(message);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        Config.Recipe recipeConfig = this.config.recipe();
        if (!recipeConfig.enabled()) {
            return;
        }

        BrewerInventory inventory = event.getContents();
        BrewingStandWindow window = BrewingStandWindow.fromArray(inventory.getContents());

        if (!window.ingredient.getType().equals(recipeConfig.ingredient())) {
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
            if (potionMeta.getBasePotionData().getType().equals(recipeConfig.basePotion())) {
                try {
                    result.setItemMeta(this.convert(potionMeta));
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Could not transform potion to lugol's iodine.", e);
                    continue;
                }

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

    public ItemStack createItemStack(int amount) throws IOException {
        ItemStack itemStack = new ItemStack(Material.POTION, amount);
        PotionMeta potionMeta = (PotionMeta) Objects.requireNonNull(itemStack.getItemMeta());

        PotionData potionData = new PotionData(this.config.recipe().basePotion());
        potionMeta.setBasePotionData(potionData);

        itemStack.setItemMeta(this.convert(potionMeta));
        return itemStack;
    }

    public PotionMeta convert(PotionMeta potionMeta) throws IOException {
        Objects.requireNonNull(potionMeta, "potionMeta");

        Duration duration = this.getDuration();
        String formattedDuration = formatDuration(duration);

        this.config.color().ifPresent(potionMeta::setColor);
        potionMeta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
        potionMeta.setDisplayName(ChatColor.AQUA + this.config.name());
        potionMeta.setLore(Collections.singletonList(ChatColor.BLUE + MessageFormat.format(this.config.description(), formattedDuration)));

        PersistentDataContainer container = potionMeta.getPersistentDataContainer();
        container.set(this.potionIdKey, PersistentDataType.STRING, this.config.id());

        List<String> radiationIds = this.config.radiationIds();
        if (radiationIds != null && !radiationIds.isEmpty()) {
            byte[] bytes;
            try {
                bytes = this.writeRadiationIds(radiationIds);
            } catch (IOException e) {
                throw new IOException("Could not write radiation IDs to bytes.", e);
            }

            if (bytes.length != 0) {
                container.set(this.radiationIdsKey, PersistentDataType.BYTE_ARRAY, bytes);
            }
        }

        container.set(this.durationSecondsKey, PersistentDataType.INTEGER, (int) duration.getSeconds());
        return potionMeta;
    }

    public String getId() {
        return this.config.id();
    }

    private List<String> readRadiationIds(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        List<String> radiationIds = new ArrayList<>();

        try (Closer closer = Closer.create()) {
            ByteArrayInputStream byteArrayInputStream = closer.register(new ByteArrayInputStream(bytes));
            DataInputStream dataInputStream = closer.register(new DataInputStream(byteArrayInputStream));

            int count = dataInputStream.readInt();
            for (int i = 0; i < count; i++) {
                radiationIds.add(dataInputStream.readUTF());
            }
        }

        return radiationIds;
    }

    private byte[] writeRadiationIds(List<String> radiationIds) throws IOException {
        Objects.requireNonNull(radiationIds, "radiationIds");

        byte[] bytes;
        try (Closer closer = Closer.create()) {
            ByteArrayOutputStream byteArrayOutputStream = closer.register(new ByteArrayOutputStream());
            DataOutputStream dataOutputStream = closer.register(new DataOutputStream(byteArrayOutputStream));

            dataOutputStream.writeInt(radiationIds.size());
            for (String radiationId : radiationIds) {
                dataOutputStream.writeUTF(radiationId);
            }

            bytes = byteArrayOutputStream.toByteArray();
        }

        return bytes;
    }

    static String formatDuration(Duration duration) {
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
        public static final String DEFAULT_ID = "default";

        private final String id;
        private final Recipe recipe;
        private final String name;
        private final Color color;
        private final String description;
        private final List<String> radiationIds;
        private final Duration duration;
        private final String drinkMessage;

        public Config(String id, Recipe recipe, String name, Color color, String description, List<String> radiationIds, Duration duration, String drinkMessage) {
            this.id = Objects.requireNonNull(id, "id");
            this.recipe = Objects.requireNonNull(recipe, "recipe");
            this.name = Objects.requireNonNull(name, "name");
            this.color = color;
            this.description = Objects.requireNonNull(description, "description");
            this.radiationIds = Objects.requireNonNull(radiationIds, "radiationIds");
            this.duration = Objects.requireNonNull(duration, "duration");
            this.drinkMessage = drinkMessage;
        }

        public Config(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            String id = section.getName();
            this.id = id.isEmpty() ? DEFAULT_ID : id;

            this.recipe = new Recipe(section.getConfigurationSection("recipe"));
            this.name = Objects.requireNonNull(section.getString("name", "Lugol's Iodine"));

            String colorHex = section.getString("color", null);
            try {
                this.color = colorHex == null || colorHex.isEmpty() ? null : Color.fromRGB(
                        Integer.parseInt(colorHex.substring(1, 3), 16),
                        Integer.parseInt(colorHex.substring(3, 5), 16),
                        Integer.parseInt(colorHex.substring(5, 7), 16)
                );
            } catch (NumberFormatException | StringIndexOutOfBoundsException exception) {
                throw new InvalidConfigurationException("Invalid potion color.", exception);
            }

            this.description = Objects.requireNonNull(section.getString("description", "Radiation resistance ({0})"));

            List<String> radiationIds = section.getStringList("radiation-ids");
            this.radiationIds = radiationIds.isEmpty() ? null : radiationIds;

            this.duration = Objects.requireNonNull(Duration.ofSeconds(section.getInt("duration", 600)));

            String drinkMessage = RadiationPlugin.colorize(section.getString("drink-message"));
            this.drinkMessage = drinkMessage != null && !drinkMessage.isEmpty() ? drinkMessage : null;

            if (this.duration.isZero() || this.duration.isNegative()) {
                throw new InvalidConfigurationException("Given potion duration must be positive.");
            }
        }

        public String id() {
            return this.id;
        }

        public Recipe recipe() {
            return this.recipe;
        }

        public String name() {
            return this.name;
        }

        public Optional<Color> color() {
            return Optional.ofNullable(this.color);
        }

        public String description() {
            return this.description;
        }

        public List<String> radiationIds() {
            return this.radiationIds;
        }

        public Duration duration() {
            return this.duration;
        }

        public Optional<String> drinkMessage() {
            return Optional.ofNullable(this.drinkMessage);
        }

        public static class Recipe {
            public static Material DEFAULT_INGREDIENT = Material.GHAST_TEAR;
            public static PotionType DEFAULT_BASE_POTION = PotionType.THICK;

            private final boolean enabled;
            private final PotionType basePotion;
            private final Material ingredient;

            public Recipe(boolean enabled, Material ingredient, PotionType basePotion) {
                this.enabled = enabled;
                this.ingredient = Objects.requireNonNull(ingredient, "ingredient");
                this.basePotion = Objects.requireNonNull(basePotion, "basePotion");
            }

            public Recipe(ConfigurationSection section) throws InvalidConfigurationException {
                if (section == null) {
                    section = new MemoryConfiguration();
                }

                this.enabled = section.getBoolean("enabled", true);

                String ingredientInput = Objects.requireNonNull(section.getString("ingredient", DEFAULT_INGREDIENT.getKey().getKey()));
                this.ingredient = Material.matchMaterial(ingredientInput);
                if (this.ingredient == null) {
                    throw new InvalidConfigurationException("Invalid recipe ingredient name: " + ingredientInput);
                }

                String basePotionInput = Objects.requireNonNull(section.getString("base-potion", DEFAULT_BASE_POTION.name())).toUpperCase(Locale.ROOT);
                try {
                    this.basePotion = PotionType.valueOf(basePotionInput);
                } catch (IllegalArgumentException exception) {
                    throw new InvalidConfigurationException("Invalid recipe base potion name: " + basePotionInput, exception);
                }
            }

            public boolean enabled() {
                return this.enabled;
            }

            public PotionType basePotion() {
                return this.basePotion;
            }

            public Material ingredient() {
                return this.ingredient;
            }
        }
    }
}
