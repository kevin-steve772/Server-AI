package com.serverai.npc;

import com.serverai.Main;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WanderingTrader;

import java.util.Map;

public final class NpcManager {

    private final Main plugin;
    private volatile Component npcName;
    private volatile String plainNpcName;
    private volatile WanderingTrader npcEntity;

    public NpcManager(Main plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        Component updatedName = plugin.getMessages().get("npc.name", "&b[AI]助手");
        npcName = updatedName;
        plainNpcName = plugin.getMessages().plain(updatedName);
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

        npcEntity = trader;
        plugin.getLogger().info("NPC spawned: " + plainNpcName);
        return true;
    }

    public void despawn() {
        WanderingTrader entity = npcEntity;
        if (entity != null) {
            npcEntity = null;
            runOnEntity(entity, entity::remove);
            plugin.getLogger().info("NPC despawned");
        }
    }

    public void say(String message) {
        String name = plainNpcName;
        plugin.runGlobal(() -> plugin.getServer().broadcast(plugin.getMessages().format(
                "npc.chat-format", "<%npc_name%> %message%",
                Map.of("message", message, "npc_name", name))));
    }

    public boolean isSpawned() {
        return npcEntity != null;
    }

    public String getNpcName() {
        return plainNpcName;
    }

    public WanderingTrader getEntity() {
        return npcEntity;
    }

    private void runOnEntity(WanderingTrader entity, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
            return;
        }
        if (plugin.isEnabled()) {
            entity.getScheduler().execute(plugin, action, () -> {
                if (npcEntity == entity) {
                    npcEntity = null;
                }
            }, 1L);
        }
    }
}
