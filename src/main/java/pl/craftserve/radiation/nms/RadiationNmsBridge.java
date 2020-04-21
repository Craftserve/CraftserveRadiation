package pl.craftserve.radiation.nms;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;

import org.apache.commons.lang.StringUtils;
import pl.craftserve.radiation.LugolsIodinePotion;

public interface RadiationNmsBridge {
    void registerLugolsIodinePotion(NamespacedKey potionKey, final LugolsIodinePotion.Config config);

    void unregisterLugolsIodinePotion(NamespacedKey potionKey);

    static String getServerVersion(Server server) {
        Package serverClassPackage = server.getClass().getPackage();
        String[] packages = StringUtils.split(serverClassPackage.getName(), ".");

        return packages[packages.length - 1];
    }
}
