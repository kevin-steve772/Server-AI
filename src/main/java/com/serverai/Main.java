package com.serverai;

import com.serverai.command.AskCommand;
import com.serverai.command.NPCCommand;
import com.serverai.npc.NPCFunctionHandler;
import com.serverai.npc.NPCManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class Main extends JavaPlugin {

    private static Main instance;
    private AiClient aiClient;
    private NPCManager npcManager;
    private NPCFunctionHandler functionHandler;
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        initAiClient();
        initNpcSystem();

        Objects.requireNonNull(getCommand("ask")).setExecutor(new AskCommand(this));
        Objects.requireNonNull(getCommand("npc")).setExecutor(new NPCCommand(this));

        getLogger().info("Server-AI 已启用 (Folia + Citizens 支持)");
    }

    @Override
    public void onDisable() {
        if (npcManager != null) {
            npcManager.removeAll();
        }
        getLogger().info("Server-AI 已禁用");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        initAiClient();
        cooldowns.clear();
    }

    private void initAiClient() {
        String key = getConfig().getString("api.key", "");
        String endpoint = getConfig().getString("api.endpoint", "https://api.openai.com/v1");
        String model = getConfig().getString("api.model", "gpt-3.5-turbo");
        int maxTokens = getConfig().getInt("api.max-tokens", 1024);
        double temperature = getConfig().getDouble("api.temperature", 0.7);
        int timeout = getConfig().getInt("api.timeout", 30);

        if (key.isEmpty() || "your-api-key-here".equals(key)) {
            getLogger().warning("API密钥未配置！请在 config.yml 中设置 api.key");
        }

        aiClient = new AiClient(key, endpoint, model, maxTokens, temperature, timeout);

        if (functionHandler != null) {
            aiClient.setTools(functionHandler.getToolDefinitions(), functionHandler::executeFunction);
        }
    }

    private void initNpcSystem() {
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) {
            getLogger().warning("Citizens 插件未安装或未启用，NPC 功能将不可用");
            return;
        }
        npcManager = new NPCManager(this);
        functionHandler = new NPCFunctionHandler(this, npcManager);
        aiClient.setTools(functionHandler.getToolDefinitions(), functionHandler::executeFunction);
        getLogger().info("NPC 系统已初始化");
    }

    public AiClient getAiClient() {
        return aiClient;
    }

    public NPCManager getNpcManager() {
        return npcManager;
    }

    public NPCFunctionHandler getFunctionHandler() {
        return functionHandler;
    }

    public long getCooldownSeconds() {
        return getConfig().getInt("cooldown", 5);
    }

    public boolean checkCooldown(UUID playerId) {
        Long lastUse = cooldowns.get(playerId);
        long now = System.currentTimeMillis();
        long cooldownMs = TimeUnit.SECONDS.toMillis(getCooldownSeconds());

        if (lastUse != null && (now - lastUse) < cooldownMs) {
            return false;
        }
        cooldowns.put(playerId, now);
        return true;
    }

    public long getRemainingCooldown(UUID playerId) {
        Long lastUse = cooldowns.get(playerId);
        if (lastUse == null) return 0;
        long remaining = TimeUnit.SECONDS.toMillis(getCooldownSeconds()) - (System.currentTimeMillis() - lastUse);
        return Math.max(0, TimeUnit.MILLISECONDS.toSeconds(remaining));
    }

    public static Main getInstance() {
        return instance;
    }
}