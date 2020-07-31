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

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import pl.craftserve.metrics.pluginmetricslite.MetricSubmitEvent;
import pl.craftserve.metrics.pluginmetricslite.MetricsLite;

import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MetricsHandler implements Listener  {
    static final Logger logger = Logger.getLogger(MetricsHandler.class.getName());

    private final Plugin plugin;
    private final Server server;

    private final LugolsIodineEffect effect;
    private final LugolsIodinePotion potion;

    public MetricsHandler(Plugin plugin, Server server, LugolsIodineEffect effect, LugolsIodinePotion potion) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");

        this.effect = Objects.requireNonNull(effect, "effect");
        this.potion = Objects.requireNonNull(potion, "potion");
    }

    public void start() {
        this.server.getPluginManager().registerEvents(this, this.plugin);

        try {
            MetricsLite.start(this.plugin);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Could not start metrics.", throwable);
        }
    }

    public void stop() {
        try {
            MetricsLite.stopIfRunning(this.plugin);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Could not stop metrics.", throwable);
        }

        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMetricSubmit(MetricSubmitEvent event) {
        Map<NamespacedKey, Object> data = event.getData();
        data.put(this.key("lugols_iodine_duration"), this.potion.getDuration().getSeconds());
        data.put(this.key("lugols_iodione_affected_count"), (int) this.server.getOnlinePlayers().stream()
                .filter(player -> this.effect.getEffect(player) != null)
                .count());
    }

    private NamespacedKey key(String key) {
        Objects.requireNonNull(key, "key");
        return new NamespacedKey(this.plugin, key);
    }
}
