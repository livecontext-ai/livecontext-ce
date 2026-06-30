package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for understanding and enriching search queries.
 *
 * This service extracts structured information from natural language queries:
 * - Provider: e.g., "gmail", "slack", "stripe"
 * - Action: e.g., "list", "get", "create", "send", "delete"
 * - Resource: e.g., "messages", "users", "payments"
 *
 * This allows for more precise filtering and improves search relevance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryUnderstandingService {

    private final SearchConfig searchConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.mapping.deepinfra.token:}")
    private String deepinfraToken;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    private static final String DEEPINFRA_URL = "https://api.deepinfra.com/v1/openai/chat/completions";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    // Known providers for rule-based extraction
    private static final Set<String> KNOWN_PROVIDERS = Set.of(
        "gmail", "google", "slack", "stripe", "github", "gitlab", "jira", "notion",
        "airtable", "hubspot", "salesforce", "zendesk", "intercom", "mailchimp",
        "sendgrid", "twilio", "discord", "telegram", "twitter", "linkedin",
        "shopify", "woocommerce", "paypal", "square", "quickbooks", "trello",
        "asana", "monday", "clickup", "dropbox", "box", "openai", "anthropic",
        "cohere", "mistral", "groq", "perplexity", "replicate", "elevenlabs",
        "spotify", "figma", "zoom", "calendly", "docusign", "aws", "azure", "gcp",
        // Social media platforms
        "instagram", "facebook", "tiktok", "youtube", "pinterest", "snapchat", "whatsapp"
    );

    // Action keywords mapping
    private static final Map<String, String> ACTION_KEYWORDS = Map.ofEntries(
        Map.entry("list", "list"),
        Map.entry("get", "get"),
        Map.entry("fetch", "get"),
        Map.entry("retrieve", "get"),
        Map.entry("read", "get"),
        Map.entry("show", "get"),
        Map.entry("find", "list"),
        Map.entry("search", "list"),
        Map.entry("create", "create"),
        Map.entry("add", "create"),
        Map.entry("new", "create"),
        Map.entry("make", "create"),
        Map.entry("post", "create"),
        Map.entry("send", "create"),
        Map.entry("update", "update"),
        Map.entry("edit", "update"),
        Map.entry("modify", "update"),
        Map.entry("change", "update"),
        Map.entry("patch", "update"),
        Map.entry("delete", "delete"),
        Map.entry("remove", "delete"),
        Map.entry("trash", "delete"),
        Map.entry("destroy", "delete")
    );

    // Resource keywords (common API resources)
    private static final Set<String> RESOURCE_KEYWORDS = Set.of(
        "message", "messages", "email", "emails", "mail",
        "user", "users", "account", "accounts", "profile",
        "file", "files", "document", "documents", "folder", "folders",
        "payment", "payments", "charge", "charges", "invoice", "invoices",
        "order", "orders", "product", "products", "item", "items",
        "customer", "customers", "contact", "contacts", "lead", "leads",
        "ticket", "tickets", "issue", "issues", "task", "tasks",
        "channel", "channels", "chat", "conversation", "conversations",
        "event", "events", "calendar", "meeting", "meetings",
        "repository", "repositories", "repo", "commit", "commits",
        "label", "labels", "tag", "tags", "category", "categories",
        // Social media resources
        "story", "stories", "post", "posts", "reel", "reels", "feed", "media",
        "follower", "followers", "following", "comment", "comments", "like", "likes"
    );

    /**
     * Represents the extracted understanding of a query.
     */
    public record QueryIntent(
        String provider,
        String action,
        String resource,
        String cleanedQuery,
        double confidence
    ) {
        public Map<String, String> toHints() {
            Map<String, String> hints = new HashMap<>();
            if (provider != null) hints.put("provider", provider);
            if (action != null) hints.put("action", action);
            if (resource != null) hints.put("resource", resource);
            return hints;
        }

        public boolean hasHints() {
            return provider != null || action != null || resource != null;
        }
    }

    /**
     * Extract intent from a search query.
     *
     * @param query The raw search query
     * @return QueryIntent with extracted provider, action, resource
     */
    public QueryIntent extractIntent(String query) {
        if (!searchConfig.getQueryUnderstanding().isEnabled()) {
            log.debug("Query understanding disabled");
            return new QueryIntent(null, null, null, query, 0.0);
        }

        if (query == null || query.isBlank()) {
            return new QueryIntent(null, null, null, query, 0.0);
        }

        // First try rule-based extraction (fast, no API call)
        QueryIntent ruleBasedIntent = extractWithRules(query);

        // If rule-based extraction found good hints, use them
        if (ruleBasedIntent.confidence >= 0.7) {
            log.info("Query understanding (rule-based): provider={}, action={}, resource={}, confidence={}",
                    ruleBasedIntent.provider, ruleBasedIntent.action, ruleBasedIntent.resource, ruleBasedIntent.confidence);
            return ruleBasedIntent;
        }

        // Otherwise, try LLM-based extraction for complex queries
        if (searchConfig.getQueryUnderstanding().isExtractHints()) {
            try {
                QueryIntent llmIntent = extractWithLLM(query);
                if (llmIntent.confidence > ruleBasedIntent.confidence) {
                    log.info("Query understanding (LLM): provider={}, action={}, resource={}, confidence={}",
                            llmIntent.provider, llmIntent.action, llmIntent.resource, llmIntent.confidence);
                    return llmIntent;
                }
            } catch (Exception e) {
                log.warn("LLM intent extraction failed: {}", e.getMessage());
            }
        }

        return ruleBasedIntent;
    }

    /**
     * Extract intent using rule-based pattern matching.
     * Fast and doesn't require API calls.
     */
    private QueryIntent extractWithRules(String query) {
        String lowerQuery = query.toLowerCase().trim();
        String[] words = lowerQuery.split("\\s+");

        String provider = null;
        String action = null;
        String resource = null;
        int matches = 0;

        // Extract provider
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z0-9]", "");
            if (KNOWN_PROVIDERS.contains(cleanWord)) {
                provider = cleanWord;
                matches++;
                break;
            }
        }

        // Extract action
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z]", "");
            if (ACTION_KEYWORDS.containsKey(cleanWord)) {
                action = ACTION_KEYWORDS.get(cleanWord);
                matches++;
                break;
            }
        }

        // Extract resource
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-z]", "");
            // Check singular and plural forms
            if (RESOURCE_KEYWORDS.contains(cleanWord)) {
                // Normalize to singular form
                resource = cleanWord.endsWith("s") && RESOURCE_KEYWORDS.contains(cleanWord.substring(0, cleanWord.length() - 1))
                    ? cleanWord.substring(0, cleanWord.length() - 1)
                    : cleanWord;
                matches++;
                break;
            }
        }

        // Calculate confidence based on number of matches
        double confidence = matches / 3.0;

        // Clean the query by removing extracted terms for better semantic search
        String cleanedQuery = lowerQuery;
        if (provider != null) {
            cleanedQuery = cleanedQuery.replace(provider, "").trim();
        }

        return new QueryIntent(provider, action, resource, cleanedQuery, confidence);
    }

    /**
     * Extract intent using LLM for more complex queries.
     */
    private QueryIntent extractWithLLM(String query) {
        String apiUrl = deepinfraToken != null && !deepinfraToken.isEmpty() ? DEEPINFRA_URL : OPENAI_URL;
        String apiKey = deepinfraToken != null && !deepinfraToken.isEmpty() ? deepinfraToken : openaiApiKey;
        String model = deepinfraToken != null && !deepinfraToken.isEmpty() ? "Qwen/Qwen2.5-72B-Instruct" : "gpt-4o-mini";

        if (apiKey == null || apiKey.isEmpty()) {
            return new QueryIntent(null, null, null, query, 0.0);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String systemPrompt = """
                You are an API tool search query analyzer. Extract the following from the user's query:
                - provider: The API service name (e.g., gmail, slack, stripe). Use lowercase.
                - action: The operation type. Must be one of: list, get, create, update, delete
                - resource: The resource/entity being operated on (e.g., messages, users, payments). Use singular form.

                Return a JSON object with these fields. Use null for any field you cannot determine with high confidence.

                Example:
                Query: "send an email using gmail"
                Output: {"provider": "gmail", "action": "create", "resource": "message", "confidence": 0.9}
                """;

            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", "Query: \"" + query + "\"")
                ),
                "temperature", 0.0,
                "max_tokens", 100,
                "response_format", Map.of("type", "json_object")
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                request,
                JsonNode.class
            );

            if (response.getBody() != null) {
                String content = response.getBody()
                    .path("choices").path(0)
                    .path("message").path("content")
                    .asText();

                JsonNode result = objectMapper.readTree(content);

                String provider = getJsonString(result, "provider");
                String action = getJsonString(result, "action");
                String resource = getJsonString(result, "resource");
                double confidence = result.has("confidence") ? result.get("confidence").asDouble() : 0.8;

                return new QueryIntent(provider, action, resource, query, confidence);
            }

        } catch (Exception e) {
            log.debug("LLM intent extraction error: {}", e.getMessage());
        }

        return new QueryIntent(null, null, null, query, 0.0);
    }

    private String getJsonString(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            String value = node.get(field).asText();
            return value.isEmpty() ? null : value.toLowerCase();
        }
        return null;
    }
}
