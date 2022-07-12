package pl.craftserve.radiation.nms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import pl.craftserve.radiation.LugolsIodinePotion;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class V1_19_R1NmsBridge implements RadiationNmsBridge {
    static final Logger logger = Logger.getLogger(V1_19_R1NmsBridge.class.getName());

    private final Class<?> itemClass;
    private final Class<?> iRegistryClass;
    private final Class<?> mobEffectClass;
    private final Class<?> potionRegistryClass;
    private final Class<?> potionBrewerClass;

    private final Field isRegistryMaterialsFrozen;

    private final Method getItem;
    private final Method newMinecraftKey;
    private final Method getPotion;
    private final Method minHeightMethod;

    private final Object potionRegistry;

    private final Map<UUID, Integer> minWorldHeightMap = new HashMap<>();

    public V1_19_R1NmsBridge(String version) {
        Objects.requireNonNull(version, "version");

        try {
            this.itemClass = Class.forName("net.minecraft.world.item.Item"); // Item -> Item
            this.iRegistryClass = Class.forName("net.minecraft.core.IRegistry"); // IRegistry -> Registry
            this.mobEffectClass = Class.forName("net.minecraft.world.effect.MobEffect"); // MobEffect -> MobEffectInstance
            this.potionRegistryClass = Class.forName("net.minecraft.world.item.alchemy.PotionRegistry"); // PotionRegistry -> Potion
            this.potionBrewerClass = Class.forName("net.minecraft.world.item.alchemy.PotionBrewer"); // PotionBrewer -> PotionBrewing

            Class<?> registryMaterialsClass = Class.forName("net.minecraft.core.RegistryMaterials"); // RegistryMaterials -> MappedRegistry
            this.isRegistryMaterialsFrozen = registryMaterialsClass.getDeclaredField("ca"); // ca -> frozen


            Class<?> craftMagicNumbers = Class.forName("org.bukkit.craftbukkit." + version + ".util.CraftMagicNumbers");
            this.getItem = craftMagicNumbers.getMethod("getItem", Material.class);
            this.minHeightMethod = Class.forName("org.bukkit.generator.WorldInfo").getMethod("getMinHeight");

            Class<?> minecraftKey = Class.forName("net.minecraft.resources.MinecraftKey"); // MinecraftKey -> ResourceLocation
            this.newMinecraftKey = minecraftKey.getMethod("a", String.class); // a -> tryParse
            this.potionRegistry = this.iRegistryClass.getDeclaredField("Z").get(null); // Z -> POTION_REGISTRY

            this.getPotion = this.potionRegistry.getClass().getMethod("a", minecraftKey); // a -> get
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize 1.19 bridge", e);
        }
    }

    @Override
    public void registerLugolsIodinePotion(NamespacedKey potionKey, LugolsIodinePotion.Config.Recipe config) {
        Objects.requireNonNull(potionKey, "potionKey");
        Objects.requireNonNull(config, "config");

        try {
            String basePotionName = config.basePotion().name().toLowerCase(Locale.ROOT);
            Object basePotion = this.getPotion.invoke(this.potionRegistry, this.newMinecraftKey.invoke(null, basePotionName));
            Objects.requireNonNull(basePotion, "basePotion not found");

            Object ingredient = this.getItem.invoke(null, config.ingredient());
            Objects.requireNonNull(ingredient, "ingredient not found");

            Object mobEffectArray = Array.newInstance(this.mobEffectClass, 0);
            Object newPotion = this.potionRegistryClass.getConstructor(mobEffectArray.getClass()).newInstance(mobEffectArray);

            Method registerMethod = this.iRegistryClass.getDeclaredMethod("a", this.iRegistryClass, String.class, Object.class); // a -> register
            this.isRegistryMaterialsFrozen.setAccessible(true);
            this.isRegistryMaterialsFrozen.set(this.potionRegistry, false);
            Object potion = registerMethod.invoke(null, this.potionRegistry, potionKey.getKey(), newPotion);

            Method registerBrewingRecipe = this.potionBrewerClass.getDeclaredMethod("a", this.potionRegistryClass, this.itemClass, this.potionRegistryClass); // a -> addMix
            registerBrewingRecipe.setAccessible(true);
            registerBrewingRecipe.invoke(null, basePotion, ingredient, potion);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not handle reflective operation.", e);
        }
    }

    @Override
    public void unregisterLugolsIodinePotion(NamespacedKey potionKey) {
        // todo unregister potion and brewing recipe
    }

    @Override
    public int getMinWorldHeight(World bukkitWorld) {
        Objects.requireNonNull(bukkitWorld, "bukkitWorld");

        return this.minWorldHeightMap.computeIfAbsent(bukkitWorld.getUID(), worldId -> {
            try {
                return (int) this.minHeightMethod.invoke(bukkitWorld);
            } catch (IllegalAccessException | InvocationTargetException e) {
                logger.log(Level.SEVERE, "Could not handle min world height on world '" + bukkitWorld.getName() + "' ('" + worldId + "').", e);
                return 0;
            }
        });
    }
}
