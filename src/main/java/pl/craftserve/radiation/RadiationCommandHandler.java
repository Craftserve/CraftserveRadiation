/*
 * Copyright 2020 Aleksander Jagiełło <themolkapl@gmail.com>
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

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.craftserve.radiation.nms.RadiationNmsBridge;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class RadiationCommandHandler implements CommandExecutor, TabCompleter {
    static final Logger logger = Logger.getLogger(RadiationCommandHandler.class.getName());

    private static final String REGION_ID = "safe_from_radiation";
    private static final String GLOBAL_REGION_ID = "__global__";

    private final RadiationNmsBridge nmsBridge;
    private final Flag<Boolean> flag;
    private final Radiation.WorldGuardMatcher worldGuardMatcher = (player, regionContainer) -> {
        throw new UnsupportedOperationException();
    };
    private final Function<String, LugolsIodinePotion> potionFinder;
    private final Supplier<Spliterator<LugolsIodinePotion>> potionLister;

    public RadiationCommandHandler(RadiationNmsBridge nmsBridge, Flag<Boolean> flag,
                                   Function<String, LugolsIodinePotion> potionFinder, Supplier<Spliterator<LugolsIodinePotion>> potionLister) {
        this.nmsBridge = Objects.requireNonNull(nmsBridge, "nmsBridge");
        this.flag = Objects.requireNonNull(flag, "flag");
        this.potionFinder = Objects.requireNonNull(potionFinder, "potionFinder");
        this.potionLister = Objects.requireNonNull(potionLister, "potionLister");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players may execute this command.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length > 0) {
            switch (args[0]) {
                case "potion":
                    return this.onPotion(player, label, args);
                case "safe":
                    return this.onSafe(player, label, args);
            }
        }

        sender.sendMessage(ChatColor.RED + command.getUsage());
        return true;
    }

    private boolean onPotion(Player sender, String label, String[] args) {
        String usage = ChatColor.RED + "/" + label + " potion <identifier>";
        if (args.length == 1) {
            String accessiblePotionIds = StreamSupport.stream(this.potionLister.get(), false)
                    .map(LugolsIodinePotion::getId)
                    .sorted()
                    .collect(Collectors.joining(", "));

            sender.sendMessage(ChatColor.RED + "Provide lugol's iodine potion identifier in the first argument.");
            sender.sendMessage(usage);
            sender.sendMessage(ChatColor.RED + "Example: /" + label + " potion default");
            sender.sendMessage(ChatColor.RED + "Accessible potions: " + accessiblePotionIds);
            return true;
        }

        String id = args[1];
        LugolsIodinePotion potion = this.potionFinder.apply(id);
        if (potion == null) {
            sender.sendMessage(ChatColor.RED + "Unknown lugol's iodine potion identifier: " + id);
            sender.sendMessage(usage);
            return true;
        }

        ItemStack itemStack;
        try {
            itemStack = potion.createItemStack(1);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create potion item for '" + sender.getName() + "'.", e);
            sender.sendMessage(ChatColor.RED + "An internal error has occurred while creating potion item. See console.");
            return true;
        }

        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());

        if (sender.getInventory().addItem(itemStack).isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "You have received " + itemStack.getAmount() + " " + itemMeta.getDisplayName());
        } else {
            sender.sendMessage(ChatColor.RED + "Your inventory is full!");
        }
        return true;
    }

    private boolean onSafe(Player sender, String label, String[] args) {
        String usage = ChatColor.RED + "/" + label + " safe <radius>";
        if (args.length == 1) {
            sender.sendMessage(ChatColor.RED + "Provide safe-from-radiation zone radius in the first argument. Radius will be relative to your current position.");
            sender.sendMessage(usage);
            return true;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Number was expected, but " + args[1] + " was provided.");
            sender.sendMessage(ChatColor.RED + usage);
            return true;
        }

        if (radius <= 0) {
            sender.sendMessage(ChatColor.RED + "Radius must be positive.");
            sender.sendMessage(ChatColor.RED + usage);
            return true;
        }

        RegionContainer container = this.worldGuardMatcher.getRegionContainer();
        if (container == null) {
            sender.sendMessage(ChatColor.RED + "Sorry, region container is not currently accessible.");
            return true;
        }

        if (this.define(sender, container, REGION_ID, radius)) {
            BlockVector2 origin = BukkitAdapter.asBlockVector(sender.getLocation()).toBlockVector2();
            sender.sendMessage(ChatColor.GREEN + "A new safe-from-radiation zone has been created in radius " +
                    radius + " at the origin at " + origin + " in world " + sender.getWorld().getName() + ".");
        }
        return true;
    }

    private boolean define(Player player, RegionContainer container, String regionId, int radius) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(regionId, "regionId");

        org.bukkit.World bukkitWorld = player.getWorld();
        World world = BukkitAdapter.adapt(bukkitWorld);
        RegionManager regionManager = container.get(world);
        if (regionManager == null) {
            player.sendMessage(ChatColor.RED + "Sorry, region manager for world " + world.getName() + " is not currently accessible.");
            return false;
        }

        BlockVector3 origin = BukkitAdapter.asBlockVector(player.getLocation());
        this.define(regionManager, this.createCuboid(bukkitWorld, regionId, origin, radius));
        this.flagGlobal(regionManager, true);
        return true;
    }

    private ProtectedCuboidRegion createCuboid(org.bukkit.World bukkitWorld, String regionId, BlockVector3 origin, int radius) {
        Objects.requireNonNull(bukkitWorld, "bukkitWorld");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(origin, "origin");

        int minY = this.nmsBridge.getMinWorldHeight(bukkitWorld);
        int maxY = bukkitWorld.getMaxHeight();

        BlockVector3 min = origin.subtract(radius, 0, radius).withY(minY);
        BlockVector3 max = origin.add(radius, 0, radius).withY(maxY);
        return new ProtectedCuboidRegion(regionId, min, max);
    }

    private void define(RegionManager regionManager, ProtectedRegion region) {
        Objects.requireNonNull(regionManager, "regionManager");
        Objects.requireNonNull(region, "region");

        String id = region.getId();
        if (regionManager.hasRegion(id)) {
            ProtectedRegion existing = Objects.requireNonNull(regionManager.getRegion(id));
            region.copyFrom(existing);
        }

        this.flag(region, false);
        region.setFlag(Flags.PASSTHROUGH, StateFlag.State.ALLOW);
        regionManager.addRegion(region);
    }

    private void flagGlobal(RegionManager regionManager, boolean value) {
        Objects.requireNonNull(regionManager, "regionMark");

        if (regionManager.hasRegion(GLOBAL_REGION_ID)) {
            ProtectedRegion existing = Objects.requireNonNull(regionManager.getRegion(GLOBAL_REGION_ID));
            this.flag(existing, value);
        } else {
            ProtectedRegion region = new GlobalProtectedRegion(GLOBAL_REGION_ID);
            this.flag(region, value);
            regionManager.addRegion(region);
        }
    }

    private void flag(ProtectedRegion region, boolean value) {
        Objects.requireNonNull(region, "region");

        if (!Objects.equals(region.getFlag(this.flag), value)) {
            region.setFlag(this.flag, value);
            region.setDirty(true);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String subCommandInput = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return Stream.of("potion", "safe")
                    .filter(subCommand -> subCommand.startsWith(subCommandInput))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    public void register(PluginCommand command) {
        Objects.requireNonNull(command, "command");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }
}
