package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * RSS node - Fetches and parses RSS/Atom feeds from URLs.
 *
 * Operations:
 * - Fetch RSS/Atom feed content from a given URL
 * - Parse feed items (title, link, description, pubDate, author, categories, guid)
 * - Extract channel-level info (title, description, link, language, lastBuildDate)
 * - Detect feed format (RSS 2.0 vs Atom)
 * - Limit items to maxItems
 *
 * Usage:
 * - Monitor RSS feeds for new content in workflows
 * - Aggregate news/blog posts from multiple sources
 * - Process feed items downstream via split/transform nodes
 */
public class RssNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(RssNode.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final Core.RssConfig rssConfig;

    public RssNode(String nodeId, Core.RssConfig rssConfig) {
        super(nodeId, NodeType.RSS);
        this.rssConfig = rssConfig;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("RSS node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        String url = null;
        int maxItems = rssConfig != null ? rssConfig.maxItems() : 20;

        try {
            // Resolve the URL expression
            url = resolveExpression(
                rssConfig != null ? rssConfig.url() : null, context);

            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("RSS feed URL is required");
            }

            // SSRF protection: validate URL before fetching feed
            UrlSafetyValidator.validateUrl(url);

            // Fetch the feed content
            String feedContent = fetchFeedContent(url);

            // Parse the feed
            FeedResult feedResult = parseFeed(feedContent, maxItems);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", feedResult.items);
            result.put("channel", feedResult.channel);
            result.put("itemCount", feedResult.items.size());
            result.put("feedUrl", url);
            result.put("feedFormat", feedResult.feedFormat);
            result.put("success", true);

            // MANDATORY metadata
            result.put("node_type", "RSS");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", buildInputDataMap(url, maxItems));

            logger.info("RSS completed: nodeId={}, itemCount={}, format={}",
                nodeId, feedResult.items.size(), feedResult.feedFormat);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("RSS execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new LinkedHashMap<>();
            failOutput.put("node_type", "RSS");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(url, maxItems));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Fetch feed content from a URL using java.net.http.HttpClient.
     */
    private String fetchFeedContent(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            .header("User-Agent", "LiveContext-RSSNode/1.0")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new RuntimeException("Redirects are not followed for RSS feeds: status=" + response.statusCode());
        }

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP error fetching feed: status=" + response.statusCode());
        }

        return response.body();
    }

    /**
     * Parse RSS 2.0 or Atom feed XML content.
     * Package-private for testing.
     */
    FeedResult parseFeed(String xmlContent, int maxItems) throws Exception {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("Feed content is empty");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Security: disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xmlContent)));
        document.getDocumentElement().normalize();

        String rootTag = document.getDocumentElement().getTagName().toLowerCase(Locale.ROOT);

        if ("feed".equals(rootTag)) {
            return parseAtomFeed(document, maxItems);
        } else {
            return parseRssFeed(document, maxItems);
        }
    }

    /**
     * Parse an RSS 2.0 feed document.
     */
    private FeedResult parseRssFeed(Document document, int maxItems) {
        Map<String, Object> channel = new LinkedHashMap<>();

        // Extract channel-level info
        NodeList channelNodes = document.getElementsByTagName("channel");
        if (channelNodes.getLength() > 0) {
            Element channelElement = (Element) channelNodes.item(0);
            channel.put("title", getDirectChildText(channelElement, "title"));
            channel.put("description", getDirectChildText(channelElement, "description"));
            channel.put("link", getDirectChildText(channelElement, "link"));
            channel.put("language", getDirectChildText(channelElement, "language"));
            channel.put("lastBuildDate", getDirectChildText(channelElement, "lastBuildDate"));
        }

        // Extract items
        List<Map<String, Object>> items = new ArrayList<>();
        NodeList itemNodes = document.getElementsByTagName("item");
        int limit = Math.min(itemNodes.getLength(), maxItems);

        for (int i = 0; i < limit; i++) {
            Element itemElement = (Element) itemNodes.item(i);
            Map<String, Object> item = new LinkedHashMap<>();

            item.put("title", getDirectChildText(itemElement, "title"));
            item.put("link", getDirectChildText(itemElement, "link"));
            item.put("description", getDirectChildText(itemElement, "description"));
            item.put("pubDate", getDirectChildText(itemElement, "pubDate"));
            item.put("author", getDirectChildText(itemElement, "author"));
            item.put("guid", getDirectChildText(itemElement, "guid"));

            // Categories can be multiple
            List<String> categories = new ArrayList<>();
            NodeList catNodes = itemElement.getElementsByTagName("category");
            for (int j = 0; j < catNodes.getLength(); j++) {
                String catText = catNodes.item(j).getTextContent();
                if (catText != null && !catText.isBlank()) {
                    categories.add(catText.trim());
                }
            }
            item.put("categories", categories);

            items.add(item);
        }

        return new FeedResult(items, channel, "rss");
    }

    /**
     * Parse an Atom feed document.
     */
    private FeedResult parseAtomFeed(Document document, int maxItems) {
        Map<String, Object> channel = new LinkedHashMap<>();
        Element root = document.getDocumentElement();

        // Extract feed-level info (Atom uses <title>, <subtitle>, <link>, <updated>)
        channel.put("title", getDirectChildText(root, "title"));
        channel.put("description", getDirectChildText(root, "subtitle"));
        channel.put("language", root.getAttribute("xml:lang"));
        channel.put("lastBuildDate", getDirectChildText(root, "updated"));

        // Atom link is an attribute: <link href="..." />
        String feedLink = null;
        NodeList linkNodes = root.getElementsByTagName("link");
        for (int i = 0; i < linkNodes.getLength(); i++) {
            Element linkEl = (Element) linkNodes.item(i);
            // Skip links inside entries
            if (linkEl.getParentNode() == root) {
                String rel = linkEl.getAttribute("rel");
                if (rel.isEmpty() || "alternate".equals(rel)) {
                    feedLink = linkEl.getAttribute("href");
                    break;
                }
            }
        }
        channel.put("link", feedLink);

        // Extract entries
        List<Map<String, Object>> items = new ArrayList<>();
        NodeList entryNodes = document.getElementsByTagName("entry");
        int limit = Math.min(entryNodes.getLength(), maxItems);

        for (int i = 0; i < limit; i++) {
            Element entryElement = (Element) entryNodes.item(i);
            Map<String, Object> item = new LinkedHashMap<>();

            item.put("title", getDirectChildText(entryElement, "title"));

            // Atom link: <link href="..." />
            String entryLink = null;
            NodeList entryLinks = entryElement.getElementsByTagName("link");
            for (int j = 0; j < entryLinks.getLength(); j++) {
                Element el = (Element) entryLinks.item(j);
                if (el.getParentNode() == entryElement) {
                    String rel = el.getAttribute("rel");
                    if (rel.isEmpty() || "alternate".equals(rel)) {
                        entryLink = el.getAttribute("href");
                        break;
                    }
                }
            }
            item.put("link", entryLink);

            // Atom uses <summary> or <content> for description
            String description = getDirectChildText(entryElement, "summary");
            if (description == null || description.isBlank()) {
                description = getDirectChildText(entryElement, "content");
            }
            item.put("description", description);

            // Atom uses <updated> or <published> for date
            String pubDate = getDirectChildText(entryElement, "published");
            if (pubDate == null || pubDate.isBlank()) {
                pubDate = getDirectChildText(entryElement, "updated");
            }
            item.put("pubDate", pubDate);

            // Atom author: <author><name>...</name></author>
            String author = null;
            NodeList authorNodes = entryElement.getElementsByTagName("author");
            if (authorNodes.getLength() > 0) {
                Element authorEl = (Element) authorNodes.item(0);
                author = getDirectChildText(authorEl, "name");
            }
            item.put("author", author);

            // Atom uses <id> for guid
            item.put("guid", getDirectChildText(entryElement, "id"));

            // Atom categories: <category term="..." />
            List<String> categories = new ArrayList<>();
            NodeList catNodes = entryElement.getElementsByTagName("category");
            for (int j = 0; j < catNodes.getLength(); j++) {
                Element catEl = (Element) catNodes.item(j);
                if (catEl.getParentNode() == entryElement) {
                    String term = catEl.getAttribute("term");
                    if (term != null && !term.isBlank()) {
                        categories.add(term);
                    }
                }
            }
            item.put("categories", categories);

            items.add(item);
        }

        return new FeedResult(items, channel, "atom");
    }

    /**
     * Get the text content of a direct child element by tag name.
     * Only considers direct children (not deeper descendants).
     */
    private String getDirectChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                String text = child.getTextContent();
                return (text != null && !text.isBlank()) ? text.trim() : null;
            }
        }
        return null;
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                return result != null ? String.valueOf(result) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    private Map<String, Object> buildInputDataMap(String url, int maxItems) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("url", url);
        inputData.put("maxItems", maxItems);
        return inputData;
    }

    // Getters
    public Core.RssConfig getRssConfig() { return rssConfig; }

    /**
     * Internal feed parse result container.
     */
    static class FeedResult {
        final List<Map<String, Object>> items;
        final Map<String, Object> channel;
        final String feedFormat;

        FeedResult(List<Map<String, Object>> items, Map<String, Object> channel, String feedFormat) {
            this.items = items;
            this.channel = channel;
            this.feedFormat = feedFormat;
        }
    }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.RssConfig rssConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder rssConfig(Core.RssConfig rssConfig) { this.rssConfig = rssConfig; return this; }
        public RssNode build() { return new RssNode(nodeId, rssConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
