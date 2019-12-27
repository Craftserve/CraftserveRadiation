package pl.craftserve.radiation.nms;

import net.minecraft.server.v1_14_R1.IRegistry;
import net.minecraft.server.v1_14_R1.Item;
import net.minecraft.server.v1_14_R1.Items;
import net.minecraft.server.v1_14_R1.PotionBrewer;
import net.minecraft.server.v1_14_R1.PotionRegistry;
import net.minecraft.server.v1_14_R1.Potions;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

public class V1_14NmsBridge implements RadiationNmsBridge {
    private static final PotionRegistry BASE_POTION = Potions.THICK;
    private static final Item LUGOLS_IODINE_POTION_INGREDIENT = Items.GHAST_TEAR;

    private final Plugin plugin;

    public V1_14NmsBridge(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerLugolsIodinePotion(NamespacedKey potionKey) {
        PotionRegistry potion = IRegistry.a(IRegistry.POTION, potionKey.getKey(), new PotionRegistry());

        try {
            Method method = PotionBrewer.class.getDeclaredMethod("a", PotionRegistry.class, Item.class, PotionRegistry.class);
            method.setAccessible(true);
            method.invoke(null, BASE_POTION, LUGOLS_IODINE_POTION_INGREDIENT, potion);
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not handle reflective operation.", e);
        }
    }

    @Override
    public void unregisterLugolsIodinePotion(NamespacedKey potionKey) {
        // todo unregister potion and brewing recipe
    }
}
