package com.serverai;

import com.serverai.command.AskCommand;
import com.serverai.command.NpcCommand;
import com.serverai.message.MessageService;
import com.serverai.npc.NpcManager;
import com.serverai.npc.NpcToolController;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main extends JavaPlugin implements Listener {

    private final Map<UUID, Long> cooldownDeadlines = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();

    private volatile AiClient aiClient;
    private NpcManager npcManager;
    private NpcToolController npcTools;
    private MessageService messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageService(this);
        npcManager = new NpcManager(this);
        npcTools = new NpcToolController(this, npcManager);
        initAiClient();

        AskCommand askCommand = new AskCommand(this);
        NpcCommand npcCommand = new NpcCommand(this);
        Objects.requireNonNull(getCommand("ask"), "Command 'ask' is missing from plugin.yml")
                .setExecutor(askCommand);
        Objects.requireNonNull(getCommand("npc"), "Command 'npc' is missing from plugin.yml")
                .setExecutor(npcCommand);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Server-AI v" + getPluginMeta().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        pendingRequests.clear();
        cooldownDeadlines.clear();
        if (npcManager != null) {
            npcManager.despawn();
        }
        getLogger().info("Server-AI 已禁用");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        npcManager.reloadConfig();
        initAiClient();
        cooldownDeadlines.clear();
    }

    private void initAiClient() {
        String key = resolveApiKey();
        boolean requireKey = getConfig().getBoolean("api.require-key", true);
        String endpoint = getConfig().getString("api.endpoint", "https://api.openai.com/v1");
        String model = getConfig().getString("api.model", "gpt-3.5-turbo");
        int maxTokens = clamp(getConfig().getInt("api.max-tokens", 1024), 1, 128_000);
        double temperature = Math.max(0.0, Math.min(2.0,
                getConfig().getDouble("api.temperature", 0.7)));
        int timeout = clamp(getConfig().getInt("api.timeout", 30), 1, 300);
        int maxConcurrentRequests = clamp(
                getConfig().getInt("api.max-concurrent-requests", 4), 1, 64);

        AiClient newClient = new AiClient(key, endpoint, model, maxTokens, temperature, timeout,
                maxConcurrentRequests, requireKey);
        newClient.setTools(npcTools.getDefinitions(), npcTools::execute);
        aiClient = newClient;

        if (!newClient.isConfigured()) {
            getLogger().warning("API密钥未配置！请设置环境变量或 config.yml 中的 api.key");
        }
    }

    private String resolveApiKey() {
        String environmentVariable = getConfig().getString("api.key-env", "SERVER_AI_API_KEY");
        if (environmentVariable != null && !environmentVariable.isBlank()) {
            String environmentKey = System.getenv(environmentVariable.trim());
            if (environmentKey != null && !environmentKey.isBlank()) {
                return environmentKey.trim();
            }
        }
        String configuredKey = getConfig().getString("api.key", "");
        return configuredKey == null ? "" : configuredKey.trim();
    }

    public AiClient getAiClient() {
        return aiClient;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public MessageService getMessages() {
        return messages;
    }

    public int getMaxQuestionLength() {
        return clamp(getConfig().getInt("api.max-question-length", 1000), 1, 20_000);
    }

    public long getCooldownSeconds() {
        return clamp(getConfig().getInt("cooldown", 5), 0, 86_400);
    }

    public boolean checkCooldown(UUID playerId) {
        long now = System.nanoTime();
        long cooldownNanos = TimeUnit.SECONDS.toNanos(getCooldownSeconds());
        AtomicBoolean allowed = new AtomicBoolean();
        cooldownDeadlines.compute(playerId, (ignored, deadline) -> {
            if (deadline == null || deadline <= now) {
                allowed.set(true);
                return now + cooldownNanos;
            }
            return deadline;
        });
        return allowed.get();
    }

    public long getRemainingCooldown(UUID playerId) {
        Long deadline = cooldownDeadlines.get(playerId);
        if (deadline == null) {
            return 0;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            cooldownDeadlines.remove(playerId, deadline);
            return 0;
        }
        return TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1;
    }

    public boolean tryStartRequest(UUID playerId) {
        return pendingRequests.add(playerId);
    }

    public void finishRequest(UUID playerId) {
        pendingRequests.remove(playerId);
    }

    public boolean runGlobal(Runnable action) {
        if (isEnabled()) {
            getServer().getGlobalRegionScheduler().execute(this, action);
            return true;
        }
        return false;
    }

    public void runForSender(CommandSender sender, Runnable action) {
        if (!isEnabled()) {
            return;
        }
        if (sender instanceof Player player) {
            player.getScheduler().execute(this, action, () -> { }, 1L);
        } else {
            runGlobal(action);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldownDeadlines.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcPotionEffect(EntityPotionEffectEvent event) {
        if (npcManager == null || event.getEntity() != npcManager.getEntity()) {
            return;
        }
        PotionEffect newEffect = event.getNewEffect();
        if (newEffect != null && newEffect.getType().equals(PotionEffectType.INVISIBILITY)) {
            event.setCancelled(true);
            npcManager.keepVisible();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
