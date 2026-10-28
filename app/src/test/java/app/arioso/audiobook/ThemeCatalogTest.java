package app.arioso.audiobook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeCatalogTest {
    @Test public void shibaPalettesMatchApprovedValues() {
        ThemeCatalog.Theme blackTan = ThemeCatalog.forId("shiba_black_tan");
        assertEquals("Black & Tan", blackTan.name);
        assertEquals(0xff1c1b1a, blackTan.background);
        assertEquals(0xff3e3730, blackTan.card);
        assertEquals(0xfff6efe2, blackTan.ink);

        ThemeCatalog.Theme apricot = ThemeCatalog.forId("shiba_apricot");
        assertEquals("Apricot & Cream", apricot.name);
        assertEquals(0xffe8dec8, apricot.background);
        assertEquals(0xffeee2cc, apricot.card);
        assertEquals(0xff1c1b1a, apricot.ink);

        ThemeCatalog.Theme sesame = ThemeCatalog.forId("shiba_sesame");
        assertEquals("Sesame Dusk", sesame.name);
        assertEquals(0xff302822, sesame.background);
        assertEquals(0xff574536, sesame.card);
        assertEquals(0xfff6efe2, sesame.ink);
    }

    @Test public void newAndUnknownThemesUseBlackTanWithoutChangingSavedChoices() {
        assertEquals("shiba_black_tan", ThemeCatalog.DEFAULT_ID);
        assertSame(ThemeCatalog.forId(ThemeCatalog.DEFAULT_ID), ThemeCatalog.forId(null));
        assertSame(ThemeCatalog.forId(ThemeCatalog.DEFAULT_ID), ThemeCatalog.forId("unknown"));
        assertEquals(ThemeCatalog.indexOf(ThemeCatalog.DEFAULT_ID), ThemeCatalog.indexOf("unknown"));
        assertEquals("midnight", ThemeCatalog.forId("midnight").id);
    }

    @Test public void themeTextPairsMeetNormalTextContrast() {
        for (ThemeCatalog.Theme theme : ThemeCatalog.ALL) {
            assertTrue(theme.name + " background", contrast(theme.background, theme.outside) >= 4.5);
            assertTrue(theme.name + " card", contrast(theme.card, theme.ink) >= 4.5);
            assertTrue(theme.name + " action", contrast(theme.action, theme.actionInk) >= 4.5);
        }
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        double r = linear((color >> 16) & 255);
        double g = linear((color >> 8) & 255);
        double b = linear(color & 255);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linear(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
