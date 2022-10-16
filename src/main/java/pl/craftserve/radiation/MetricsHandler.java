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

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import pl.craftserve.metrics.pluginmetricslite.MetricSubmitEvent;
import pl.craftserve.metrics.pluginmetricslite.MetricsLite;
import pl.craftserve.radiation.nms.RadiationNmsBridge;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MetricsHandler implements Listener  {
    static final Logger logger = Logger.getLogger(MetricsHandler.class.getName());

    private static final int B_STATS_PLUGIN_ID = 13487;

    private final RadiationPlugin plugin;
    private final Server server;
    private final Class<? extends RadiationNmsBridge> nmsBridgeClass;

    public MetricsHandler(RadiationPlugin plugin, Server server, Class<? extends RadiationNmsBridge> nmsBridgeClass) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.nmsBridgeClass = Objects.requireNonNull(nmsBridgeClass, "nmsBridgeClass");
    }

    public void start() {
        this.server.getPluginManager().registerEvents(this, this.plugin);

        try {
            MetricsLite.start(this.plugin);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Could not start metrics.", throwable);
        }

        Metrics metrics = new Metrics(this.plugin, B_STATS_PLUGIN_ID);
        this.setupBStatsCharts(metrics);
    }

    public void stop() {
        try {
            MetricsLite.stopIfRunning(this.plugin);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Could not stop metrics.", throwable);
        }

        HandlerList.unregisterAll(this);
    }

    private void setupBStatsCharts(Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics");

        metrics.addCustomChart(new SimplePie("lugols_iodine_potion_count", () -> {
            return Integer.toString(this.plugin.getPotionHandlers().size());
        }));

        metrics.addCustomChart(new SimplePie("lugols_iodine_duration", () -> {
            Duration average = Duration.ZERO;

            Map<String, LugolsIodinePotion> potionHandlers = this.plugin.getPotionHandlers();
            if (potionHandlers.isEmpty()) {
                return LugolsIodinePotion.formatDuration(average);
            }

            for (LugolsIodinePotion potion : potionHandlers.values()) {
                average = average.plus(potion.getDuration());
            }

            Duration duration = average.dividedBy(potionHandlers.size());
            return LugolsIodinePotion.formatDuration(duration);
        }));

        metrics.addCustomChart(new SingleLineChart("lugols_iodione_affected_count", () -> {
            return (int) this.server.getOnlinePlayers().stream()
                    .filter(this::hasEffect)
                    .count();
        }));

        metrics.addCustomChart(new SimplePie("active_radiations_count", () -> {
            return Integer.toString(this.plugin.getActiveRadiations().size());
        }));

        metrics.addCustomChart(new SingleLineChart("active_radiations_affected_count", () -> {
            return this.plugin.getActiveRadiations().values().stream()
                    .mapToInt(radiation -> radiation.getAffectedPlayers().size())
                    .sum();
        }));

        metrics.addCustomChart(new SimplePie("nms_bridge_class", this.nmsBridgeClass::getName));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMetricSubmit(MetricSubmitEvent event) {
        Map<String, Radiation> activeRadiations = this.plugin.getActiveRadiations();

        Map<NamespacedKey, Object> data = event.getData();
        data.put(this.key("lugols_iodione_affected_count"), (int) this.server.getOnlinePlayers().stream()
                .filter(this::hasEffect)
                .count());
        data.put(this.key("active_radiations_count"), activeRadiations.size());
        data.put(this.key("active_radiations_affected_count"), activeRadiations.values().stream()
                .mapToInt(radiation -> radiation.getAffectedPlayers().size())
                .sum());
        data.put(this.key("nms_bridge_class"), this.nmsBridgeClass.getName());
    }

    private boolean hasEffect(Player player) {
        Objects.requireNonNull(player, "player");
        LugolsIodineEffect effectHandler = this.plugin.getEffectHandler();

        List<LugolsIodineEffect.Effect> effects;
        try {
            effects = effectHandler.getEffects(player);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not get lugol's iodine effect on '" + player.getName() + "'.", e);
            return false;
        }

        return !effects.isEmpty();
    }

    private NamespacedKey key(String key) {
        Objects.requireNonNull(key, "key");
        return new NamespacedKey(this.plugin, key);
    }
}
