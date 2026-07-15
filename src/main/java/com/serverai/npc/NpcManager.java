package com.serverai.npc;

import com.serverai.Main;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;

public class NpcManager {

    private final Main plugin;
    private final String npcName;
    private WanderingTrader npcEntity;

    public NpcManager(Main plugin) {
        this.plugin = plugin;
        this.npcName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("npc.name", "&b[AI]助手"));
    }

    public boolean spawn(Location location) {
        if (npcEntity != null) despawn();

        location.getWorld().getEntitiesByClass(WanderingTrader.class).stream()
                .filter(t -> t.getCustomName() != null && t.getCustomName().equals(npcName))
                .forEach(t -> t.remove());

        WanderingTrader trader = (WanderingTrader) location.getWorld().spawnEntity(location, EntityType.WANDERING_TRADER);
        trader.setCustomName(npcName);
        trader.setCustomNameVisible(true);
        trader.setAI(false);
        trader.setInvulnerable(true);
        trader.setCollidable(false);
        trader.setSilent(true);
        trader.setPersistent(true);
        trader.setRemoveWhenFarAway(false);

        this.npcEntity = trader;
        plugin.getLogger().info("NPC spawned: " + ChatColor.stripColor(npcName));
        return true;
    }

    public void despawn() {
        if (npcEntity != null) {
            npcEntity.remove();
            npcEntity = null;
            plugin.getLogger().info("NPC despawned");
        }
    }

    public void say(String message) {
        String stripName = ChatColor.stripColor(npcName);
        String format = plugin.getConfig().getString("npc.chat-format",
                "<" + stripName + "> %message%");
        String formatted = format
                .replace("%message%", message)
                .replace("%npc_name%", stripName);
        plugin.getServer().broadcast(Component.text(
                ChatColor.translateAlternateColorCodes('&', formatted)));
    }

    public boolean isSpawned() {
        return npcEntity != null && npcEntity.isValid();
    }

    public String getNpcName() {
        return npcName;
    }

    public WanderingTrader getEntity() {
        return npcEntity;
    }
}
