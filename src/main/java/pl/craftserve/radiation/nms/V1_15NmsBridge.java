package pl.craftserve.radiation.nms;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.logging.Level;

public class V1_15NmsBridge implements RadiationNmsBridge {
    private static final Class<?> POTIONS_CLASS;
    private static final Class<?> ITEMS_CLASS;
    private static final Class<?> ITEM_CLASS;
    private static final Class<?> IREGISTRY_CLASS;
    private static final Class<?> MOB_EFFECT_CLASS;
    private static final Class<?> POTION_REGISTRY_CLASS;
    private static final Class<?> POTION_BREWER_CLASS;

    private static final Object BASE_POTION;
    private static final Object LUGOLS_IODINE_POTION_INGREDIENT;

    private final Plugin plugin;

    static {
        try {
            POTIONS_CLASS = Class.forName("net.minecraft.server.v1_15_R1.Potions");
            ITEMS_CLASS = Class.forName("net.minecraft.server.v1_15_R1.Items");
            ITEM_CLASS = Class.forName("net.minecraft.server.v1_15_R1.Item");
            IREGISTRY_CLASS = Class.forName("net.minecraft.server.v1_15_R1.IRegistry");
            MOB_EFFECT_CLASS = Class.forName("net.minecraft.server.v1_15_R1.MobEffect");
            POTION_REGISTRY_CLASS = Class.forName("net.minecraft.server.v1_15_R1.PotionRegistry");
            POTION_BREWER_CLASS = Class.forName("net.minecraft.server.v1_15_R1.PotionBrewer");

            BASE_POTION = POTIONS_CLASS.getDeclaredField("THICK").get(null);
            LUGOLS_IODINE_POTION_INGREDIENT = ITEMS_CLASS.getDeclaredField("GHAST_TEAR").get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise 1.15 bridge", e);
        }
    }

    public V1_15NmsBridge(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerLugolsIodinePotion(final NamespacedKey potionKey) {
        try {
            Object registryType = IREGISTRY_CLASS.getDeclaredField("POTION").get(null);
            final Object mobEffectArray = Array.newInstance(MOB_EFFECT_CLASS, 0);
            Object newPotion = POTION_REGISTRY_CLASS.getConstructor(mobEffectArray.getClass()).newInstance(mobEffectArray);

            Method registerMethod = IREGISTRY_CLASS.getDeclaredMethod("a", IREGISTRY_CLASS, String.class, Object.class);
            Object potion = registerMethod.invoke(null, registryType, potionKey.getKey(), newPotion);

            Method registerBrewingRecipe = POTION_BREWER_CLASS.getDeclaredMethod("a", POTION_REGISTRY_CLASS, ITEM_CLASS, POTION_REGISTRY_CLASS);
            registerBrewingRecipe.setAccessible(true);
            registerBrewingRecipe.invoke(null, BASE_POTION, LUGOLS_IODINE_POTION_INGREDIENT, potion);
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not handle reflective operation.", e);
        }
    }

    @Override
    public void unregisterLugolsIodinePotion(final NamespacedKey potionKey) {
        // todo unregister potion and brewing recipe
    }
}
