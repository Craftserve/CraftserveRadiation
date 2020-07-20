package pl.craftserve.radiation.nms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.craftserve.radiation.LugolsIodinePotion;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Objects;

public class V1_14ToV1_15NmsBridge implements RadiationNmsBridge {
    static final Logger logger = LoggerFactory.getLogger(V1_14ToV1_15NmsBridge.class);

    private final Class<?> itemClass;
    private final Class<?> iRegistryClass;
    private final Class<?> mobEffectClass;
    private final Class<?> potionRegistryClass;
    private final Class<?> potionBrewerClass;

    private final Method getItem;
    private final Method newMinecraftKey;
    private final Method getPotion;

    private final Object potionRegistry;

    public V1_14ToV1_15NmsBridge(final String version) {
        Objects.requireNonNull(version, "version");

        try {
            this.itemClass = this.getNmsClass("Item", version);
            this.iRegistryClass = this.getNmsClass("IRegistry", version);
            this.mobEffectClass = this.getNmsClass("MobEffect", version);
            this.potionRegistryClass = this.getNmsClass("PotionRegistry", version);
            this.potionBrewerClass = this.getNmsClass("PotionBrewer", version);

            Class<?> craftMagicNumbers = this.getObcClass("util.CraftMagicNumbers", version);
            this.getItem = craftMagicNumbers.getMethod("getItem", Material.class);

            Class<?> iRegistry = this.getNmsClass("IRegistry", version);
            Class<?> minecraftKey = this.getNmsClass("MinecraftKey", version);
            this.newMinecraftKey = minecraftKey.getMethod("a", String.class);
            this.potionRegistry = iRegistry.getDeclaredField("POTION").get(null);
            this.getPotion = potionRegistry.getClass().getMethod("get", minecraftKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize 1.14-1.15 bridge", e);
        }
    }

    private Class<?> getNmsClass(String clazz, String version) throws ClassNotFoundException {
        return Class.forName(MessageFormat.format("net.minecraft.server.{1}.{0}", clazz, version));
    }

    private Class<?> getObcClass(String clazz, String version) throws ClassNotFoundException {
        return Class.forName(MessageFormat.format("org.bukkit.craftbukkit.{1}.{0}", clazz, version));
    }

    @Override
    public void registerLugolsIodinePotion(final NamespacedKey potionKey, final LugolsIodinePotion.Config config) {
        try {
            LugolsIodinePotion.Config.Recipe recipeConfig = config.recipe();
            Object basePotion = this.getPotion.invoke(this.potionRegistry, this.newMinecraftKey.invoke(null, recipeConfig.basePotion().name().toLowerCase()));
            Object ingredient = this.getItem.invoke(null, recipeConfig.ingredient());

            Object registryType = this.iRegistryClass.getDeclaredField("POTION").get(null);
            Object mobEffectArray = Array.newInstance(this.mobEffectClass, 0);
            Object newPotion = this.potionRegistryClass.getConstructor(mobEffectArray.getClass()).newInstance(mobEffectArray);

            Method registerMethod = this.iRegistryClass.getDeclaredMethod("a", this.iRegistryClass, String.class, Object.class);
            Object potion = registerMethod.invoke(null, registryType, potionKey.getKey(), newPotion);

            Method registerBrewingRecipe = this.potionBrewerClass.getDeclaredMethod("a", this.potionRegistryClass, this.itemClass, this.potionRegistryClass);
            registerBrewingRecipe.setAccessible(true);
            registerBrewingRecipe.invoke(null, basePotion, ingredient, potion);
        } catch (Exception e) {
            logger.error("Could not handle reflective operation.", e);
        }
    }

    @Override
    public void unregisterLugolsIodinePotion(final NamespacedKey potionKey) {
        // todo unregister potion and brewing recipe
    }
}
