package com.serverai.npc;

import com.serverai.Main;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Skin;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.npc.pathfinder.PathType;
import net.citizensnpcs.util.NPCCreator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class NpcManager {

    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;
    private static final double PATH_SEGMENT_DISTANCE = 24.0;
    private static final double PROGRESS_DISTANCE_SQUARED = 1.0;
    private static final int MAX_STALLED_REPATHS = 3;
    private static final int MAX_AIRBORNE_WAIT_CHECKS = 20;

    private final Main plugin;
    private final NPCRegistry registry;

    private volatile Component npcName;
    private volatile String plainNpcName;
    private volatile String npcSkin;
    private volatile NPC npc;
    private volatile ScheduledTask movementTask;
    private volatile Location movementTarget;
    private volatile Location lastProgressLocation;
    private volatile int stalledRepaths;
    private volatile int airborneWaitChecks;
    private volatile double defaultSpeed;
    private volatile double arrivalDistance;
    private volatile double maxMoveDistance;

    public NpcManager(Main plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.getNPCRegistry();
        reloadConfig();
    }

    public void reloadConfig() {
        Component updatedName = plugin.getMessages().get("npc.name", "&b[AI]助手");
        npcName = updatedName;
        plainNpcName = plugin.getMessages().plain(updatedName);
        npcSkin = plugin.getConfig().getString("npc.skin", "");
        defaultSpeed = clamp(plugin.getConfig().getDouble("npc.default-speed", 1.0),
                MIN_SPEED, MAX_SPEED);
        arrivalDistance = clamp(
                plugin.getConfig().getDouble("npc.arrival-distance", 1.5), 0.5, 10.0);
        maxMoveDistance = clamp(
                plugin.getConfig().getDouble("npc.max-move-distance", 512.0), 8.0, 512.0);

        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            entity.setName(npcName);
            if (!npcSkin.isBlank()) {
                Skin skinTrait = entity.getOrAddTrait(Skin.class);
                skinTrait.setSkinName(npcSkin);
            }
        }
    }

    public boolean spawn(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        despawn();

        NPC newNpc = NPCCreator.createNPC(EntityType.PLAYER, npcName);
        newNpc.spawn(location);
        newNpc.setProtected(true);
        newNpc.setFlyable(false);
        newNpc.setInvulnerable(true);
        newNpc.setCollidable(false);

        Navigator navigator = newNpc.getNavigator();
        navigator.setLocalPathfinder(PathType.PLAYER_REALISTIC);

        if (!npcSkin.isBlank()) {
            Skin skinTrait = newNpc.getOrAddTrait(Skin.class);
            skinTrait.setSkinName(npcSkin);
        }

        npc = newNpc;
        plugin.getLogger().info("NPC spawned: " + plainNpcName);
        return true;
    }

    public void despawn() {
        NPC entity = npc;
        if (entity != null) {
            cancelMovementMonitor();
            npc = null;
            if (entity.isSpawned()) {
                entity.destroy();
            }
            plugin.getLogger().info("NPC despawned");
        }
    }

    public CompletableFuture<MoveResult> moveTo(Location destination, double speed) {
        Location target = destination.clone();
        if (!isValidLocation(target)) {
            return CompletableFuture.completedFuture(MoveResult.INVALID_LOCATION);
        }
        if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
            return CompletableFuture.completedFuture(MoveResult.INVALID_SPEED);
        }
        return callOnEntity(entity -> startMovement(entity, target, speed),
                MoveResult.NOT_SPAWNED);
    }

    public CompletableFuture<Boolean> stopMovement() {
        return callOnEntity(entity -> {
            finishMovement(entity, null);
            return true;
        }, false);
    }

    public CompletableFuture<NpcState> getState() {
        return callOnEntity(entity -> {
            Location location = entity.getEntity().getLocation();
            Location target = movementTarget;
            return new NpcState(
                    entity.getEntity().getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    target != null,
                    target == null ? null : target.getWorld().getName(),
                    target == null ? null : target.getX(),
                    target == null ? null : target.getY(),
                    target == null ? null : target.getZ());
        }, null);
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
        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            Equipment equip = entity.getOrAddTrait(Equipment.class);
            equip.set(Equipment.EquipmentSlot.HAND, handItem);
        }
    }

    public void setArmor(ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            Equipment equip = entity.getOrAddTrait(Equipment.class);
            if (helmet != null) equip.set(Equipment.EquipmentSlot.HELMET, helmet);
            if (chest != null) equip.set(Equipment.EquipmentSlot.CHESTPLATE, chest);
            if (legs != null) equip.set(Equipment.EquipmentSlot.LEGGINGS, legs);
            if (boots != null) equip.set(Equipment.EquipmentSlot.BOOTS, boots);
        }
    }

    public void lookAt(Location location) {
        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            entity.faceLocation(location);
        }
    }

    public void teleport(Location location) {
        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            entity.teleport(location, true);
        }
    }

    public void setSkin(String skinName) {
        this.npcSkin = skinName;
        NPC entity = npc;
        if (entity != null && entity.isSpawned()) {
            Skin skinTrait = entity.getOrAddTrait(Skin.class);
            skinTrait.setSkinName(skinName);
        }
    }

    public boolean isSpawned() {
        NPC entity = npc;
        return entity != null && entity.isSpawned();
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

    public NPC getEntity() {
        return npc;
    }

    private MoveResult startMovement(NPC entity, Location target, double speed) {
        if (target.getWorld() == null || !entity.getEntity().getWorld().equals(target.getWorld())) {
            return MoveResult.DIFFERENT_WORLD;
        }
        Location currentLocation = entity.getEntity().getLocation();
        double distanceSquared = currentLocation.distanceSquared(target);
        if (distanceSquared > maxMoveDistance * maxMoveDistance) {
            return MoveResult.TOO_FAR;
        }
        if (distanceSquared <= arrivalDistance * arrivalDistance) {
            finishMovement(entity, null);
            return MoveResult.ALREADY_THERE;
        }

        cancelMovementMonitor();
        entity.getNavigator().cancelNavigation();
        boolean canPathNow = canStartGroundPath(entity);
        if (canPathNow && !startNextPathSegment(entity, target, speed)) {
            return MoveResult.NO_PATH;
        }

        movementTarget = target;
        lastProgressLocation = currentLocation;
        stalledRepaths = 0;
        airborneWaitChecks = 0;
        movementTask = entity.getEntity().getScheduler().runAtFixedRate(plugin,
                task -> monitorMovement(entity, target, speed, task),
                () -> clearRetiredEntity(entity), 1L, 5L);
        return MoveResult.STARTED;
    }

    private void monitorMovement(NPC entity, Location target, double speed,
                                 ScheduledTask task) {
        if (npc != entity || movementTask != task || !entity.isSpawned()) {
            clearMovementTask(task);
            return;
        }

        Location currentLocation = entity.getEntity().getLocation();
        boolean arrived = entity.getEntity().getWorld().equals(target.getWorld())
                && currentLocation.distanceSquared(target)
                <= arrivalDistance * arrivalDistance;
        if (arrived) {
            finishMovement(entity, task);
            return;
        }

        Location previousLocation = lastProgressLocation;
        if (previousLocation == null
                || previousLocation.getWorld() != currentLocation.getWorld()
                || previousLocation.distanceSquared(currentLocation) >= PROGRESS_DISTANCE_SQUARED) {
            lastProgressLocation = currentLocation;
            stalledRepaths = 0;
        }

        if (entity.getNavigator().isNavigating()) {
            return;
        }

        if (!canStartGroundPath(entity)) {
            airborneWaitChecks++;
            if (airborneWaitChecks >= MAX_AIRBORNE_WAIT_CHECKS) {
                finishMovement(entity, task);
            }
            return;
        }

        airborneWaitChecks = 0;
        stalledRepaths++;
        if (stalledRepaths >= MAX_STALLED_REPATHS) {
            finishMovement(entity, task);
            return;
        }
        startNextPathSegment(entity, target, speed);
    }

    private boolean startNextPathSegment(NPC entity, Location target, double speed) {
        Location current = entity.getEntity().getLocation();
        double distance = current.distance(target);
        if (distance <= PATH_SEGMENT_DISTANCE) {
            return entity.getNavigator().setTarget(target, true, (float) speed);
        }

        double ratio = PATH_SEGMENT_DISTANCE / distance;
        Location waypoint = current.clone().add(
                (target.getX() - current.getX()) * ratio,
                (target.getY() - current.getY()) * ratio,
                (target.getZ() - current.getZ()) * ratio);
        if (entity.getNavigator().setTarget(waypoint, true, (float) speed)) {
            return true;
        }
        return entity.getNavigator().setTarget(target, true, (float) speed);
    }

    private static boolean canStartGroundPath(NPC entity) {
        return entity.getEntity().isOnGround()
                || entity.getEntity().isInWater()
                || entity.getEntity().isInsideVehicle();
    }

    private void finishMovement(NPC entity, ScheduledTask currentTask) {
        if (currentTask != null && movementTask != currentTask) {
            currentTask.cancel();
            return;
        }

        entity.getNavigator().cancelNavigation();
        movementTarget = null;
        lastProgressLocation = null;
        stalledRepaths = 0;
        airborneWaitChecks = 0;

        ScheduledTask task = movementTask;
        if (currentTask == null || task == currentTask) {
            movementTask = null;
        }
        if (currentTask != null) {
            currentTask.cancel();
        } else if (task != null) {
            task.cancel();
        }
    }

    private void cancelMovementMonitor() {
        ScheduledTask task = movementTask;
        movementTask = null;
        movementTarget = null;
        lastProgressLocation = null;
        stalledRepaths = 0;
        airborneWaitChecks = 0;
        if (task != null) {
            task.cancel();
        }
    }

    private void clearMovementTask(ScheduledTask task) {
        if (movementTask == task) {
            movementTask = null;
            movementTarget = null;
            lastProgressLocation = null;
            stalledRepaths = 0;
            airborneWaitChecks = 0;
        }
        task.cancel();
    }

    private void clearRetiredEntity(NPC entity) {
        if (npc == entity) {
            npc = null;
            cancelMovementMonitor();
        }
    }

    private <T> CompletableFuture<T> callOnEntity(Function<NPC, T> operation,
                                                   T unavailableValue) {
        NPC entity = npc;
        if (entity == null || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(unavailableValue);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable action = () -> {
            if (npc != entity || !entity.isSpawned()) {
                result.complete(unavailableValue);
                return;
            }
            try {
                result.complete(operation.apply(entity));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        };

        if (Bukkit.isOwnedByCurrentRegion(entity.getEntity())) {
            action.run();
        } else {
            boolean scheduled = entity.getEntity().getScheduler().execute(plugin, action,
                    () -> {
                        clearRetiredEntity(entity);
                        result.complete(unavailableValue);
                    }, 1L);
            if (!scheduled) {
                result.complete(unavailableValue);
            }
        }
        return result;
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