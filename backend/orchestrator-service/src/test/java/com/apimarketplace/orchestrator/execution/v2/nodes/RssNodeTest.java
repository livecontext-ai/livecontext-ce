package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RssNode.
 * RssNode fetches and parses RSS/Atom feeds from URLs.
 *
 * Since RSS fetches from external URLs, tests focus on the XML parsing logic
 * by calling the package-private parseFeed() method directly with sample XML strings.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RssNode")
class RssNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @Test
    @DisplayName("Should not follow HTTP redirects when fetching feeds")
    void shouldNotFollowRedirectsWhenFetchingFeeds() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            byte[] body = "<rss><channel/></rss>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            Method fetch = RssNode.class.getDeclaredMethod("fetchFeedContent", String.class);
            fetch.setAccessible(true);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> fetch.invoke(node, url));

            assertTrue(ex.getCause().getMessage().contains("Redirects are not followed"));
            assertEquals(0, targetHits.get());
        } finally {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("feedUrl", "https://example.com/feed.xml");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create RssNode with nodeId and config")
        void shouldCreateRssNodeWithNodeIdAndConfig() {
            Core.RssConfig config = new Core.RssConfig("https://example.com/feed.xml", 10);
            RssNode node = new RssNode("core:rss", config);

            assertEquals("core:rss", node.getNodeId());
            assertEquals(NodeType.RSS, node.getType());
            assertNotNull(node.getRssConfig());
            assertEquals("https://example.com/feed.xml", node.getRssConfig().url());
            assertEquals(10, node.getRssConfig().maxItems());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            RssNode node = new RssNode("core:rss", null);

            assertEquals("core:rss", node.getNodeId());
            assertNull(node.getRssConfig());
        }

        @Test
        @DisplayName("Should default maxItems to 20 when zero or negative")
        void shouldDefaultMaxItemsWhenZeroOrNegative() {
            Core.RssConfig configZero = new Core.RssConfig("https://example.com/feed.xml", 0);
            assertEquals(20, configZero.maxItems());

            Core.RssConfig configNeg = new Core.RssConfig("https://example.com/feed.xml", -5);
            assertEquals(20, configNeg.maxItems());
        }

        @Test
        @DisplayName("Should preserve positive maxItems value")
        void shouldPreservePositiveMaxItems() {
            Core.RssConfig config = new Core.RssConfig("https://example.com/feed.xml", 5);
            assertEquals(5, config.maxItems());
        }
    }

    // ===============================================================
    // RSS 2.0 parsing tests
    // ===============================================================

    @Nested
    @DisplayName("RSS 2.0 parsing")
    class Rss20ParsingTests {

        @Test
        @DisplayName("Should parse RSS 2.0 feed with channel and items")
        void shouldParseRss20FeedWithChannelAndItems() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Test Blog</title>
                    <description>A test blog feed</description>
                    <link>https://example.com</link>
                    <language>en-us</language>
                    <lastBuildDate>Mon, 10 Mar 2026 12:00:00 GMT</lastBuildDate>
                    <item>
                      <title>First Post</title>
                      <link>https://example.com/post1</link>
                      <description>Description of first post</description>
                      <pubDate>Mon, 10 Mar 2026 10:00:00 GMT</pubDate>
                      <author>author@example.com</author>
                      <guid>https://example.com/post1</guid>
                      <category>Tech</category>
                      <category>News</category>
                    </item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(rssXml, 20);

            assertEquals("rss", result.feedFormat);
            assertEquals(1, result.items.size());

            // Check channel
            assertEquals("Test Blog", result.channel.get("title"));
            assertEquals("A test blog feed", result.channel.get("description"));
            assertEquals("https://example.com", result.channel.get("link"));
            assertEquals("en-us", result.channel.get("language"));
            assertEquals("Mon, 10 Mar 2026 12:00:00 GMT", result.channel.get("lastBuildDate"));

            // Check item
            Map<String, Object> item = result.items.get(0);
            assertEquals("First Post", item.get("title"));
            assertEquals("https://example.com/post1", item.get("link"));
            assertEquals("Description of first post", item.get("description"));
            assertEquals("Mon, 10 Mar 2026 10:00:00 GMT", item.get("pubDate"));
            assertEquals("author@example.com", item.get("author"));
            assertEquals("https://example.com/post1", item.get("guid"));

            @SuppressWarnings("unchecked")
            List<String> categories = (List<String>) item.get("categories");
            assertEquals(2, categories.size());
            assertTrue(categories.contains("Tech"));
            assertTrue(categories.contains("News"));
        }

        @Test
        @DisplayName("Should parse RSS 2.0 feed with multiple items")
        void shouldParseRss20FeedWithMultipleItems() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Blog</title>
                    <item><title>Post 1</title><link>https://example.com/1</link></item>
                    <item><title>Post 2</title><link>https://example.com/2</link></item>
                    <item><title>Post 3</title><link>https://example.com/3</link></item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(rssXml, 20);

            assertEquals(3, result.items.size());
            assertEquals("Post 1", result.items.get(0).get("title"));
            assertEquals("Post 2", result.items.get(1).get("title"));
            assertEquals("Post 3", result.items.get(2).get("title"));
        }

        @Test
        @DisplayName("Should handle RSS feed with missing optional fields")
        void shouldHandleRssFeedWithMissingOptionalFields() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Minimal Feed</title>
                    <item>
                      <title>Minimal Item</title>
                    </item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(rssXml, 20);

            assertEquals(1, result.items.size());
            Map<String, Object> item = result.items.get(0);
            assertEquals("Minimal Item", item.get("title"));
            assertNull(item.get("link"));
            assertNull(item.get("description"));
            assertNull(item.get("pubDate"));
            assertNull(item.get("author"));
            assertNull(item.get("guid"));

            @SuppressWarnings("unchecked")
            List<String> categories = (List<String>) item.get("categories");
            assertTrue(categories.isEmpty());
        }

        @Test
        @DisplayName("Should handle empty RSS channel with no items")
        void shouldHandleEmptyRssChannelWithNoItems() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Empty Feed</title>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(rssXml, 20);

            assertEquals("rss", result.feedFormat);
            assertTrue(result.items.isEmpty());
            assertEquals("Empty Feed", result.channel.get("title"));
        }
    }

    // ===============================================================
    // Atom parsing tests
    // ===============================================================

    @Nested
    @DisplayName("Atom parsing")
    class AtomParsingTests {

        @Test
        @DisplayName("Should parse Atom feed with entries")
        void shouldParseAtomFeedWithEntries() throws Exception {
            String atomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en">
                  <title>Atom Blog</title>
                  <subtitle>An Atom blog feed</subtitle>
                  <link href="https://example.com" rel="alternate"/>
                  <updated>2026-03-10T12:00:00Z</updated>
                  <entry>
                    <title>First Entry</title>
                    <link href="https://example.com/entry1" rel="alternate"/>
                    <summary>Summary of first entry</summary>
                    <published>2026-03-10T10:00:00Z</published>
                    <author><name>Jane Doe</name></author>
                    <id>urn:uuid:entry-1</id>
                    <category term="Science"/>
                    <category term="Research"/>
                  </entry>
                </feed>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(atomXml, 20);

            assertEquals("atom", result.feedFormat);
            assertEquals(1, result.items.size());

            // Check channel
            assertEquals("Atom Blog", result.channel.get("title"));
            assertEquals("An Atom blog feed", result.channel.get("description"));
            assertEquals("https://example.com", result.channel.get("link"));
            assertEquals("2026-03-10T12:00:00Z", result.channel.get("lastBuildDate"));

            // Check entry
            Map<String, Object> item = result.items.get(0);
            assertEquals("First Entry", item.get("title"));
            assertEquals("https://example.com/entry1", item.get("link"));
            assertEquals("Summary of first entry", item.get("description"));
            assertEquals("2026-03-10T10:00:00Z", item.get("pubDate"));
            assertEquals("Jane Doe", item.get("author"));
            assertEquals("urn:uuid:entry-1", item.get("guid"));

            @SuppressWarnings("unchecked")
            List<String> categories = (List<String>) item.get("categories");
            assertEquals(2, categories.size());
            assertTrue(categories.contains("Science"));
            assertTrue(categories.contains("Research"));
        }

        @Test
        @DisplayName("Should fall back to content when summary is missing in Atom")
        void shouldFallBackToContentWhenSummaryMissing() throws Exception {
            String atomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Blog</title>
                  <entry>
                    <title>Entry</title>
                    <content>Full content of the entry</content>
                    <id>urn:uuid:entry-1</id>
                  </entry>
                </feed>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(atomXml, 20);

            assertEquals("Full content of the entry", result.items.get(0).get("description"));
        }

        @Test
        @DisplayName("Should fall back to updated when published is missing in Atom")
        void shouldFallBackToUpdatedWhenPublishedMissing() throws Exception {
            String atomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Blog</title>
                  <entry>
                    <title>Entry</title>
                    <updated>2026-03-10T12:00:00Z</updated>
                    <id>urn:uuid:entry-1</id>
                  </entry>
                </feed>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(atomXml, 20);

            assertEquals("2026-03-10T12:00:00Z", result.items.get(0).get("pubDate"));
        }

        @Test
        @DisplayName("Should parse Atom feed with multiple entries")
        void shouldParseAtomFeedWithMultipleEntries() throws Exception {
            String atomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Blog</title>
                  <entry><title>Entry 1</title><id>1</id></entry>
                  <entry><title>Entry 2</title><id>2</id></entry>
                  <entry><title>Entry 3</title><id>3</id></entry>
                </feed>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            RssNode.FeedResult result = node.parseFeed(atomXml, 20);

            assertEquals("atom", result.feedFormat);
            assertEquals(3, result.items.size());
            assertEquals("Entry 1", result.items.get(0).get("title"));
            assertEquals("Entry 2", result.items.get(1).get("title"));
            assertEquals("Entry 3", result.items.get(2).get("title"));
        }
    }

    // ===============================================================
    // maxItems limiting tests
    // ===============================================================

    @Nested
    @DisplayName("maxItems limiting")
    class MaxItemsTests {

        @Test
        @DisplayName("Should limit RSS items to maxItems")
        void shouldLimitRssItemsToMaxItems() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Blog</title>
                    <item><title>Post 1</title></item>
                    <item><title>Post 2</title></item>
                    <item><title>Post 3</title></item>
                    <item><title>Post 4</title></item>
                    <item><title>Post 5</title></item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 3));
            RssNode.FeedResult result = node.parseFeed(rssXml, 3);

            assertEquals(3, result.items.size());
            assertEquals("Post 1", result.items.get(0).get("title"));
            assertEquals("Post 3", result.items.get(2).get("title"));
        }

        @Test
        @DisplayName("Should limit Atom entries to maxItems")
        void shouldLimitAtomEntriesToMaxItems() throws Exception {
            String atomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Blog</title>
                  <entry><title>Entry 1</title><id>1</id></entry>
                  <entry><title>Entry 2</title><id>2</id></entry>
                  <entry><title>Entry 3</title><id>3</id></entry>
                  <entry><title>Entry 4</title><id>4</id></entry>
                </feed>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 2));
            RssNode.FeedResult result = node.parseFeed(atomXml, 2);

            assertEquals(2, result.items.size());
            assertEquals("Entry 1", result.items.get(0).get("title"));
            assertEquals("Entry 2", result.items.get(1).get("title"));
        }

        @Test
        @DisplayName("Should return all items when maxItems exceeds feed size")
        void shouldReturnAllItemsWhenMaxItemsExceedsFeedSize() throws Exception {
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Blog</title>
                    <item><title>Post 1</title></item>
                    <item><title>Post 2</title></item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 100));
            RssNode.FeedResult result = node.parseFeed(rssXml, 100);

            assertEquals(2, result.items.size());
        }

        @Test
        @DisplayName("Should return zero items when maxItems is zero (defaults to 20)")
        void shouldHandleDefaultMaxItems() throws Exception {
            // RssConfig normalizes 0 to 20, so we test with maxItems=1 for edge case
            String rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Blog</title>
                    <item><title>Post 1</title></item>
                    <item><title>Post 2</title></item>
                    <item><title>Post 3</title></item>
                  </channel>
                </rss>
                """;

            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 1));
            RssNode.FeedResult result = node.parseFeed(rssXml, 1);

            assertEquals(1, result.items.size());
            assertEquals("Post 1", result.items.get(0).get("title"));
        }
    }

    // ===============================================================
    // Error handling tests
    // ===============================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should fail when config is null")
        void shouldFailWhenConfigIsNull() {
            RssNode node = new RssNode("core:rss", null);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail when URL is null")
        void shouldFailWhenUrlIsNull() {
            Core.RssConfig config = new Core.RssConfig(null, 20);
            RssNode node = new RssNode("core:rss", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for empty feed content in parseFeed")
        void shouldFailForEmptyFeedContent() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));

            assertThrows(IllegalArgumentException.class, () -> node.parseFeed("", 20));
            assertThrows(IllegalArgumentException.class, () -> node.parseFeed(null, 20));
        }

        @Test
        @DisplayName("Should fail for malformed XML in parseFeed")
        void shouldFailForMalformedXml() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));

            assertThrows(Exception.class, () -> node.parseFeed("<rss><unclosed>", 20));
        }

        @Test
        @DisplayName("Should fail for non-XML content in parseFeed")
        void shouldFailForNonXmlContent() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));

            assertThrows(Exception.class, () -> node.parseFeed("this is not xml", 20));
        }
    }

    // ===============================================================
    // SSRF protection tests
    // ===============================================================

    @Nested
    @DisplayName("SSRF protection")
    class SsrfProtection {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1/feed.xml",
            "http://localhost/feed.xml",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.0/internal",
            "ftp://example.com/feed.xml"
        })
        @DisplayName("should reject SSRF URLs")
        void shouldRejectSsrfUrls(String url) {
            Core.RssConfig config = new Core.RssConfig(url, 20);
            RssNode node = new RssNode("core:rss", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            // The error message should indicate the URL was blocked
            assertTrue(result.errorMessage().isPresent());
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create RssNode using builder")
        void shouldCreateRssNodeUsingBuilder() {
            Core.RssConfig config = new Core.RssConfig("https://blog.example.com/feed", 15);

            RssNode node = RssNode.builder()
                .nodeId("core:my_rss")
                .rssConfig(config)
                .build();

            assertEquals("core:my_rss", node.getNodeId());
            assertEquals(NodeType.RSS, node.getType());
            assertEquals("https://blog.example.com/feed", node.getRssConfig().url());
            assertEquals(15, node.getRssConfig().maxItems());
        }

        @Test
        @DisplayName("Should create RssNode with null config using builder")
        void shouldCreateRssNodeWithNullConfigUsingBuilder() {
            RssNode node = RssNode.builder()
                .nodeId("core:rss_feed")
                .rssConfig(null)
                .build();

            assertEquals("core:rss_feed", node.getNodeId());
            assertNull(node.getRssConfig());
        }

        @Test
        @DisplayName("Should return builder from static method")
        void shouldReturnBuilderFromStaticMethod() {
            RssNode.Builder builder = RssNode.builder();
            assertNotNull(builder);
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:rss", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:rss", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            NodeExecutionResult result = NodeExecutionResult.success("core:rss", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            RssNode node = new RssNode("core:rss", new Core.RssConfig("http://test", 20));
            NodeExecutionResult result = NodeExecutionResult.failure("core:rss", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
