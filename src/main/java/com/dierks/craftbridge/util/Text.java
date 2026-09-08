package com.dierks.craftbridge.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;

/** MiniMessage helpers. Item names/lore get italics stripped so they read like vanilla. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gradient:#4fc3f7:#81c784>CraftBridge</gradient> <dark_gray>»</dark_gray> ";

    private Text() {
    }

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** A chat line with the CraftBridge prefix. */
    public static Component msg(String miniMessage) {
        return MM.deserialize(PREFIX + miniMessage);
    }

    /** Item display name / lore line: MiniMessage, never italic unless asked. */
    public static Component item(String miniMessage) {
        return MM.deserialize("<!italic>" + miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(item("<gray>" + line));
        }
        return out;
    }

    public static List<Component> lore(List<String> lines) {
        return lore(lines.toArray(new String[0]));
    }
}
