package com.serverai.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersCommonMarkdownAndGfmBlocks() {
        Component result = renderer.render("""
                # Title
                Use **bold**, *italic*, ~~old~~ and `code`.

                1. first
                2. second

                > quote

                ```java
                int x = 1;
                ```

                | name | value |
                | --- | --- |
                | x | 1 |
                """);

        assertEquals("""
                Title
                Use bold, italic, old and code.
                1. first
                2. second
                > quote
                [java]
                int x = 1;
                name | value
                x | 1""", PLAIN.serialize(result));
        assertTrue(hasDecoration(result, "Title", TextDecoration.BOLD, false));
        assertTrue(hasDecoration(result, "bold", TextDecoration.BOLD, false));
        assertTrue(hasDecoration(result, "italic", TextDecoration.ITALIC, false));
        assertTrue(hasDecoration(result, "old", TextDecoration.STRIKETHROUGH, false));
        assertFalse(hasDecoration(result, "code", TextDecoration.ITALIC, false));
    }

    @Test
    void makesOnlyHttpLinksClickable() {
        Component result = renderer.render(
                "[site](https://example.com/docs) [unsafe](javascript:alert(1))");

        assertEquals("https://example.com/docs", clickUrl(result, "site", null));
        assertNull(clickUrl(result, "unsafe", null));
    }

    private boolean hasDecoration(Component component, String text,
                                  TextDecoration decoration, boolean inherited) {
        TextDecoration.State state = component.decoration(decoration);
        boolean active = state == TextDecoration.State.NOT_SET
                ? inherited : state == TextDecoration.State.TRUE;
        if (component instanceof TextComponent textComponent
                && textComponent.content().equals(text)) {
            return active;
        }
        for (Component child : component.children()) {
            if (hasDecoration(child, text, decoration, active)) {
                return true;
            }
        }
        return false;
    }

    private String clickUrl(Component component, String text, ClickEvent inherited) {
        ClickEvent clickEvent = component.clickEvent() == null
                ? inherited : component.clickEvent();
        if (component instanceof TextComponent textComponent
                && textComponent.content().equals(text)) {
            return clickEvent == null ? null : clickEvent.value();
        }
        for (Component child : component.children()) {
            String value = clickUrl(child, text, clickEvent);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
