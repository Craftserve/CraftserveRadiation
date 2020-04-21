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
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RadiationCommandHandler implements CommandExecutor, TabCompleter {
    private static final String REGION_ID = "safe_from_radiation";
    private static final String GLOBAL_REGION_ID = "__global__";

    private final Flag<Boolean> flag;
    private final Radiation.WorldGuardMatcher worldGuardMatcher = (player, regionContainer) -> {
        throw new UnsupportedOperationException();
    };
    private final LugolsIodinePotion potion;

    public RadiationCommandHandler(Flag<Boolean> flag, LugolsIodinePotion potion) {
        this.flag = Objects.requireNonNull(flag, "flag");
        this.potion = potion;
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
                    return onPotion(player);
                case "safe":
                    return onSafe(player, label, args);
            }
        }

        sender.sendMessage(ChatColor.RED + command.getUsage());
        return true;
    }

    private boolean onPotion(Player sender) {
        ItemStack itemStack = new ItemStack(Material.POTION);
        PotionMeta itemMeta = Objects.requireNonNull((PotionMeta) itemStack.getItemMeta());
        itemMeta.setBasePotionData(new PotionData(potion.getConfig().getRecipe().getBasePotion()));
        itemStack.setItemMeta(potion.convert(itemMeta));
        sender.getInventory().addItem(itemStack);
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

        World world = BukkitAdapter.adapt(player.getWorld());
        RegionManager regionManager = container.get(world);
        if (regionManager == null) {
            player.sendMessage(ChatColor.RED + "Sorry, region manager for world " + world.getName() + " is not currently accessible.");
            return false;
        }

        BlockVector3 origin = BukkitAdapter.asBlockVector(player.getLocation());
        this.define(regionManager, this.createCuboid(regionId, origin, radius));
        this.flagGlobal(regionManager, true);
        return true;
    }

    private ProtectedCuboidRegion createCuboid(String regionId, BlockVector3 origin, int radius) {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(origin, "origin");

        BlockVector3 min = origin.subtract(radius, 0, radius).withY(0);
        BlockVector3 max = origin.add(radius, 0, radius).withY(255);
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
        return Collections.emptyList();
    }

    public void register(PluginCommand command) {
        Objects.requireNonNull(command, "command");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }
}
