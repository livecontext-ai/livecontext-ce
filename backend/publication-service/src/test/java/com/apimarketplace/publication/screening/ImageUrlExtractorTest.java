package com.apimarketplace.publication.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImageUrlExtractor - Wave 2a static scan")
class ImageUrlExtractorTest {

    @Nested
    @DisplayName("extractFromHtml - <img src>")
    class ImgSrc {

        @Test
        @DisplayName("Single img tag with double-quoted src is captured")
        void singleImgDoubleQuoted() {
            String html = "<img src=\"https://cdn.example.com/hero.jpg\" alt=\"hero\">";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactly("https://cdn.example.com/hero.jpg");
        }

        @Test
        @DisplayName("Single img tag with single-quoted src is captured")
        void singleImgSingleQuoted() {
            String html = "<img alt='x' src='https://cdn.example.com/hero.jpg'>";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactly("https://cdn.example.com/hero.jpg");
        }

        @Test
        @DisplayName("Multiple img tags are all captured and deduplicated")
        void multipleImgDedup() {
            String html = "<img src=\"a.jpg\"><img src=\"b.jpg\"><img src=\"a.jpg\">";
            Set<String> urls = ImageUrlExtractor.extractFromHtml(html);
            assertThat(urls).containsExactly("a.jpg", "b.jpg");
        }

        @Test
        @DisplayName("data: URI src is skipped (publisher-controlled bytes, no network fetch)")
        void dataUriSkipped() {
            String html = "<img src=\"data:image/png;base64,iVBORw0KGgo=\"><img src=\"https://x.com/y.png\">";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactly("https://x.com/y.png");
        }

        @Test
        @DisplayName("javascript: / mailto: / about: schemes are skipped (not network image fetches)")
        void nonNetworkSchemesSkipped() {
            String html = "<img src=\"javascript:alert(1)\"><img src=\"about:blank\"><img src=\"mailto:x@y\">";
            assertThat(ImageUrlExtractor.extractFromHtml(html)).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractFromHtml - srcset")
    class Srcset {

        @Test
        @DisplayName("srcset with descriptor list yields each URL minus descriptor")
        void srcsetSplitsCorrectly() {
            String html = "<img srcset=\"https://x.com/a.jpg 1x, https://x.com/b.jpg 2x\">";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactlyInAnyOrder("https://x.com/a.jpg", "https://x.com/b.jpg");
        }

        @Test
        @DisplayName("source srcset (used by picture element) is also captured")
        void pictureSourceSrcset() {
            String html = "<picture><source srcset=\"https://x.com/wide.jpg 100w\" media=\"(min-width: 500px)\"><img src=\"https://x.com/fallback.jpg\"></picture>";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactlyInAnyOrder("https://x.com/wide.jpg", "https://x.com/fallback.jpg");
        }
    }

    @Nested
    @DisplayName("extractFromHtml - inline CSS url(...)")
    class InlineCss {

        @Test
        @DisplayName("style attribute with background-image: url(...) is captured")
        void inlineStyleBackgroundImage() {
            String html = "<div style=\"background-image: url('https://cdn.example.com/bg.jpg')\">x</div>";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactly("https://cdn.example.com/bg.jpg");
        }

        @Test
        @DisplayName("Unquoted url() form is captured")
        void unquotedCssUrl() {
            String html = "<div style=\"background:url(https://cdn.example.com/bg.jpg)\">x</div>";
            assertThat(ImageUrlExtractor.extractFromHtml(html))
                    .containsExactly("https://cdn.example.com/bg.jpg");
        }
    }

    @Nested
    @DisplayName("extractFromCss - standalone CSS template field")
    class StandaloneCss {

        @Test
        @DisplayName("Multiple url() references are extracted")
        void multipleCssUrls() {
            String css = ".a { background: url('https://x.com/a.png'); } .b { background-image: url(\"https://x.com/b.png\"); }";
            assertThat(ImageUrlExtractor.extractFromCss(css))
                    .containsExactly("https://x.com/a.png", "https://x.com/b.png");
        }

        @Test
        @DisplayName("Null or empty CSS returns empty set without NPE")
        void nullCssIsEmpty() {
            assertThat(ImageUrlExtractor.extractFromCss(null)).isEmpty();
            assertThat(ImageUrlExtractor.extractFromCss("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractFromJs - literal image-URL strings")
    class JsLiterals {

        @Test
        @DisplayName("Hardcoded https://...jpg literal is captured (rare but possible publisher footgun)")
        void hardcodedJsLiteral() {
            String js = "const HERO = \"https://x.com/photo.jpg\"; console.log(HERO);";
            assertThat(ImageUrlExtractor.extractFromJs(js))
                    .containsExactly("https://x.com/photo.jpg");
        }

        @Test
        @DisplayName("Quoted strings that are NOT image URLs are NOT captured (no false positives)")
        void notAnImageNotCaptured() {
            String js = "fetch('https://api.example.com/data').then(r => r.json())";
            assertThat(ImageUrlExtractor.extractFromJs(js)).isEmpty();
        }

        @Test
        @DisplayName("Template-literal interpolation is NOT captured here - Wave 2b's domain")
        void templateInterpolationIsWave2b() {
            // ${item.photoUrl} is a runtime substitution; resolving it requires
            // the materialized items[].data which only Wave 2b sees.
            String js = "el.src = `${item.photoUrl}`;";
            assertThat(ImageUrlExtractor.extractFromJs(js)).isEmpty();
        }

        @Test
        @DisplayName("Image URL with query string is captured")
        void imageWithQueryString() {
            String js = "img.src = 'https://x.com/photo.jpg?width=800&quality=85';";
            assertThat(ImageUrlExtractor.extractFromJs(js))
                    .containsExactly("https://x.com/photo.jpg?width=800&quality=85");
        }
    }

    @Nested
    @DisplayName("Defensive - null + edge cases")
    class Edge {

        @Test
        @DisplayName("Null HTML returns empty set (callers shouldn't have to guard)")
        void nullHtml() {
            assertThat(ImageUrlExtractor.extractFromHtml(null)).isEmpty();
        }

        @Test
        @DisplayName("HTML without any image references returns empty set")
        void htmlWithoutImages() {
            String html = "<div><p>Hello world</p><a href=\"https://x.com\">link</a></div>";
            assertThat(ImageUrlExtractor.extractFromHtml(html)).isEmpty();
        }
    }
}
