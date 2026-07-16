package com.serverai.npc;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Equipment;
import net.citizensnpcs.npc.pathfinder.PathType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AINpc {

    private final NPCManager manager;
    private final NPC npc;
    private final net.citizensnpcs.api.ai.Navigator navigator;
    private final UUID id;
    private String currentAction = "idle";

    public AINpc(NPCManager manager, NPC npc) {
        this.manager = manager;
        this.npc = npc;
        this.navigator = npc.getNavigator();
        this.id = npc.getUniqueId();
        navigator.setLocalPathfinder(PathType.PLAYER_REALISTIC);
    }

    public @NotNull NPC getNpc() {
        return npc;
    }

    public @NotNull UUID getId() {
        return id;
    }

    public @NotNull String getName() {
        return npc.getName();
    }

    public @NotNull Location getLocation() {
        return npc.getEntity().getLocation();
    }

    public boolean isSpawned() {
        return npc.isSpawned();
    }

    public void chat(@NotNull String message) {
        npc.getEntity().sendMessage("§e[AI] §r" + message);
        Bukkit.broadcastMessage("§e[" + npc.getName() + "] §r" + message);
        currentAction = "chat";
    }

    public void chatTo(@NotNull Player player, @NotNull String message) {
        player.sendMessage("§e[" + npc.getName() + "] §r" + message);
        currentAction = "chat";
    }

    public @NotNull CompletableFuture<Boolean> moveTo(@NotNull Location target, double speed) {
        currentAction = "move";
        Location from = getLocation();

        return CompletableFuture.supplyAsync(() -> {
            navigator.setTarget(target, true, speed > 0 ? (float) speed : 1.0f);
            return waitForArrival(target, 60_000);
        });
    }

    public @NotNull CompletableFuture<Boolean> moveTo(@NotNull Location target) {
        return moveTo(target, 1.0);
    }

    public void stopMoving() {
        navigator.cancelNavigation();
        currentAction = "idle";
    }

    public void lookAt(@NotNull Location target) {
        npc.faceLocation(target);
    }

    public void lookAt(@NotNull Player player) {
        npc.faceEntity(player);
    }

    public void setEquipment(@NotNull ItemStack hand) {
        Equipment equip = npc.getTrait(Equipment.class);
        if (equip != null) {
            equip.set(Equipment.EquipmentSlot.HAND, hand);
        }
    }

    public void setArmor(@NotNull ItemStack helmet, @NotNull ItemStack chest, @NotNull ItemStack legs, @NotNull ItemStack boots) {
        Equipment equip = npc.getTrait(Equipment.class);
        if (equip != null) {
            equip.set(Equipment.EquipmentSlot.HELMET, helmet);
            equip.set(Equipment.EquipmentSlot.CHESTPLATE, chest);
            equip.set(Equipment.EquipmentSlot.LEGGINGS, legs);
            equip.set(Equipment.EquipmentSlot.BOOTS, boots);
        }
    }

    public void setSkin(@NotNull String skinName) {
        npc.setEntityType(EntityType.PLAYER);
        // Skin setting requires skin trait or packet manipulation
        // Simplified: just set name for now
    }

    public @NotNull String getCurrentAction() {
        return currentAction;
    }

    public void setAction(@NotNull String action) {
        this.currentAction = action;
    }

    public void despawn() {
        npc.despawn();
    }

    public void respawn() {
        npc.spawn(getLocation());
    }

    public void destroy() {
        stopMoving();
        npc.destroy();
        manager.removeNpc(id);
    }

    private boolean waitForArrival(@NotNull Location target, long timeoutMs) {
        long start = System.currentTimeMillis();
        Location npcLoc;

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!npc.isSpawned()) return false;

            npcLoc = getLocation();
            if (npcLoc.distance(target) < 2.0) {
                currentAction = "idle";
                return true;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        currentAction = "idle";
        return false;
    }
}