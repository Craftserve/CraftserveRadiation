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
