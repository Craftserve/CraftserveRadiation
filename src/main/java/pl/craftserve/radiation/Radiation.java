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

import com.google.common.base.Preconditions;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import pl.craftserve.radiation.nms.RadiationNmsBridge;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class Radiation implements Listener {
    static final Logger logger = Logger.getLogger(Radiation.class.getName());

    private final Set<UUID> affectedPlayers = new HashSet<>(128);

    private final Plugin plugin;
    private final Matcher matcher;
    private final Config config;

    private BossBar bossBar;
    private Task task;

    public Radiation(Plugin plugin, Matcher matcher, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void enable() {
        Server server = this.plugin.getServer();
        this.bossBar = this.config.bar().create(server, ChatColor.DARK_RED);

        this.task = new Task();
        this.task.runTaskTimer(this.plugin, 20L, 20L);

        server.getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);

        if (this.task != null) {
            this.task.cancel();
        }

        if (this.bossBar != null) {
            this.bossBar.removeAll();
        }

        this.affectedPlayers.clear();
    }

    public boolean addAffectedPlayer(Player player, boolean addBossBar) {
        Objects.requireNonNull(player, "player");

        return this.affectedPlayers.add(player.getUniqueId());
    }

    private void addBossBar(Player player) {
        Objects.requireNonNull(player, "player");
        this.bossBar.addPlayer(player);
    }

    private void broadcastEscape(Player player) {
        Objects.requireNonNull(player, "player");

        String id = this.getId();
        logger.info(player.getName() + " has entered \"" + id + "\" radiation zone at " + player.getLocation());

        this.config.enterMessage().ifPresent(rawMessage -> {
            String message = ChatColor.RED + MessageFormat.format(rawMessage, player.getDisplayName() + ChatColor.RESET, id);
            for (Player online : this.plugin.getServer().getOnlinePlayers()) {
                if (online.canSee(player)) {
                    online.sendMessage(message);
                }
            }
        });
    }

    public Set<UUID> getAffectedPlayers() {
        return Collections.unmodifiableSet(this.affectedPlayers);
    }

    public String getId() {
        return this.config.id();
    }

    public Matcher getMatcher() {
        return this.matcher;
    }

    public boolean removeAffectedPlayer(Player player, boolean removeBossBar) {
        Objects.requireNonNull(player, "player");

        boolean ok = this.affectedPlayers.remove(player.getUniqueId());
        if (removeBossBar) {
            this.removeBossBar(player);
        }

        return ok;
    }

    public void removeBossBar(Player player) {
        Objects.requireNonNull(player, "player");
        this.bossBar.removePlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.removeAffectedPlayer(event.getPlayer(), true);
    }

    class Task extends BukkitRunnable {
        @Override
        public void run() {
            Server server = plugin.getServer();
            Iterable<PotionEffect> effects = config.effects();

            server.getOnlinePlayers().forEach(player -> {
                if (matcher.test(player)) {
                    RadiationEvent event = new RadiationEvent(player, Radiation.this);
                    server.getPluginManager().callEvent(event);

                    boolean showBossBar = event.shouldShowWarning();
                    boolean cancel = event.isCancelled();

                    boolean contains = bossBar.getPlayers().contains(player);

                    if (!cancel) {
                        for (PotionEffect effect : effects) {
                            player.addPotionEffect(effect, true);
                        }

                        addAffectedPlayer(player, showBossBar);
                    }

                    if (showBossBar) {
                        addBossBar(player);

                        if (!contains) {
                            broadcastEscape(player);
                        }
                    } else {
                        removeBossBar(player);
                    }
                } else {
                    removeAffectedPlayer(player, true);
                }
            });
        }
    }

    /**
     * Something that tests if the player can be affected by the radiation.
     */
    public interface Matcher extends Predicate<Player> {
    }

    /**
     * Base interface for all matchers using WorldGuard to test the radiation.
     */
    public interface WorldGuardMatcher extends Matcher {
        @Override
        default boolean test(Player player) {
            RegionContainer regionContainer = this.getRegionContainer();
            return regionContainer != null && this.test(player, regionContainer);
        }

        default RegionContainer getRegionContainer() {
            WorldGuardPlatform platform = WorldGuard.getInstance().getPlatform();
            return platform != null ? platform.getRegionContainer() : null;
        }

        boolean test(Player player, RegionContainer regionContainer);
    }

    /**
     * Tests if the given flags matches the radiation IDs.
     */
    public static class FlagMatcher implements WorldGuardMatcher {
        private final RadiationNmsBridge nmsBridge;
        private final Flag<Boolean> isRadioactiveFlag;
        private final Flag<String> radiationTypeFlag;
        private final Set<String> acceptedRadiationTypes;

        public FlagMatcher(RadiationNmsBridge nmsBridge, Flag<Boolean> isRadioactiveFlag, Flag<String> radiationTypeFlag, Set<String> acceptedRadiationTypes) {
            this.nmsBridge = Objects.requireNonNull(nmsBridge, "nmsBridge");
            this.isRadioactiveFlag = Objects.requireNonNull(isRadioactiveFlag, "isRadioactiveFlag");
            this.radiationTypeFlag = Objects.requireNonNull(radiationTypeFlag, "radiationTypeFlag");
            this.acceptedRadiationTypes = Objects.requireNonNull(acceptedRadiationTypes, "acceptedRadiationTypes");
        }

        @Override
        public boolean test(Player player, RegionContainer regionContainer) {
            org.bukkit.Location bukkitLocation = player.getLocation();
            World world = player.getWorld();
            int minY = this.nmsBridge.getMinWorldHeight(world);
            int maxY = world.getMaxHeight();

            Location location = BukkitAdapter.adapt(bukkitLocation);
            location = location.setY(Math.max(minY, Math.min(maxY, location.getY())));
            ApplicableRegionSet regions = regionContainer.createQuery().getApplicableRegions(location);
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

            Boolean radioactive = regions.queryValue(localPlayer, this.isRadioactiveFlag);
            if (radioactive == null || !radioactive) {
                return false;
            }

            String radiationId = regions.queryValue(localPlayer, this.radiationTypeFlag);
            if (radiationId == null || radiationId.isEmpty()) {
                radiationId = Config.DEFAULT_ID;
            }

            Permission permission = new Permission("craftserveradiation.immune." + radiationId, PermissionDefault.FALSE);
            if (player.hasPermission(permission)) {
                // Players with this permission are immune to radiation. They won't match this matcher.
                return false;
            }

            return this.acceptedRadiationTypes.contains(radiationId);
        }
    }

    //
    // Config
    //

    public static class Config {
        public static final String DEFAULT_ID = "default";

        private final String id;
        private final BarConfig bar;
        private final Iterable<PotionEffect> effects;
        private final String enterMessage;

        public Config(String id, BarConfig bar, Iterable<PotionEffect> effects, String enterMessage) {
            this.id = Objects.requireNonNull(id, "id");
            this.bar = Objects.requireNonNull(bar, "bar");
            this.effects = Objects.requireNonNull(effects, "effects");
            this.enterMessage = enterMessage;

            Preconditions.checkArgument(!id.isEmpty(), "id cannot be empty");
        }

        public Config(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            String id = section.getName();
            this.id = id.isEmpty() ? DEFAULT_ID : id;

            try {
                this.bar = new BarConfig(section.getConfigurationSection("bar"));
            } catch (InvalidConfigurationException e) {
                throw new InvalidConfigurationException("Could not parse bar section in radiation.", e);
            }

            List<PotionEffect> effects = new ArrayList<>();
            ConfigurationSection effectsSection = section.getConfigurationSection("effects");
            if (effectsSection != null) {
                for (String key : effectsSection.getKeys(false)) {
                    if (!effectsSection.isConfigurationSection(key)) {
                        continue;
                    }

                    ConfigurationSection effectSection = effectsSection.getConfigurationSection(key);
                    if (effectSection == null) {
                        continue;
                    }

                    PotionEffectType type = PotionEffectType.getByName(effectSection.getName());
                    if (type == null) {
                        throw new InvalidConfigurationException("Unknown effect type: " + key + ".");
                    }

                    effectSection.set("effect", type.getId());
                    effectSection.set("duration", 20 * 5); // duration, in ticks
                    effectSection.set("amplifier", effectSection.getInt("level", 1) - 1);

                    try {
                        effects.add(new PotionEffect(effectSection.getValues(false)));
                    } catch (NoSuchElementException e) {
                        throw new InvalidConfigurationException("Could not parse effect " + key + ".", e);
                    }
                }
            }

            this.effects = Collections.unmodifiableCollection(effects);

            String enterMessage = RadiationPlugin.colorize(section.getString("enter-message"));
            this.enterMessage = enterMessage != null && !enterMessage.isEmpty() ? enterMessage : null;
        }

        public String id() {
            return this.id;
        }

        public BarConfig bar() {
            return this.bar;
        }

        public Iterable<PotionEffect> effects() {
            return this.effects;
        }

        public Optional<String> enterMessage() {
            return Optional.ofNullable(this.enterMessage);
        }
    }
}
