package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XmlAdapter class.
 *
 * XmlAdapter handles XML format data for mapping operations.
 */
@DisplayName("XmlAdapter")
class XmlAdapterTest {

    private XmlAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new XmlAdapter();
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should return true for multiple child elements")
        void shouldReturnTrueForMultipleChildElements() {
            String xml = "<root><item>1</item><item>2</item></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for single child element")
        void shouldReturnFalseForSingleChildElement() {
            String xml = "<root><item>only one</item></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should check collection based on XPath")
        void shouldCheckCollectionBasedOnXPath() {
            String xml = "<root><items><item>1</item><item>2</item><item>3</item></items></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec("xml", "//item");

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for invalid XML")
        void shouldReturnFalseForInvalidXml() {
            String xml = "not valid xml";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }
    }

    // ========================================================================
    // iterateItems tests
    // ========================================================================

    @Nested
    @DisplayName("iterateItems()")
    class IterateItemsTests {

        @Test
        @DisplayName("should iterate over child elements")
        void shouldIterateOverChildElements() {
            String xml = "<root><item>A</item><item>B</item><item>C</item></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : items) {
                count++;
            }
            assertEquals(3, count);
        }

        @Test
        @DisplayName("should iterate based on XPath")
        void shouldIterateBasedOnXPath() {
            String xml = "<root><data><item>1</item><item>2</item></data></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec("xml", "//item");

            Iterable<?> items = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : items) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should return empty list for invalid XML")
        void shouldReturnEmptyListForInvalidXml() {
            String xml = "not valid xml";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
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
        @DisplayName("should extract text content with XPath")
        void shouldExtractTextContentWithXPath() throws Exception {
            String xml = "<root><name>John</name></root>";
            Node node = parseXml(xml).getDocumentElement();

            Object result = adapter.evalScalar(node, "name/text()");

            assertEquals("John", result);
        }

        @Test
        @DisplayName("should extract attribute with XPath")
        void shouldExtractAttributeWithXPath() throws Exception {
            String xml = "<root><item id='123'>Value</item></root>";
            Node node = parseXml(xml).getDocumentElement();

            Object result = adapter.evalScalar(node, "item/@id");

            assertEquals("123", result);
        }

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "//name");

            assertNull(result);
        }

        @Test
        @DisplayName("should return empty for non-matching XPath")
        void shouldReturnEmptyForNonMatchingXPath() throws Exception {
            String xml = "<root><name>John</name></root>";
            Node node = parseXml(xml).getDocumentElement();

            Object result = adapter.evalScalar(node, "missing/text()");

            assertEquals("", result);
        }
    }

    // ========================================================================
    // evalNodes tests
    // ========================================================================

    @Nested
    @DisplayName("evalNodes()")
    class EvalNodesTests {

        @Test
        @DisplayName("should return matching nodes")
        void shouldReturnMatchingNodes() throws Exception {
            String xml = "<root><item>A</item><item>B</item></root>";
            Node node = parseXml(xml).getDocumentElement();

            Iterable<?> result = adapter.evalNodes(node, "item");

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should return empty for null context")
        void shouldReturnEmptyForNullContext() {
            Iterable<?> result = adapter.evalNodes(null, "//item");

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
        @DisplayName("should flatten simple XML structure")
        void shouldFlattenSimpleXmlStructure() {
            String xml = "<root><name>John</name><age>30</age></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("root/name"));
            assertEquals("30", result.get("root/age"));
        }

        @Test
        @DisplayName("should include attributes")
        void shouldIncludeAttributes() {
            String xml = "<root><item id='123' type='test'>Value</item></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("123", result.get("root/item@id"));
            assertEquals("test", result.get("root/item@type"));
            assertEquals("Value", result.get("root/item"));
        }

        @Test
        @DisplayName("should handle nested elements")
        void shouldHandleNestedElements() {
            String xml = "<root><user><name>John</name><email>john@test.com</email></user></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("root/user/name"));
            assertEquals("john@test.com", result.get("root/user/email"));
        }

        @Test
        @DisplayName("should return empty map for invalid XML")
        void shouldReturnEmptyMapForInvalidXml() {
            String xml = "not valid xml";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
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
            String xml = "<root><data>Content</data></root>";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof Document);
        }

        @Test
        @DisplayName("should return null for invalid XML")
        void shouldReturnNullForInvalidXml() {
            String xml = "not valid xml";
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNull(result);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }
}
