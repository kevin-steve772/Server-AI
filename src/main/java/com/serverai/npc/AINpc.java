package com.serverai.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AINpc {

    private final NpcManager manager;
    private final LivingEntity entity;
    private final UUID id;
    private String currentAction = "idle";

    public AINpc(NpcManager manager, LivingEntity entity) {
        this.manager = manager;
        this.entity = entity;
        this.id = entity.getUniqueId();
        entity.setCustomNameVisible(true);
    }

    public @NotNull LivingEntity getEntity() {
        return entity;
    }

    public @NotNull UUID getId() {
        return id;
    }

    public @NotNull String getName() {
        String customName = entity.getCustomName();
        return customName == null || customName.isBlank()
                ? entity.getType().name()
                : customName;
    }

    public @NotNull Location getLocation() {
        return entity.getLocation();
    }

    public boolean isSpawned() {
        return entity.isValid() && !entity.isDead();
    }

    public void chat(@NotNull String message) {
        entity.getWorld().sendMessage("§e[" + getName() + "] §r" + message);
        Bukkit.broadcastMessage("§e[" + getName() + "] §r" + message);
        currentAction = "chat";
    }

    public void chatTo(@NotNull Player player, @NotNull String message) {
        player.sendMessage("§e[" + getName() + "] §r" + message);
        currentAction = "chat";
    }

    public @NotNull CompletableFuture<Boolean> moveTo(@NotNull Location target, double speed) {
        currentAction = "move";
        return manager.moveEntity(this, target, speed);
    }

    public @NotNull CompletableFuture<Boolean> moveTo(@NotNull Location target) {
        return moveTo(target, 1.0);
    }

    public void stopMoving() {
        currentAction = "idle";
        manager.stopMovement();
    }

    public void lookAt(@NotNull Location target) {
        Location current = entity.getLocation();
        Location updated = current.clone();
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        if (dx == 0.0 && dz == 0.0) {
            updated.setPitch(0.0f);
        } else {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            updated.setYaw(yaw);
            updated.setPitch(0.0f);
        }
        entity.teleport(updated);
    }

    public void lookAt(@NotNull Player player) {
        lookAt(player.getLocation());
    }

    public void setEquipment(@NotNull ItemStack hand) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(hand);
        }
    }

    public void setArmor(@NotNull ItemStack helmet, @NotNull ItemStack chest, @NotNull ItemStack legs, @NotNull ItemStack boots) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            if (helmet != null) equipment.setHelmet(helmet);
            if (chest != null) equipment.setChestplate(chest);
            if (legs != null) equipment.setLeggings(legs);
            if (boots != null) equipment.setBoots(boots);
        }
    }

    public void setSkin(@NotNull String skinName) {
        entity.setCustomName(getName() + " (" + skinName + ")");
    }

    public @NotNull String getCurrentAction() {
        return currentAction;
    }

    public void setAction(@NotNull String action) {
        this.currentAction = action;
    }

    public void despawn() {
        entity.remove();
    }

    public void respawn() {
        if (!entity.isValid()) {
            entity.teleport(getLocation());
        }
    }

    public void destroy() {
        stopMoving();
        entity.remove();
        manager.removeNpc(id);
    }
}