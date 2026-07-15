package com.serverai.npc;

import com.serverai.Main;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WanderingTrader;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class NpcManager {

    private final Main plugin;

    private volatile Component npcName;
    private volatile String plainNpcName;
    private volatile WanderingTrader npcEntity;
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
        defaultSpeed = clamp(plugin.getConfig().getDouble("npc.default-speed", 1.0), 0.1, 2.0);
        arrivalDistance = clamp(
                plugin.getConfig().getDouble("npc.arrival-distance", 1.5), 0.5, 10.0);
        maxMoveDistance = clamp(
                plugin.getConfig().getDouble("npc.max-move-distance", 128.0), 8.0, 512.0);

        WanderingTrader entity = npcEntity;
        if (entity != null) {
            runOnEntity(entity, () -> entity.customName(updatedName));
        }
    }

    public boolean spawn(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        despawn();

        WanderingTrader trader = (WanderingTrader) world.spawnEntity(
                location, EntityType.WANDERING_TRADER);
        trader.customName(npcName);
        trader.setCustomNameVisible(true);
        trader.setAI(false);
        trader.setInvulnerable(true);
        trader.setCollidable(false);
        trader.setSilent(true);
        trader.setPersistent(false);
        trader.setRemoveWhenFarAway(false);
        trader.setDespawnDelay(Integer.MAX_VALUE);

        npcEntity = trader;
        plugin.getLogger().info("NPC spawned: " + plainNpcName);
        return true;
    }

    public void despawn() {
        WanderingTrader entity = npcEntity;
        if (entity != null) {
            cancelMovementMonitor();
            npcEntity = null;
            runOnEntity(entity, () -> {
                entity.getPathfinder().stopPathfinding();
                entity.remove();
            });
            plugin.getLogger().info("NPC despawned");
        }
    }

    public CompletableFuture<Boolean> moveTo(Location destination, double speed) {
        Location target = destination.clone();
        if (!isValidLocation(target)) {
            return CompletableFuture.completedFuture(false);
        }
        double movementSpeed = clamp(speed, 0.1, 2.0);
        return callOnEntity(entity -> startMovement(entity, target, movementSpeed), false);
    }

    public CompletableFuture<Boolean> stopMovement() {
        return callOnEntity(entity -> {
            finishMovement(entity, null);
            return true;
        }, false);
    }

    public CompletableFuture<NpcState> getState() {
        return callOnEntity(entity -> {
            Location location = entity.getLocation();
            Location target = movementTarget;
            return new NpcState(
                    entity.getWorld().getName(),
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

    public boolean isSpawned() {
        return npcEntity != null;
    }

    public boolean isMoving() {
        return movementTarget != null;
    }

    public double getDefaultSpeed() {
        return defaultSpeed;
    }

    public String getNpcName() {
        return plainNpcName;
    }

    public WanderingTrader getEntity() {
        return npcEntity;
    }

    private boolean startMovement(WanderingTrader entity, Location target, double speed) {
        if (target.getWorld() == null || !entity.getWorld().equals(target.getWorld())) {
            return false;
        }
        if (entity.getLocation().distanceSquared(target) > maxMoveDistance * maxMoveDistance) {
            return false;
        }

        cancelMovementMonitor();
        entity.getPathfinder().stopPathfinding();
        entity.setAI(true);
        boolean started = entity.getPathfinder().moveTo(target, speed);
        if (!started) {
            entity.setAI(false);
            return false;
        }

        movementTarget = target;
        movementTask = entity.getScheduler().runAtFixedRate(plugin,
                task -> monitorMovement(entity, target, task),
                () -> clearRetiredEntity(entity), 1L, 5L);
        return true;
    }

    private void monitorMovement(WanderingTrader entity, Location target, ScheduledTask task) {
        if (npcEntity != entity || movementTask != task || !entity.isValid()) {
            clearMovementTask(task);
            return;
        }

        boolean arrived = entity.getWorld().equals(target.getWorld())
                && entity.getLocation().distanceSquared(target)
                <= arrivalDistance * arrivalDistance;
        if (arrived || !entity.getPathfinder().hasPath()) {
            finishMovement(entity, task);
        }
    }

    private void finishMovement(WanderingTrader entity, ScheduledTask currentTask) {
        if (currentTask != null && movementTask != currentTask) {
            currentTask.cancel();
            return;
        }

        entity.getPathfinder().stopPathfinding();
        entity.setAI(false);
        movementTarget = null;

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
        if (task != null) {
            task.cancel();
        }
    }

    private void clearMovementTask(ScheduledTask task) {
        if (movementTask == task) {
            movementTask = null;
            movementTarget = null;
        }
        task.cancel();
    }

    private void clearRetiredEntity(WanderingTrader entity) {
        if (npcEntity == entity) {
            npcEntity = null;
            cancelMovementMonitor();
        }
    }

    private <T> CompletableFuture<T> callOnEntity(Function<WanderingTrader, T> operation,
                                                   T unavailableValue) {
        WanderingTrader entity = npcEntity;
        if (entity == null || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(unavailableValue);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable action = () -> {
            if (npcEntity != entity || !entity.isValid()) {
                result.complete(unavailableValue);
                return;
            }
            try {
                result.complete(operation.apply(entity));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        };

        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
        } else {
            boolean scheduled = entity.getScheduler().execute(plugin, action, () -> {
                clearRetiredEntity(entity);
                result.complete(unavailableValue);
            }, 1L);
            if (!scheduled) {
                result.complete(unavailableValue);
            }
        }
        return result;
    }

    private void runOnEntity(WanderingTrader entity, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
            return;
        }
        if (plugin.isEnabled()) {
            entity.getScheduler().execute(plugin, action,
                    () -> clearRetiredEntity(entity), 1L);
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
}
