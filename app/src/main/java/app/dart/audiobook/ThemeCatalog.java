package app.dart.audiobook;

/** Dart-owned presentation of the approved Petal color values; no other app code or assets. */
final class ThemeCatalog {
    static final class Theme {
        final String id;
        final String name;
        final int background;
        final int card;
        final int action;
        final int ink;
        final int outside;
        final int outline;
        final int actionInk;
        final int button;

        Theme(String id, String name, int background, int card, int action, int ink,
              int outside, int outline, int actionInk, int button) {
            this.id = id;
            this.name = name;
            this.background = background;
            this.card = card;
            this.action = action;
            this.ink = ink;
            this.outside = outside;
            this.outline = outline;
            this.actionInk = actionInk;
            this.button = button;
        }
    }

    private static Theme theme(String id, String name, int background, int card, int action,
                               int ink, int outside, int outline) {
        return new Theme(id, name, background, card, action, ink, outside, outline, ink, action);
    }

    static final Theme[] ALL = {
            new Theme("warm", "Warm Stone", 0xfff2eeeb, 0xfffffaf2, 0xff724151,
                    0xff211b22, 0xff211b22, 0xffd8c4ca, 0xfffffaf2, 0xfff1e5e8),
            theme("rose", "Rose Porcelain", 0xff8a4d63, 0xfff5f0e8, 0xffe6b4c5, 0xff211b22, 0xfffff8f0, 0xff9f9c97),
            theme("sage", "Sage & Linen", 0xff596c5a, 0xffeee4cf, 0xffd9c7ab, 0xff211b22, 0xfffff8f0, 0xff9b9487),
            theme("navy", "Harbor Jade", 0xff142c47, 0xff72c9bb, 0xffd5f1ce, 0xff211b22, 0xfffff8f0, 0xff4a837a),
            theme("ocean", "Tidepool Glass", 0xffd5e7e2, 0xff3b7775, 0xff254e65, 0xfffff8f0, 0xff211b22, 0xff264d4c),
            theme("blush", "Blush Denim", 0xff3a5275, 0xffdfb1be, 0xffc7ddec, 0xff211b22, 0xfffff8f0, 0xff91737c),
            theme("arctic", "Arctic Sail", 0xff244c72, 0xffb7dff3, 0xffeef2fa, 0xff211b22, 0xfffff8f0, 0xff77919e),
            theme("violet", "Moonlit Orchid", 0xff302940, 0xffb79ad7, 0xffd7c2f2, 0xff211b22, 0xfffff8f0, 0xff77648c),
            theme("berry", "Berry Sorbet", 0xffe8bcd7, 0xff72254f, 0xff452d62, 0xfffff8f0, 0xff211b22, 0xff4a1833),
            theme("vermilion", "Vermilion Ink", 0xff172c3b, 0xfff18069, 0xfff2ca82, 0xff211b22, 0xfffff8f0, 0xff9d5344),
            theme("lemon", "Lemon Grove", 0xfff1e7a6, 0xff346c55, 0xff31586e, 0xfffff8f0, 0xff211b22, 0xff224637),
            theme("cobalt", "Cobalt Bloom", 0xfff6bcad, 0xff264aa0, 0xff34406e, 0xfffff8f0, 0xff211b22, 0xff193068),
            theme("pistachio", "Pistachio Plum", 0xffccd98f, 0xff612750, 0xff3b4569, 0xfffff8f0, 0xff211b22, 0xff3f1934),
            theme("marigold", "Marigold Sail", 0xff183455, 0xfff1d95b, 0xffffe9a8, 0xff211b22, 0xfffff8f0, 0xff9d8d3b),
            theme("coral", "Coral Reef", 0xffeead94, 0xff19515f, 0xff313f70, 0xfffff8f0, 0xff211b22, 0xff10353e),
            theme("chartreuse", "Chartreuse Smoke", 0xff354351, 0xffc5d46e, 0xffdceab1, 0xff211b22, 0xfffff8f0, 0xff808a48),
            theme("amethyst", "Amethyst Ice", 0xff67428a, 0xffc4e4ef, 0xffe3d1f3, 0xff211b22, 0xfffff8f0, 0xff7f949b),
            theme("standardlight", "Standard Light", 0xffe6e6e6, 0xffffffff, 0xffd1d1d1, 0xff222222, 0xff222222, 0xffa3a3a3),
            theme("standarddark", "Standard Dark", 0xff181818, 0xff333333, 0xff484848, 0xfff5f5f5, 0xfff5f5f5, 0xff6b6b6b)
    };

    static Theme forId(String id) {
        for (Theme theme : ALL) if (theme.id.equals(id)) return theme;
        return ALL[0];
    }

    static int indexOf(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].id.equals(id)) return i;
        return 0;
    }

    private ThemeCatalog() { }
}
