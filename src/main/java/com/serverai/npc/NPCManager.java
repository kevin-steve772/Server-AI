package com.serverai.npc;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCManager {

    private final Plugin plugin;
    private final NPCRegistry registry;
    private final Map<UUID, AINpc> aiNpcs = new ConcurrentHashMap<>();
    private final Map<String, UUID> npcNameToId = new ConcurrentHashMap<>();

    public NPCManager(Plugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.getNPCRegistry();
    }

    public @NotNull AINpc createNpc(@NotNull String name, @NotNull Location location, @NotNull EntityType type) {
        NPC npc = registry.createNPC(type, name);
        npc.spawn(location);
        npc.addTrait(LookClose.class);

        AINpc aiNpc = new AINpc(this, npc);
        UUID id = npc.getUniqueId();
        aiNpcs.put(id, aiNpc);
        npcNameToId.put(name.toLowerCase(), id);

        plugin.getLogger().info("创建 AI NPC: " + name + " (" + id + ") at " + location);
        return aiNpc;
    }

    public void removeNpc(@NotNull UUID id) {
        AINpc aiNpc = aiNpcs.remove(id);
        if (aiNpc != null) {
            npcNameToId.values().remove(id);
            aiNpc.getNpc().destroy();
            plugin.getLogger().info("移除 AI NPC: " + id);
        }
    }

    public void removeNpcByName(@NotNull String name) {
        UUID id = npcNameToId.remove(name.toLowerCase());
        if (id != null) {
            removeNpc(id);
        }
    }

    public @Nullable AINpc getNpc(@NotNull UUID id) {
        return aiNpcs.get(id);
    }

    public @Nullable AINpc getNpcByName(@NotNull String name) {
        UUID id = npcNameToId.get(name.toLowerCase());
        return id != null ? aiNpcs.get(id) : null;
    }

    public @NotNull Collection<AINpc> getAllNpcs() {
        return Collections.unmodifiableCollection(aiNpcs.values());
    }

    public void removeAll() {
        for (AINpc npc : aiNpcs.values()) {
            npc.getNpc().destroy();
        }
        aiNpcs.clear();
        npcNameToId.clear();
    }

    public NPCRegistry getRegistry() {
        return registry;
    }
}