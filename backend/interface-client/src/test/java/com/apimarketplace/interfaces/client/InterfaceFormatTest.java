package com.apimarketplace.interfaces.client;

import com.apimarketplace.interfaces.client.InterfaceFormat.Viewport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InterfaceFormat} - the node-level global display/capture format.
 * A format is either a named preset, an alias for one, or a custom "WxH" string; blank /
 * unknown / out-of-range input resolves to null so every consumer falls back to the classic
 * 1280x800 default.
 */
@DisplayName("InterfaceFormat")
class InterfaceFormatTest {

    @Nested
    @DisplayName("resolve() - named presets")
    class PresetResolution {

        @ParameterizedTest(name = "{0} -> {1}x{2}")
        @CsvSource({
            "classic,      1280,  800",
            "widescreen,   1920, 1080",
            "vertical,     1080, 1920",
            "square,       1080, 1080",
            "portrait,     1080, 1350",
            "mobile,        390,  844",
            "tablet,        820, 1180",
            "desktop,      1440,  900",
            "banner,       1500,  500",
            "social_card,  1200,  630",
            "a4_portrait,   794, 1123",
            "a4_landscape, 1123,  794"
        })
        @DisplayName("Each canonical preset name resolves to its documented pixel dimensions")
        void canonicalPresetsResolveToDocumentedDimensions(String preset, int width, int height) {
            Viewport viewport = InterfaceFormat.resolve(preset);

            assertNotNull(viewport, "preset '" + preset + "' must resolve");
            assertEquals(width, viewport.width());
            assertEquals(height, viewport.height());
        }

        @Test
        @DisplayName("Preset names are matched case-insensitively with surrounding whitespace tolerated")
        void presetMatchingIsCaseInsensitiveAndTrimmed() {
            assertEquals(new Viewport(1080, 1920), InterfaceFormat.resolve("  VERTICAL  "));
            assertEquals(new Viewport(1920, 1080), InterfaceFormat.resolve("WideScreen"));
        }

        @Test
        @DisplayName("presets() exposes all 12 canonical presets")
        void presetsTableExposesAllTwelve() {
            assertEquals(12, InterfaceFormat.presets().size());
            assertTrue(InterfaceFormat.presets().containsKey("classic"));
        }
    }

    @Nested
    @DisplayName("resolve() - aliases")
    class AliasResolution {

        @ParameterizedTest(name = "{0} resolves like {1}")
        @CsvSource({
            "landscape,  classic",
            "horizontal, widescreen",
            "story,      vertical",
            "reel,       vertical",
            "og,         social_card",
            "16:9,       widescreen",
            "9:16,       vertical",
            "1:1,        square",
            "4:5,        portrait"
        })
        @DisplayName("Each alias resolves to the same dimensions as its canonical preset")
        void aliasesResolveToCanonicalPresetDimensions(String alias, String canonical) {
            assertEquals(InterfaceFormat.resolve(canonical), InterfaceFormat.resolve(alias),
                "alias '" + alias + "' must resolve to the same viewport as '" + canonical + "'");
        }
    }

    @Nested
    @DisplayName("resolve() - custom WxH strings")
    class CustomParsing {

        @ParameterizedTest(name = "\"{0}\" parses to 1080x1920")
        @ValueSource(strings = {"1080x1920", "1080X1920", "1080×1920", " 1080 x 1920 "})
        @DisplayName("Custom dimensions accept x / X / × separators and tolerate whitespace")
        void customSeparatorsAndWhitespaceAccepted(String raw) {
            assertEquals(new Viewport(1080, 1920), InterfaceFormat.resolve(raw));
        }

        @Test
        @DisplayName("Dimension bounds are inclusive: 16 and 2160 accepted")
        void boundaryDimensionsAccepted() {
            assertEquals(new Viewport(16, 2160), InterfaceFormat.resolve("16x2160"));
        }

        @Test
        @DisplayName("Odd custom dimensions are floored to even (H.264 screenshot/video parity)")
        void oddCustomDimensionsFlooredToEven() {
            assertEquals(new Viewport(1080, 1920), InterfaceFormat.resolve("1081x1921"));
        }

        @Test
        @DisplayName("Odd lower-bound custom '17x17' floors to 16x16 (still within MIN_DIMENSION)")
        void oddLowerBoundFloorsToMinimum() {
            assertEquals(new Viewport(16, 16), InterfaceFormat.resolve("17x17"));
        }

        @Test
        @DisplayName("Out-of-range odd input is rejected BEFORE flooring ('100x2161' does not floor into range)")
        void boundsCheckedBeforeFlooring() {
            assertNull(InterfaceFormat.resolve("100x2161"),
                "2161 must be rejected as out-of-range, not floored to the 2160 maximum");
        }

        @Test
        @DisplayName("Flooring applies to CUSTOM input only - presets keep their odd dimensions (a4_portrait 794x1123)")
        void presetsAreNotFloored() {
            assertEquals(new Viewport(794, 1123), InterfaceFormat.resolve("a4_portrait"),
                "preset dimensions are authoritative and must not be even-floored");
        }

        @Test
        @DisplayName("Width below the 16px minimum is rejected (null)")
        void widthBelowMinimumRejected() {
            assertNull(InterfaceFormat.resolve("15x100"));
        }

        @Test
        @DisplayName("Height above the 2160px maximum is rejected (null)")
        void heightAboveMaximumRejected() {
            assertNull(InterfaceFormat.resolve("100x2161"));
        }

        @ParameterizedTest(name = "\"{0}\" -> null")
        @ValueSource(strings = {"garbage", "cinema", "1080", "x1920", "1080x", "1080x1920x30", "axb", "10:80"})
        @DisplayName("Garbage / malformed input resolves to null")
        void garbageResolvesToNull(String raw) {
            assertNull(InterfaceFormat.resolve(raw));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Null / blank input resolves to null")
        void nullAndBlankResolveToNull(String raw) {
            assertNull(InterfaceFormat.resolve(raw));
        }
    }

    @Nested
    @DisplayName("normalize()")
    class Normalization {

        @Test
        @DisplayName("Alias 'Landscape' canonicalises to 'classic' (case-insensitive)")
        void aliasCanonicalisedToPresetName() {
            assertEquals("classic", InterfaceFormat.normalize("Landscape"));
        }

        @Test
        @DisplayName("Aspect-ratio alias '16:9' canonicalises to 'widescreen'")
        void aspectRatioAliasCanonicalised() {
            assertEquals("widescreen", InterfaceFormat.normalize("16:9"));
        }

        @Test
        @DisplayName("Valid custom '1080x1920' stays '1080x1920' (canonical lowercase-x form)")
        void customFormatKeptInCanonicalForm() {
            assertEquals("1080x1920", InterfaceFormat.normalize("1080x1920"));
        }

        @Test
        @DisplayName("Custom with × separator and whitespace normalises to the lowercase-x form")
        void customFormatSeparatorsNormalised() {
            assertEquals("1080x1920", InterfaceFormat.normalize(" 1080 × 1920 "));
            assertEquals("1080x1920", InterfaceFormat.normalize("1080X1920"));
        }

        @Test
        @DisplayName("Preset name is lowercased and trimmed to its canonical form")
        void presetNameCanonicalised() {
            assertEquals("vertical", InterfaceFormat.normalize("  VERTICAL "));
        }

        @Test
        @DisplayName("Odd custom dimensions normalise to the even-floored canonical form ('1081x1921' -> '1080x1920')")
        void oddCustomDimensionsNormaliseFloored() {
            assertEquals("1080x1920", InterfaceFormat.normalize("1081x1921"));
            assertEquals("16x16", InterfaceFormat.normalize("17x17"));
        }

        @Test
        @DisplayName("Unknown / out-of-range / blank input normalises to null")
        void unknownAndBlankNormaliseToNull() {
            assertNull(InterfaceFormat.normalize("cinema"));
            assertNull(InterfaceFormat.normalize("15x100"));
            assertNull(InterfaceFormat.normalize("100x2161"));
            assertNull(InterfaceFormat.normalize("   "));
            assertNull(InterfaceFormat.normalize(null));
        }
    }

    @Nested
    @DisplayName("resolveOrDefault()")
    class ResolveOrDefault {

        @Test
        @DisplayName("Null falls back to the classic 1280x800 default")
        void nullFallsBackToClassicDefault() {
            assertEquals(new Viewport(1280, 800), InterfaceFormat.resolveOrDefault(null));
        }

        @Test
        @DisplayName("Unknown format falls back to the classic 1280x800 default")
        void unknownFallsBackToClassicDefault() {
            assertEquals(new Viewport(1280, 800), InterfaceFormat.resolveOrDefault("garbage"));
        }

        @Test
        @DisplayName("Resolvable format wins over the default")
        void resolvableFormatWins() {
            assertEquals(new Viewport(1080, 1920), InterfaceFormat.resolveOrDefault("vertical"));
        }
    }
}
