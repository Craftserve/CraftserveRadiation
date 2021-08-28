package pl.craftserve.radiation.nms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import pl.craftserve.radiation.LugolsIodinePotion;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class V1_17_R1NmsBridge implements RadiationNmsBridge {
    static final Logger logger = Logger.getLogger(V1_14ToV1_15NmsBridge.class.getName());

    private final Class<?> itemClass;
    private final Class<?> iRegistryClass;
    private final Class<?> mobEffectClass;
    private final Class<?> potionRegistryClass;
    private final Class<?> potionBrewerClass;

    private final Method getItem;
    private final Method newMinecraftKey;
    private final Method getPotion;
    private final Method minHeightMethod;

    private final Object potionRegistry;

    public V1_17_R1NmsBridge(String version) {
        Objects.requireNonNull(version, "version");

        try {
            this.itemClass = Class.forName("net.minecraft.world.item.Item");
            this.iRegistryClass = Class.forName("net.minecraft.core.IRegistry");
            this.mobEffectClass = Class.forName("net.minecraft.world.effect.MobEffect");
            this.potionRegistryClass = Class.forName("net.minecraft.world.item.alchemy.PotionRegistry");
            this.potionBrewerClass = Class.forName("net.minecraft.world.item.alchemy.PotionBrewer");

            Class<?> craftMagicNumbers = Class.forName("org.bukkit.craftbukkit." + version + ".util.CraftMagicNumbers");
            this.getItem = craftMagicNumbers.getMethod("getItem", Material.class);
            this.minHeightMethod = World.class.getMethod("getMinHeight");

            Class<?> minecraftKey = Class.forName("net.minecraft.resources.MinecraftKey");
            this.newMinecraftKey = minecraftKey.getMethod("a", String.class);
            this.potionRegistry = this.iRegistryClass.getDeclaredField("aa").get(null);
            this.getPotion = this.potionRegistry.getClass().getMethod("get", minecraftKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize 1.17.1 bridge", e);
        }
    }

    @Override
    public void registerLugolsIodinePotion(NamespacedKey potionKey, LugolsIodinePotion.Config.Recipe config) {
        Objects.requireNonNull(potionKey, "potionKey");
        Objects.requireNonNull(config, "config");

        try {
            String basePotionName = config.basePotion().name().toLowerCase().toLowerCase(Locale.ROOT);
            Object basePotion = this.getPotion.invoke(this.potionRegistry, this.newMinecraftKey.invoke(null, basePotionName));
            Object ingredient = this.getItem.invoke(null, config.ingredient());

            Object mobEffectArray = Array.newInstance(this.mobEffectClass, 0);
            Object newPotion = this.potionRegistryClass.getConstructor(mobEffectArray.getClass()).newInstance(mobEffectArray);

            Method registerMethod = this.iRegistryClass.getDeclaredMethod("a", this.iRegistryClass, String.class, Object.class);
            Object potion = registerMethod.invoke(null, this.potionRegistry, potionKey.getKey(), newPotion);

            Method registerBrewingRecipe = this.potionBrewerClass.getDeclaredMethod("a", this.potionRegistryClass, this.itemClass, this.potionRegistryClass);
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
        try {
            return (int) this.minHeightMethod.invoke(bukkitWorld);
        } catch (IllegalAccessException | InvocationTargetException e) {
            logger.log(Level.SEVERE, "Could not handle min world height on world '" + bukkitWorld.getName() + "'.", e);
            return 0;
        }
    }
}
