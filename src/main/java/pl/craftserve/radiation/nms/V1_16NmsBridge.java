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

import net.minecraft.server.v1_16_R1.BiomeManager;
import net.minecraft.server.v1_16_R1.Container;
import net.minecraft.server.v1_16_R1.DimensionManager;
import net.minecraft.server.v1_16_R1.EntityLiving;
import net.minecraft.server.v1_16_R1.EntityPlayer;
import net.minecraft.server.v1_16_R1.EntityTypes;
import net.minecraft.server.v1_16_R1.EnumGamemode;
import net.minecraft.server.v1_16_R1.FoodMetaData;
import net.minecraft.server.v1_16_R1.PacketPlayOutAbilities;
import net.minecraft.server.v1_16_R1.PacketPlayOutCamera;
import net.minecraft.server.v1_16_R1.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_16_R1.PacketPlayOutGameStateChange;
import net.minecraft.server.v1_16_R1.PacketPlayOutHeldItemSlot;
import net.minecraft.server.v1_16_R1.PacketPlayOutPosition;
import net.minecraft.server.v1_16_R1.PacketPlayOutRespawn;
import net.minecraft.server.v1_16_R1.PacketPlayOutSpawnEntityLiving;
import net.minecraft.server.v1_16_R1.PacketPlayOutUpdateHealth;
import net.minecraft.server.v1_16_R1.PacketPlayOutWindowItems;
import net.minecraft.server.v1_16_R1.PlayerConnection;
import net.minecraft.server.v1_16_R1.PlayerInteractManager;
import net.minecraft.server.v1_16_R1.ResourceKey;
import net.minecraft.server.v1_16_R1.World;
import net.minecraft.server.v1_16_R1.WorldServer;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_16_R1.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class V1_16NmsBridge extends V1_14ToV1_15NmsBridge {
    private final Set<UUID> mobCameras = new HashSet<>(128);

    public V1_16NmsBridge(String version) {
        super(version);
    }

    @Override
    public void playMobCameraEffect(Player player, EntityType entityType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(entityType, "entityType");

        if (!this.mobCameras.add(player.getUniqueId())) {
            return;
        }

        EntityPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        PlayerInteractManager interactManager = nmsPlayer.playerInteractManager;

        PlayerConnection connection = nmsPlayer.playerConnection;
        if (connection == null) {
            return;
        }

        EntityTypes<?> nmsType = EntityTypes.a(entityType.getKey().getKey()).orElse(null);
        if (nmsType == null) {
            return;
        }

        EntityLiving nmsEntity = (EntityLiving) nmsType.a(nmsPlayer.world);
        if (nmsEntity == null) {
            return;
        }

        Location location = player.getLocation();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        float yaw = location.getYaw();
        float pitch = location.getPitch();

        nmsEntity.setPosition(x, y, z);
        nmsEntity.setPositionRotation(x, y, z, yaw, pitch);

        PacketPlayOutGameStateChange.a gameModeState = PacketPlayOutGameStateChange.d;
        int spectatorId = EnumGamemode.SPECTATOR.getId();

        connection.sendPacket(new PacketPlayOutGameStateChange(gameModeState, spectatorId));
        connection.sendPacket(new PacketPlayOutSpawnEntityLiving(nmsEntity));
        connection.sendPacket(new PacketPlayOutCamera(nmsEntity));
        connection.sendPacket(new PacketPlayOutEntityDestroy(nmsEntity.getId()));
        connection.sendPacket(new PacketPlayOutUpdateHealth(0F, 0, 0F)); // kill

        World world = nmsEntity.world;
        WorldServer worldServer = nmsPlayer.getWorldServer();

        ResourceKey<DimensionManager> typeKey = world.getTypeKey();
        ResourceKey<World> dimensionKey = world.getDimensionKey();
        long seed = BiomeManager.a(worldServer.getSeed());
        EnumGamemode gameMode = interactManager.getGameMode();
        EnumGamemode prevGameMode = interactManager.c();
        boolean debug = world.isDebugWorld();
        boolean flat = worldServer.isFlatWorld();

        connection.sendPacket(new PacketPlayOutRespawn(typeKey, dimensionKey, seed, gameMode, prevGameMode, debug, flat, true));
        connection.sendPacket(new PacketPlayOutAbilities(nmsPlayer.abilities));

        Container container = nmsPlayer.activeContainer;
        connection.sendPacket(new PacketPlayOutWindowItems(container.windowId, container.b()));
        connection.sendPacket(new PacketPlayOutHeldItemSlot(nmsPlayer.inventory.itemInHandIndex));

        FoodMetaData foodData = nmsPlayer.getFoodData();
        connection.sendPacket(new PacketPlayOutUpdateHealth(nmsPlayer.getHealth(), foodData.foodLevel, foodData.saturationLevel));

        connection.sendPacket(new PacketPlayOutPosition(x, y, z, yaw, pitch, Collections.emptySet(), -1));
    }

    @Override
    public void pauseMobCameraEffect(Player player) {
        Objects.requireNonNull(player, "player");

        if (this.mobCameras.remove(player.getUniqueId())) {
            EntityPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            PlayerConnection connection = nmsPlayer.playerConnection;

            if (connection != null) {
                connection.sendPacket(new PacketPlayOutCamera(nmsPlayer));
            }
        }
    }
}
