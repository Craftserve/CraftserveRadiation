package pl.craftserve.radiation.nms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import pl.craftserve.radiation.LugolsIodinePotion;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.logging.Level;

public class V1_14ToV1_15NmsBridge implements RadiationNmsBridge {
    private final Plugin plugin;

    private final Class<?> itemClass;
    private final Class<?> iRegistryClass;
    private final Class<?> mobEffectClass;
    private final Class<?> potionRegistryClass;
    private final Class<?> potionBrewerClass;

    private final Object basePotion;
    private final Object lugolsIodinePotionIngredient;
    private final LugolsIodinePotion.Config config;

    public V1_14ToV1_15NmsBridge(final Plugin plugin, final String version, final LugolsIodinePotion.Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(version, "version");
        this.config = Objects.requireNonNull(config, "config");

        try {
            this.itemClass = this.getNmsClass("Item", version);
            this.iRegistryClass = this.getNmsClass("IRegistry", version);
            this.mobEffectClass = this.getNmsClass("MobEffect", version);
            this.potionRegistryClass = this.getNmsClass("PotionRegistry", version);
            this.potionBrewerClass = this.getNmsClass("PotionBrewer", version);

            Class<?> craftMagicNumbers = this.getObcClass("util.CraftMagicNumbers", version);
            Method getItem = craftMagicNumbers.getMethod("getItem", Material.class);

            Class<?> iRegistry = this.getNmsClass("IRegistry", version);
            Class<?> minecraftKey = this.getNmsClass("MinecraftKey", version);
            Method newMinecraftKey = minecraftKey.getMethod("a", String.class);
            Object potion = iRegistry.getDeclaredField("POTION").get(null);
            Method getPotion = potion.getClass().getMethod("get", minecraftKey);

            this.basePotion = getPotion.invoke(potion, newMinecraftKey.invoke(null, config.getRecipe().getBasePotion().name().toLowerCase()));
            this.lugolsIodinePotionIngredient = getItem.invoke(null, config.getRecipe().getIngredient());
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
    public void registerLugolsIodinePotion(final NamespacedKey potionKey) {
        try {
            Object registryType = this.iRegistryClass.getDeclaredField("POTION").get(null);
            Object mobEffectArray = Array.newInstance(this.mobEffectClass, 0);
            Object newPotion = this.potionRegistryClass.getConstructor(mobEffectArray.getClass()).newInstance(mobEffectArray);

            Method registerMethod = this.iRegistryClass.getDeclaredMethod("a", this.iRegistryClass, String.class, Object.class);
            Object potion = registerMethod.invoke(null, registryType, potionKey.getKey(), newPotion);

            Method registerBrewingRecipe = this.potionBrewerClass.getDeclaredMethod("a", this.potionRegistryClass, this.itemClass, this.potionRegistryClass);
            registerBrewingRecipe.setAccessible(true);
            registerBrewingRecipe.invoke(null, this.basePotion, this.lugolsIodinePotionIngredient, potion);
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not handle reflective operation.", e);
        }
    }

    @Override
    public void unregisterLugolsIodinePotion(final NamespacedKey potionKey) {
        // todo unregister potion and brewing recipe
    }
}
