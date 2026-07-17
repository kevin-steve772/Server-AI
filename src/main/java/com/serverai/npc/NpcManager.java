package com.serverai.npc;

import com.serverai.Main;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NpcManager {

    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;
    private static final double ARRIVAL_DISTANCE_SQUARED = 1.0;

    private final Main plugin;
    private final Map<UUID, AINpc> npcs = new LinkedHashMap<>();

    private volatile Component npcName;
    private volatile String plainNpcName;
    private volatile String npcSkin;
    private volatile AINpc activeNpc;
    private volatile ScheduledTask movementTask;
    private volatile Location movementTarget;
    private volatile double defaultSpeed;
    private volatile double arrivalDistance;
    private volatile double maxMoveDistance;

    public NpcManager(Main plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        Component updatedName = plugin.getMessages().get("npc.name", "&b[AI]助手");
        npcName = updatedName;
        plainNpcName = plugin.getMessages().plain(updatedName);
        npcSkin = plugin.getConfig().getString("npc.skin", "");
        defaultSpeed = clamp(plugin.getConfig().getDouble("npc.default-speed", 1.0), MIN_SPEED, MAX_SPEED);
        arrivalDistance = clamp(plugin.getConfig().getDouble("npc.arrival-distance", 1.5), 0.5, 10.0);
        maxMoveDistance = clamp(plugin.getConfig().getDouble("npc.max-move-distance", 512.0), 8.0, 512.0);

        AINpc current = activeNpc;
        if (current != null && current.isSpawned()) {
            current.getEntity().setCustomName(plainNpcName);
            current.getEntity().setCustomNameVisible(true);
        }
    }

    public boolean spawn(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        despawn();

        Entity spawned = world.spawnEntity(location, EntityType.VILLAGER);
        if (!(spawned instanceof LivingEntity livingEntity)) {
            return false;
        }
        livingEntity.setCustomName(plainNpcName);
        livingEntity.setCustomNameVisible(true);
        livingEntity.setInvulnerable(true);
        livingEntity.setPersistent(false);

        AINpc npc = new AINpc(this, livingEntity);
        npcs.put(npc.getId(), npc);
        activeNpc = npc;
        plugin.getLogger().info("NPC spawned: " + plainNpcName);
        return true;
    }

    public void despawn() {
        cancelMovementMonitor();
        if (activeNpc != null) {
            AINpc current = activeNpc;
            activeNpc = null;
            current.despawn();
        }
        npcs.clear();
        plugin.getLogger().info("NPC despawned");
    }

    public CompletableFuture<MoveResult> moveTo(Location destination, double speed) {
        Location target = destination.clone();
        if (!isValidLocation(target)) {
            return CompletableFuture.completedFuture(MoveResult.INVALID_LOCATION);
        }
        if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
            return CompletableFuture.completedFuture(MoveResult.INVALID_SPEED);
        }
        AINpc npc = activeNpc;
        if (npc == null || !npc.isSpawned()) {
            return CompletableFuture.completedFuture(MoveResult.NOT_SPAWNED);
        }

        if (target.getWorld() == null || !target.getWorld().equals(npc.getLocation().getWorld())) {
            return CompletableFuture.completedFuture(MoveResult.DIFFERENT_WORLD);
        }
        if (npc.getLocation().distanceSquared(target) > maxMoveDistance * maxMoveDistance) {
            return CompletableFuture.completedFuture(MoveResult.TOO_FAR);
        }
        if (npc.getLocation().distanceSquared(target) <= arrivalDistance * arrivalDistance) {
            return CompletableFuture.completedFuture(MoveResult.ALREADY_THERE);
        }

        movementTarget = target;
        movementTask = npc.getEntity().getScheduler().runAtFixedRate(plugin, task -> {
            if (activeNpc != npc || !npc.isSpawned()) {
                clearMovementTask(task);
                return;
            }
            Location current = npc.getLocation();
            if (current.getWorld() == null || !current.getWorld().equals(target.getWorld())) {
                clearMovementTask(task);
                return;
            }
            if (current.distanceSquared(target) <= arrivalDistance * arrivalDistance) {
                clearMovementTask(task);
                return;
            }
            Vector direction = target.toVector().subtract(current.toVector()).normalize();
            double step = Math.max(0.25, speed * 0.5);
            Location next = current.clone().add(direction.multiply(step));
            npc.getEntity().teleport(next);
        }, () -> clearMovementTask(null), 1L, 1L);
        return CompletableFuture.completedFuture(MoveResult.STARTED);
    }

    public CompletableFuture<Boolean> stopMovement() {
        cancelMovementMonitor();
        return CompletableFuture.completedFuture(true);
    }

    public CompletableFuture<NpcState> getState() {
        AINpc npc = activeNpc;
        if (npc == null || !npc.isSpawned()) {
            return CompletableFuture.completedFuture(null);
        }
        Location location = npc.getLocation();
        Location target = movementTarget;
        return CompletableFuture.completedFuture(new NpcState(
                location.getWorld() == null ? null : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                target != null,
                target == null ? null : target.getWorld() == null ? null : target.getWorld().getName(),
                target == null ? null : target.getX(),
                target == null ? null : target.getY(),
                target == null ? null : target.getZ()));
    }

    public void say(String message) {
        String name = plainNpcName;
        plugin.runGlobal(() -> plugin.getServer().broadcast(plugin.getMessages().formatComponents(
                "npc.chat-format", "<%npc_name%> %message%",
                Map.of(
                        "message", plugin.getMessages().markdown(message),
                        "npc_name", Component.text(name)))));
    }

    public void equip(ItemStack handItem) {
        AINpc npc = activeNpc;
        if (npc != null && npc.isSpawned()) {
            npc.setEquipment(handItem);
        }
    }

    public void setArmor(ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
        AINpc npc = activeNpc;
        if (npc != null && npc.isSpawned()) {
            npc.setArmor(helmet, chest, legs, boots);
        }
    }

    public void lookAt(Location location) {
        AINpc npc = activeNpc;
        if (npc != null && npc.isSpawned()) {
            npc.lookAt(location);
        }
    }

    public void teleport(Location location) {
        AINpc npc = activeNpc;
        if (npc != null && npc.isSpawned()) {
            npc.getEntity().teleport(location);
        }
    }

    public void setSkin(String skinName) {
        this.npcSkin = skinName;
        AINpc npc = activeNpc;
        if (npc != null && npc.isSpawned()) {
            npc.setSkin(skinName);
        }
    }

    public boolean isSpawned() {
        AINpc npc = activeNpc;
        return npc != null && npc.isSpawned();
    }

    public boolean isMoving() {
        return movementTarget != null;
    }

    public double getDefaultSpeed() {
        return defaultSpeed;
    }

    public double getMaxMoveDistance() {
        return maxMoveDistance;
    }

    public String getNpcName() {
        return plainNpcName;
    }

    public LivingEntity getEntity() {
        AINpc npc = activeNpc;
        return npc != null && npc.isSpawned() ? npc.getEntity() : null;
    }

    public AINpc createNpc(String name, Location location, EntityType type) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        EntityType entityType = type == EntityType.PLAYER ? EntityType.VILLAGER : type;
        Entity spawned = world.spawnEntity(location, entityType);
        if (!(spawned instanceof LivingEntity livingEntity)) {
            return null;
        }
        livingEntity.setCustomName(name);
        livingEntity.setCustomNameVisible(true);
        livingEntity.setInvulnerable(true);
        livingEntity.setPersistent(false);
        AINpc npc = new AINpc(this, livingEntity);
        npcs.put(npc.getId(), npc);
        activeNpc = npc;
        return npc;
    }

    public AINpc getNpcByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AINpc npc : npcs.values()) {
            if (name.equalsIgnoreCase(npc.getName())) {
                return npc;
            }
        }
        return null;
    }

    public Collection<AINpc> getAllNpcs() {
        return new ArrayList<>(npcs.values());
    }

    public void removeNpc(UUID id) {
        npcs.remove(id);
        if (activeNpc != null && activeNpc.getId().equals(id)) {
            activeNpc = null;
        }
    }

    public CompletableFuture<Boolean> moveEntity(AINpc npc, Location target, double speed) {
        if (npc == null || !npc.isSpawned()) {
            return CompletableFuture.completedFuture(false);
        }
        if (target.getWorld() == null || !target.getWorld().equals(npc.getLocation().getWorld())) {
            return CompletableFuture.completedFuture(false);
        }
        if (npc.getLocation().distanceSquared(target) > maxMoveDistance * maxMoveDistance) {
            return CompletableFuture.completedFuture(false);
        }
        if (npc.getLocation().distanceSquared(target) <= ARRIVAL_DISTANCE_SQUARED) {
            return CompletableFuture.completedFuture(true);
        }
        movementTarget = target;
        movementTask = npc.getEntity().getScheduler().runAtFixedRate(plugin, task -> {
            if (activeNpc != npc || !npc.isSpawned()) {
                clearMovementTask(task);
                return;
            }
            Location current = npc.getLocation();
            if (current.distanceSquared(target) <= ARRIVAL_DISTANCE_SQUARED) {
                clearMovementTask(task);
                return;
            }
            Vector direction = target.toVector().subtract(current.toVector()).normalize();
            double step = Math.max(0.25, speed * 0.5);
            npc.getEntity().teleport(current.clone().add(direction.multiply(step)));
        }, () -> clearMovementTask(null), 1L, 1L);
        return CompletableFuture.completedFuture(true);
    }

    public void keepVisible() {
        LivingEntity entity = getEntity();
        if (entity != null) {
            entity.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void cancelMovementMonitor() {
        ScheduledTask task = movementTask;
        movementTask = null;
        movementTarget = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void clearMovementTask(ScheduledTask task) {
        if (movementTask == task || task == null) {
            movementTask = null;
            movementTarget = null;
        }
        if (task != null) {
            task.cancel();
        }
    }

    private static boolean isValidLocation(Location location) {
        return location.getWorld() != null
                && Double.isFinite(location.getX())
                && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ());
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record NpcState(
            String world,
            double x,
            double y,
            double z,
            boolean moving,
            String targetWorld,
            Double targetX,
            Double targetY,
            Double targetZ) {
    }

    public enum MoveResult {
        STARTED,
        ALREADY_THERE,
        NOT_SPAWNED,
        DIFFERENT_WORLD,
        TOO_FAR,
        INVALID_LOCATION,
        INVALID_SPEED,
        NO_PATH
    }
}