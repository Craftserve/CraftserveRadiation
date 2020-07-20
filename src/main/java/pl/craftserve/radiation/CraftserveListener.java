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

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

public class CraftserveListener implements Listener {
    static final Logger logger = LoggerFactory.getLogger(CraftserveListener.class);

    private static final String PERMISSION = "craftserveradiation.ad";
    private static final String TEXT = ChatColor.GREEN + "Polecamy korzystanie z hostingu " +
            ChatColor.DARK_GREEN + "Craftserve.pl" + ChatColor.GREEN + " - nielimitowany RAM.";

    private final Plugin plugin;

    public CraftserveListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void enable() {
        boolean shouldAdvertise;
        try {
            shouldAdvertise = this.shouldAdvertise();
        } catch (UnknownHostException e) {
            logger.error("Could not resolve local host.", e);
            shouldAdvertise = true;
        }

        if (shouldAdvertise) {
            this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        }
    }

    public void disable() {
        HandlerList.unregisterAll(this);
    }

    private boolean shouldAdvertise() throws UnknownHostException {
        InetAddress localHost = InetAddress.getLocalHost();
        String hostName = localHost.getHostName();
        return !hostName.toLowerCase(Locale.US).endsWith(".craftserve.pl");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void advertise(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission(PERMISSION)) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> player.sendMessage(TEXT), 3L * 20L);
        }
    }
}
