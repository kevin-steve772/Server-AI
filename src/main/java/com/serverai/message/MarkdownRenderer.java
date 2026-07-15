package com.serverai.message;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class MarkdownRenderer {

    private static final Key MONOSPACE_FONT = Key.key("minecraft", "uniform");
    private static final Component NEWLINE = Component.newline();

    private final Parser parser;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(
                StrikethroughExtension.create(),
                TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
    }

    public Component render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Component.empty();
        }
        Node document = parser.parse(markdown);
        Component body = renderBlockChildren(document, 0);
        return Component.empty().color(NamedTextColor.WHITE).append(body);
    }

    private Component renderBlockChildren(Node parent, int depth) {
        List<Component> blocks = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            Component rendered = renderBlock(child, depth);
            if (rendered != null) {
                blocks.add(rendered);
            }
        }
        return join(blocks, NEWLINE);
    }

    private Component renderBlock(Node node, int depth) {
        if (node instanceof Document) {
            return renderBlockChildren(node, depth);
        }
        if (node instanceof Paragraph) {
            return renderInlineChildren(node);
        }
        if (node instanceof Heading heading) {
            NamedTextColor color = heading.getLevel() == 1
                    ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            return renderInlineChildren(heading).color(color).decorate(TextDecoration.BOLD);
        }
        if (node instanceof FencedCodeBlock codeBlock) {
            return renderCodeBlock(codeBlock.getLiteral(), codeBlock.getInfo());
        }
        if (node instanceof IndentedCodeBlock codeBlock) {
            return renderCodeBlock(codeBlock.getLiteral(), null);
        }
        if (node instanceof BlockQuote) {
            Component content = Component.empty().color(NamedTextColor.GRAY)
                    .append(renderBlockChildren(node, depth));
            return Component.text("> ", NamedTextColor.DARK_GRAY).append(content);
        }
        if (node instanceof BulletList) {
            return renderList(node, depth, null);
        }
        if (node instanceof OrderedList orderedList) {
            return renderList(node, depth, orderedList.getMarkerStartNumber());
        }
        if (node instanceof ThematicBreak) {
            return Component.text("----------------", NamedTextColor.DARK_GRAY);
        }
        if (node instanceof TableBlock) {
            return renderTable(node);
        }
        if (node instanceof HtmlBlock htmlBlock) {
            return Component.text(trimTrailingLineBreaks(htmlBlock.getLiteral()),
                    NamedTextColor.DARK_GRAY);
        }
        if (node instanceof LinkReferenceDefinition) {
            return null;
        }
        return node.getFirstChild() == null ? null : renderBlockChildren(node, depth);
    }

    private Component renderList(Node list, int depth, Integer startNumber) {
        List<Component> items = new ArrayList<>();
        int number = startNumber == null ? 0 : startNumber;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem)) {
                continue;
            }
            String marker = startNumber == null ? "- " : number++ + ". ";
            Component item = Component.text("  ".repeat(depth) + marker,
                            NamedTextColor.GOLD)
                    .append(renderListItem(child, depth));
            items.add(item);
        }
        return join(items, NEWLINE);
    }

    private Component renderListItem(Node item, int depth) {
        Component result = Component.empty();
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            Component part = renderBlock(child, child instanceof BulletList
                    || child instanceof OrderedList ? depth + 1 : depth);
            if (part == null) {
                continue;
            }
            if (!first) {
                result = result.append(NEWLINE);
                if (!(child instanceof BulletList) && !(child instanceof OrderedList)) {
                    result = result.append(Component.text("  ".repeat(depth + 1)));
                }
            }
            result = result.append(part);
            first = false;
        }
        return result;
    }

    private Component renderTable(Node table) {
        List<Component> rows = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            if (section instanceof TableHead || section instanceof TableBody) {
                boolean header = section instanceof TableHead;
                appendTableRows(section, header, rows);
            } else if (section instanceof TableRow) {
                rows.add(renderTableRow(section, false));
            }
        }
        return join(rows, NEWLINE);
    }

    private void appendTableRows(Node section, boolean header, List<Component> rows) {
        for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
            if (row instanceof TableRow) {
                rows.add(renderTableRow(row, header));
            }
        }
    }

    private Component renderTableRow(Node row, boolean header) {
        List<Component> cells = new ArrayList<>();
        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
            if (cell instanceof TableCell) {
                Component content = renderInlineChildren(cell);
                cells.add(header ? content.decorate(TextDecoration.BOLD) : content);
            }
        }
        return join(cells, Component.text(" | ", NamedTextColor.DARK_GRAY));
    }

    private Component renderInlineChildren(Node parent) {
        Component result = Component.empty();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            result = result.append(renderInline(child));
        }
        return result;
    }

    private Component renderInline(Node node) {
        if (node instanceof Text text) {
            return Component.text(text.getLiteral());
        }
        if (node instanceof SoftLineBreak) {
            return Component.space();
        }
        if (node instanceof HardLineBreak) {
            return NEWLINE;
        }
        if (node instanceof Code code) {
            return codeComponent(code.getLiteral());
        }
        if (node instanceof Emphasis) {
            return renderInlineChildren(node).decorate(TextDecoration.ITALIC);
        }
        if (node instanceof StrongEmphasis) {
            return renderInlineChildren(node).decorate(TextDecoration.BOLD);
        }
        if (node instanceof Strikethrough) {
            return renderInlineChildren(node).decorate(TextDecoration.STRIKETHROUGH);
        }
        if (node instanceof Link link) {
            return renderLink(renderInlineChildren(link), link.getDestination());
        }
        if (node instanceof Image image) {
            Component alt = renderInlineChildren(image);
            Component label = Component.text("[image: ", NamedTextColor.GRAY)
                    .append(alt)
                    .append(Component.text("]", NamedTextColor.GRAY));
            return renderLink(label, image.getDestination());
        }
        if (node instanceof HtmlInline htmlInline) {
            return Component.text(htmlInline.getLiteral(), NamedTextColor.DARK_GRAY);
        }
        return node.getFirstChild() == null ? Component.empty() : renderInlineChildren(node);
    }

    private Component renderCodeBlock(String literal, String info) {
        Component result = Component.empty()
                .font(MONOSPACE_FONT)
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
        if (info != null && !info.isBlank()) {
            String language = info.strip().split("\\s+", 2)[0];
            result = result.append(Component.text("[" + language + "]\n",
                    NamedTextColor.DARK_GRAY));
        }
        return result.append(Component.text(trimTrailingLineBreaks(literal)));
    }

    private Component codeComponent(String literal) {
        return Component.text(literal, NamedTextColor.GRAY)
                .font(MONOSPACE_FONT)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component renderLink(Component label, String destination) {
        URI uri = httpUri(destination);
        Component link = label.color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED);
        if (uri == null) {
            return link;
        }
        return link.clickEvent(ClickEvent.openUrl(uri.toString()))
                .hoverEvent(HoverEvent.showText(Component.text(uri.toString(),
                        NamedTextColor.GRAY)));
    }

    private URI httpUri(String destination) {
        try {
            URI uri = URI.create(destination);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid destinations remain visible but are not clickable.
        }
        return null;
    }

    private static Component join(List<Component> components, Component delimiter) {
        Component result = Component.empty();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) {
                result = result.append(delimiter);
            }
            result = result.append(components.get(index));
        }
        return result;
    }

    private static String trimTrailingLineBreaks(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
            end--;
        }
        return value.substring(0, end);
    }
}
