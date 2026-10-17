package com.splitkeys.keyboard;

/**
 * The two high-contrast keyboard themes (v3.7). High contrast is always
 * on, so these carry only identity — the IME renders each theme from a
 * fixed palette:
 *
 *  LIGHT ("light"): the exact high-contrast look of the stable v3.0
 *  build, rebuilt only from the surviving stock resources (key_bg_hc,
 *  key_bg, key_bg_accent, key_bg_red, space_bg, R.color.kb_bg/key_text/
 *  accent). "White keys, black letters".
 *
 *  DARK ("dark"): the v3.5-beta dark high-contrast palette (near-black
 *  keys, white bold labels, yellow action keys). "Black keys, white
 *  letters".
 */
public class Theme {

    public final String name;
    public final String displayName;
    public final String subtitle;

    private Theme(String name, String displayName, String subtitle) {
        this.name = name;
        this.displayName = displayName;
        this.subtitle = subtitle;
    }

    public static final Theme LIGHT =
            new Theme("light", "Light", "White keys, black letters");
    public static final Theme DARK =
            new Theme("dark", "Dark", "Black keys, white letters");

    /**
     * Look up by stored name. Migrates the old v3.6 names
     * ("stable" -> light, "new" -> dark). Missing or unknown names fall
     * back to Dark (the default).
     */
    public static Theme byName(String name) {
        if ("light".equals(name) || "stable".equals(name)) return LIGHT;
        return DARK;
    }

    public boolean isLight() {
        return this == LIGHT;
    }
}
