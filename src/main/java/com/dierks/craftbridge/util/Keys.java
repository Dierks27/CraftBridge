package com.dierks.craftbridge.util;

import org.bukkit.NamespacedKey;

/** Every PDC / NamespacedKey the plugin uses, in one place. Namespace is always {@code craftbridge}. */
public final class Keys {

    public static final String NAMESPACE = "craftbridge";

    /** Marks a GUI icon so click handlers can tell buttons from real items. */
    public static final NamespacedKey GUI_BUTTON = key("gui_button");

    private Keys() {
    }

    public static NamespacedKey key(String name) {
        return new NamespacedKey(NAMESPACE, name);
    }
}
