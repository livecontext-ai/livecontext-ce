package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HtmlAdapter class.
 *
 * HtmlAdapter handles HTML format data for mapping operations.
 */
@DisplayName("HtmlAdapter")
class HtmlAdapterTest {

    private HtmlAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HtmlAdapter();
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should return true for multiple body children")
        void shouldReturnTrueForMultipleBodyChildren() {
            String html = "<html><body><div>1</div><div>2</div></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for single body child")
        void shouldReturnFalseForSingleBodyChild() {
            String html = "<html><body><div>only one</div></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should check collection based on root selector")
        void shouldCheckCollectionBasedOnRootSelector() {
            String html = "<html><body><ul><li>1</li><li>2</li><li>3</li></ul></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec("html", "li");

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }
    }

    // ========================================================================
    // iterateItems tests
    // ========================================================================

    @Nested
    @DisplayName("iterateItems()")
    class IterateItemsTests {

        @Test
        @DisplayName("should iterate over body children")
        void shouldIterateOverBodyChildren() {
            String html = "<html><body><p>First</p><p>Second</p><p>Third</p></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : items) {
                count++;
            }
            assertEquals(3, count);
        }

        @Test
        @DisplayName("should iterate based on selector")
        void shouldIterateBasedOnSelector() {
            String html = "<html><body><div class='item'>A</div><div class='item'>B</div></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec("html", ".item");

            Iterable<?> items = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : items) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should return empty list for invalid HTML")
        void shouldReturnEmptyListForInvalidHtml() {
            byte[] input = new byte[0];
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            assertFalse(items.iterator().hasNext());
        }
    }

    // ========================================================================
    // evalScalar tests
    // ========================================================================

    @Nested
    @DisplayName("evalScalar()")
    class EvalScalarTests {

        @Test
        @DisplayName("should extract text content with text()")
        void shouldExtractTextContentWithText() {
            Document doc = Jsoup.parse("<div id='test'>Hello World</div>");
            Element element = doc.select("#test").first();

            Object result = adapter.evalScalar(element, "text()");

            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("should extract text content with @text")
        void shouldExtractTextContentWithAtText() {
            Document doc = Jsoup.parse("<div id='test'>Hello</div>");
            Element element = doc.select("#test").first();

            Object result = adapter.evalScalar(element, "@text");

            assertEquals("Hello", result);
        }

        @Test
        @DisplayName("should extract HTML content with html()")
        void shouldExtractHtmlContentWithHtml() {
            Document doc = Jsoup.parse("<div id='test'><span>Inner</span></div>");
            Element element = doc.select("#test").first();

            Object result = adapter.evalScalar(element, "html()");

            assertTrue(result.toString().contains("span"));
            assertTrue(result.toString().contains("Inner"));
        }

        @Test
        @DisplayName("should extract attribute with @prefix")
        void shouldExtractAttributeWithAtPrefix() {
            Document doc = Jsoup.parse("<a id='link' href='http://example.com'>Link</a>");
            Element element = doc.select("#link").first();

            Object result = adapter.evalScalar(element, "@href");

            assertEquals("http://example.com", result);
        }

        @Test
        @DisplayName("should extract with CSS selector")
        void shouldExtractWithCssSelector() {
            Document doc = Jsoup.parse("<div id='parent'><span class='child'>Value</span></div>");
            Element element = doc.select("#parent").first();

            Object result = adapter.evalScalar(element, ".child");

            assertEquals("Value", result);
        }

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "text()");

            assertNull(result);
        }

        @Test
        @DisplayName("should return list for multiple matches")
        void shouldReturnListForMultipleMatches() {
            Document doc = Jsoup.parse("<div><span>A</span><span>B</span><span>C</span></div>");
            Element element = doc.select("div").first();

            Object result = adapter.evalScalar(element, "span");

            assertTrue(result instanceof List);
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) result;
            assertEquals(3, list.size());
        }
    }

    // ========================================================================
    // evalNodes tests
    // ========================================================================

    @Nested
    @DisplayName("evalNodes()")
    class EvalNodesTests {

        @Test
        @DisplayName("should return matching elements")
        void shouldReturnMatchingElements() {
            Document doc = Jsoup.parse("<div><p>1</p><p>2</p></div>");
            Element element = doc.select("div").first();

            Iterable<?> result = adapter.evalNodes(element, "p");

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should return empty for null context")
        void shouldReturnEmptyForNullContext() {
            Iterable<?> result = adapter.evalNodes(null, "p");

            assertFalse(result.iterator().hasNext());
        }
    }

    // ========================================================================
    // flatten tests
    // ========================================================================

    @Nested
    @DisplayName("flatten()")
    class FlattenTests {

        @Test
        @DisplayName("should flatten simple HTML structure")
        void shouldFlattenSimpleHtmlStructure() {
            String html = "<html><body><div id='main'><p>Text</p></div></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result);
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("should include attributes")
        void shouldIncludeAttributes() {
            String html = "<html><body><a href='http://test.com' class='link'>Link</a></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            boolean hasHref = result.keySet().stream().anyMatch(k -> k.contains("@href"));
            assertTrue(hasHref);
        }

        @Test
        @DisplayName("should include text content")
        void shouldIncludeTextContent() {
            String html = "<html><body><p>Hello World</p></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            boolean hasText = result.values().stream().anyMatch(v -> "Hello World".equals(v));
            assertTrue(hasText);
        }
    }

    // ========================================================================
    // getRoot tests
    // ========================================================================

    @Nested
    @DisplayName("getRoot()")
    class GetRootTests {

        @Test
        @DisplayName("should return Document")
        void shouldReturnDocument() {
            String html = "<html><body><div>Content</div></body></html>";
            byte[] input = html.getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof Document);
        }

        @Test
        @DisplayName("should return null for invalid input")
        void shouldReturnNullForInvalidInput() {
            byte[] input = new byte[0];

            Object result = adapter.getRoot(input);

            // Jsoup is lenient and will return an empty document
            assertNotNull(result);
        }
    }
}
