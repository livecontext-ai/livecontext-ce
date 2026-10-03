package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.common.web.SafeAddressResolverGroup;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

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

    /** Largest feed body read, in bytes: bounded, and far above what real feeds reach. */
    static final int MAX_FEED_BYTES = 16 * 1024 * 1024;

    /** Feeds are fetched through a pool of their own, with the limits java.net.http had (large headers). */
    private static final reactor.netty.resources.ConnectionProvider POOL =
        com.apimarketplace.common.web.OutboundHttpTransport.pool("rss-node");

    /**
     * Built once and shared by every production RssNode instance (LC-002 / LC-006, CASA
     * readiness round 3: DNS rebinding). Pre-fix, {@code fetchFeedContent} built a fresh
     * {@code java.net.http.HttpClient} per call, which validated the URL up-front with
     * {@code UrlSafetyValidator.validateUrl} and then resolved the SAME name again, through the
     * JDK's own DNS cache, when it connected - a name answering public to the check and private
     * to the connect reached the target. This client runs on Reactor Netty with
     * {@link SafeAddressResolverGroup#strict()}, the same strict predicate the URL check uses: the
     * resolver and the channel hooks judge the exact address the socket dials, so no second,
     * independent lookup happens between the check and the connect.
     */
    private static final WebClient PRODUCTION_WEB_CLIENT = buildWebClient(UrlSafetyValidator::isUnsafeAddress);

    private final Core.RssConfig rssConfig;
    private final WebClient webClient;

    public RssNode(String nodeId, Core.RssConfig rssConfig) {
        this(nodeId, rssConfig, PRODUCTION_WEB_CLIENT);
    }

    /**
     * Test seam for the connect-time pin. A local test server IS loopback, so a test that needs
     * to reach one builds an RssNode whose connect-time guard is off (mirrors
     * {@code WebClientFileDownloader}'s test constructors, for the same reason: the refusal path
     * itself is tested separately, against the real predicate, with no live network).
     */
    RssNode(String nodeId, Core.RssConfig rssConfig, WebClient webClient) {
        super(nodeId, NodeType.RSS);
        this.rssConfig = rssConfig;
        this.webClient = webClient;
    }

    /** Package-private so tests can build a client whose connect-time guard is a no-op. */
    static WebClient buildWebClient(Predicate<InetAddress> unsafeAddress) {
        reactor.netty.http.client.HttpClient httpClient = com.apimarketplace.common.web.OutboundHttpTransport.client(POOL)
            // followRedirect stays false: RssNode's own redirect handling below reports a clear
            // "redirects are not followed" error rather than silently chasing a target the SSRF
            // check never saw.
            .followRedirect(false)
            .resolver(new SafeAddressResolverGroup(unsafeAddress))
            .responseTimeout(HTTP_TIMEOUT)
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) HTTP_TIMEOUT.toMillis())
            .doOnChannelInit((observer, channel, remoteAddress) ->
                SafeAddressResolverGroup.assertRemoteAddressSafe(remoteAddress, unsafeAddress))
            .doOnConnected(connection ->
                SafeAddressResolverGroup.assertRemoteAddressSafe(
                    connection.channel().remoteAddress(), unsafeAddress));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            // WebClient buffers at most 256 KB by default, which refused ordinary feeds (a podcast
            // or full-content blog feed runs to megabytes) that java.net.http read whole.
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_FEED_BYTES))
            .build();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("RSS node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        String url = null;
        String maxItemsTemplate = deferredScalar("rss", "maxItems");
        // Until it resolves, a templated maxItems is reported as the template it is.
        Object reportedMaxItems = maxItemsTemplate != null ? maxItemsTemplate
            : (rssConfig != null ? rssConfig.maxItems() : 20);

        try {
            Core.RssConfig cfg = withDeferredScalars("rss", rssConfig, Core.RssConfig.class, context);
            int maxItems = cfg != null ? cfg.maxItems() : 20;
            reportedMaxItems = maxItemsTemplate != null
                ? ReportedParams.valueFrom(maxItemsTemplate, maxItems) : maxItems;

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
            result.put("resolved_params", buildInputDataMap(url, reportedMaxItems));

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
            failOutput.put("resolved_params", buildInputDataMap(url, reportedMaxItems));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Fetch feed content from a URL. Runs on the pinned {@link #webClient} (see its javadoc for
     * why); behaviour is otherwise unchanged from the {@code java.net.http.HttpClient} this
     * replaced - same headers, same timeout, same no-redirect refusal, same error message shapes.
     */
    private String fetchFeedContent(String url) throws Exception {
        try {
            return webClient.get()
                .uri(URI.create(url))
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                .header("User-Agent", "LiveContext-RSSNode/1.0")
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.is3xxRedirection()) {
                        return response.releaseBody().then(Mono.error(
                            new RuntimeException("Redirects are not followed for RSS feeds: status=" + status.value())));
                    }
                    if (status.isError()) {
                        return response.releaseBody().then(Mono.error(
                            new RuntimeException("HTTP error fetching feed: status=" + status.value())));
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("");
                })
                .timeout(HTTP_TIMEOUT)
                .block();
        } catch (RuntimeException e) {
            // Reactor may wrap a downstream failure; unwrap so callers see the same plain
            // RuntimeException (and message) the JDK client used to throw directly.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw e;
        }
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
        // One resolver for every field of every node: typed, JSON for a structure, never the
        // configured template in place of a value (BaseNode#resolveTemplateValue).
        return resolveTemplateString(expression, context);
    }

    private Map<String, Object> buildInputDataMap(String url, Object maxItems) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        // Masked like every other reported url: a feed url can carry its token in the
        // query string, and download_file and http_request both mask theirs.
        inputData.put("url", ReportedParams.maskUrlSecrets(url));
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
