package com.serverai.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class MessageService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Component get(String path, String fallback) {
        FileConfiguration config = plugin.getConfig();
        String value = config.getString(path, fallback);
        return LEGACY.deserialize(value == null ? fallback : value);
    }

    public Component format(String path, String fallback, Map<String, String> replacements) {
        Component message = get(path, fallback);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            TextReplacementConfig replacement = TextReplacementConfig.builder()
                    .matchLiteral('%' + entry.getKey() + '%')
                    .replacement(Component.text(entry.getValue()))
                    .build();
            message = message.replaceText(replacement);
        }
        return message;
    }

    public String plain(Component component) {
        return PLAIN.serialize(component);
    }
}
