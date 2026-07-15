package com.serverai.command;

import com.serverai.AiClient;
import com.serverai.Main;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class AskCommand implements TabExecutor {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are an AI assistant inside a Minecraft server. Use the available NPC tools whenever
            the requester asks the NPC to move, come to a player, stop, speak, or report its location.
            The NPC must be spawned before a tool can control it. Do not claim a tool succeeded unless
            its result says it succeeded.
            """;

    private final Main plugin;

    public AskCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }

        if (!sender.hasPermission("serverai.ask")) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.no-permission", "&c你没有权限执行此命令。"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.usage", "&e用法: /ask <问题>"));
            return true;
        }

        AiClient client = plugin.getAiClient();
        if (!client.isConfigured()) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.no-key", "&cAPI密钥未配置，请设置环境变量或 config.yml 中的 api.key"));
            return true;
        }

        String question = String.join(" ", args).trim();
        int questionLength = question.codePointCount(0, question.length());
        int maxQuestionLength = plugin.getMaxQuestionLength();
        if (questionLength > maxQuestionLength) {
            sender.sendMessage(plugin.getMessages().format(
                    "messages.question-too-long",
                    "&c问题过长，最多允许 %max% 个字符。",
                    Map.of("max", String.valueOf(maxQuestionLength))));
            return true;
        }

        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        if (playerId != null && !plugin.tryStartRequest(playerId)) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.in-progress", "&c你已有一个请求正在处理中，请等待完成。"));
            return true;
        }
        if (playerId != null && !plugin.checkCooldown(playerId)) {
            plugin.finishRequest(playerId);
            sender.sendMessage(plugin.getMessages().format(
                    "messages.cooldown", "&c请等待 %seconds% 秒后再询问。",
                    Map.of("seconds", String.valueOf(plugin.getRemainingCooldown(playerId)))));
            return true;
        }

        String senderName = sender instanceof Player player ? player.getName() : "Console";
        plugin.runGlobal(() -> plugin.getServer().broadcast(plugin.getMessages().format(
                "messages.broadcast-question",
                "&6[提问] &e%sender%&6: &f%question%",
                Map.of("sender", senderName, "question", question))));
        sender.sendMessage(plugin.getMessages().get(
                "messages.thinking", "&e正在思考中，请稍候..."));

        try {
            boolean canControlNpc = sender.hasPermission("serverai.npc");
            client.askWithFunctionsAsync(question, createHistory(sender, canControlNpc),
                            canControlNpc)
                    .whenComplete((answer, error) -> {
                        if (playerId != null) {
                            plugin.finishRequest(playerId);
                        }
                        if (error == null) {
                            plugin.runGlobal(() -> broadcastAnswer(answer));
                        } else {
                            Throwable cause = unwrap(error);
                            plugin.getLogger().log(Level.WARNING, "AI request failed", cause);
                            plugin.runForSender(sender, () -> sendError(sender, cause));
                        }
                    });
        } catch (RuntimeException exception) {
            if (playerId != null) {
                plugin.finishRequest(playerId);
            }
            plugin.getLogger().log(Level.WARNING, "AI request could not be started", exception);
            sendError(sender, exception);
        }

        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("serverai.reload")) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.no-permission", "&c你没有权限执行此命令。"));
            return true;
        }
        try {
            plugin.reloadPluginConfig();
            sender.sendMessage(plugin.getMessages().get(
                    "messages.reloaded", "&a配置已重载。"));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Configuration reload failed", exception);
            sender.sendMessage(plugin.getMessages().format(
                    "messages.reload-error", "&c配置重载失败: %error%",
                    Map.of("error", safeMessage(exception))));
        }
        return true;
    }

    private void broadcastAnswer(String answer) {
        if (plugin.getNpcManager().isSpawned()) {
            plugin.getNpcManager().say(answer);
            return;
        }
        plugin.getServer().broadcast(plugin.getMessages().formatComponents(
                "messages.broadcast-answer", "&b[AI] %answer%",
                Map.of("answer", plugin.getMessages().markdown(answer))));
    }

    private void sendError(CommandSender sender, Throwable error) {
        if (error instanceof AiClient.AiClientException
                && "API key is not configured".equals(error.getMessage())) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.no-key", "&cAPI密钥未配置"));
            return;
        }
        sender.sendMessage(plugin.getMessages().format(
                "messages.error", "&c请求AI时出错: %error%",
                Map.of("error", safeMessage(error))));
    }

    private List<Map<String, Object>> createHistory(CommandSender sender,
                                                     boolean canControlNpc) {
        String configuredPrompt = plugin.getConfig().getString(
                "api.system-prompt", DEFAULT_SYSTEM_PROMPT);
        StringBuilder context = new StringBuilder(
                configuredPrompt == null || configuredPrompt.isBlank()
                        ? DEFAULT_SYSTEM_PROMPT : configuredPrompt.trim());
        context.append("\nNPC spawned: ").append(plugin.getNpcManager().isSpawned()).append('.');
        context.append("\nRequester has NPC control permission: ")
                .append(canControlNpc).append('.');
        if (!canControlNpc) {
            context.append(" Do not claim to move or control the NPC.");
        }

        if (sender instanceof Player player) {
            Location location = player.getLocation();
            context.append(String.format(Locale.ROOT,
                    "\nRequester: %s. Current location: world=%s x=%.2f y=%.2f z=%.2f.",
                    player.getName(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ()));
        } else {
            context.append("\nRequester: server console.");
        }
        return List.of(Map.of("role", "system", "content", context.toString()));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("serverai.reload")) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }
}
