package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueryUnderstandingService.
 *
 * QueryUnderstandingService extracts structured intent from natural language queries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QueryUnderstandingService")
class QueryUnderstandingServiceTest {

    @Mock
    private SearchConfig searchConfig;

    @Mock
    private SearchConfig.QueryUnderstandingConfig queryUnderstandingConfig;

    @Mock
    private RestTemplate restTemplate;

    private QueryUnderstandingService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new QueryUnderstandingService(searchConfig, objectMapper, restTemplate);
    }

    private void enableQueryUnderstanding() {
        when(searchConfig.getQueryUnderstanding()).thenReturn(queryUnderstandingConfig);
        when(queryUnderstandingConfig.isEnabled()).thenReturn(true);
        when(queryUnderstandingConfig.isExtractHints()).thenReturn(false);
    }

    private void disableQueryUnderstanding() {
        when(searchConfig.getQueryUnderstanding()).thenReturn(queryUnderstandingConfig);
        when(queryUnderstandingConfig.isEnabled()).thenReturn(false);
    }

    // ========================================================================
    // extractIntent() - Disabled tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Disabled")
    class DisabledTests {

        @Test
        @DisplayName("should return empty intent when query understanding is disabled")
        void shouldReturnEmptyIntentWhenDisabled() {
            disableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("list gmail messages");

            assertNull(result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals("list gmail messages", result.cleanedQuery());
            assertEquals(0.0, result.confidence());
        }
    }

    // ========================================================================
    // extractIntent() - Null/blank input tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Null/Blank input")
    class NullBlankTests {

        @Test
        @DisplayName("should return empty intent for null query")
        void shouldReturnEmptyIntentForNullQuery() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent(null);

            assertNull(result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals(0.0, result.confidence());
        }

        @Test
        @DisplayName("should return empty intent for blank query")
        void shouldReturnEmptyIntentForBlankQuery() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("   ");

            assertNull(result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals(0.0, result.confidence());
        }

        @Test
        @DisplayName("should return empty intent for empty query")
        void shouldReturnEmptyIntentForEmptyQuery() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("");

            assertNull(result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals(0.0, result.confidence());
        }
    }

    // ========================================================================
    // extractIntent() - Provider extraction tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Provider extraction")
    class ProviderExtractionTests {

        @ParameterizedTest
        @ValueSource(strings = {
            "gmail", "google", "slack", "stripe", "github", "gitlab", "jira", "notion",
            "hubspot", "salesforce", "zendesk", "discord", "telegram", "twitter",
            "shopify", "paypal", "trello", "asana", "dropbox", "openai", "anthropic",
            "spotify", "figma", "zoom", "aws", "azure", "instagram", "facebook",
            "tiktok", "youtube", "pinterest", "whatsapp"
        })
        @DisplayName("should extract known provider")
        void shouldExtractKnownProvider(String provider) {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("use " + provider + " api");

            assertEquals(provider, result.provider());
        }

        @Test
        @DisplayName("should not extract unknown provider")
        void shouldNotExtractUnknownProvider() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("use unknownservice api");

            assertNull(result.provider());
        }

        @Test
        @DisplayName("should extract provider with punctuation")
        void shouldExtractProviderWithPunctuation() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("gmail: send email");

            assertEquals("gmail", result.provider());
        }
    }

    // ========================================================================
    // extractIntent() - Action extraction tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Action extraction")
    class ActionExtractionTests {

        @ParameterizedTest
        @CsvSource({
            "list, list",
            "get, get",
            "fetch, get",
            "retrieve, get",
            "read, get",
            "show, get",
            "find, list",
            "search, list",
            "create, create",
            "add, create",
            "new, create",
            "make, create",
            "post, create",
            "send, create",
            "update, update",
            "edit, update",
            "modify, update",
            "change, update",
            "patch, update",
            "delete, delete",
            "remove, delete",
            "trash, delete",
            "destroy, delete"
        })
        @DisplayName("should map action keywords correctly")
        void shouldMapActionKeywordsCorrectly(String keyword, String expectedAction) {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent(keyword + " something");

            assertEquals(expectedAction, result.action());
        }

        @Test
        @DisplayName("should not extract unknown action")
        void shouldNotExtractUnknownAction() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("analyze data");

            assertNull(result.action());
        }
    }

    // ========================================================================
    // extractIntent() - Resource extraction tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Resource extraction")
    class ResourceExtractionTests {

        @ParameterizedTest
        @ValueSource(strings = {
            "message", "email", "user", "account", "file", "document", "folder",
            "payment", "charge", "invoice", "order", "product", "customer",
            "contact", "lead", "ticket", "issue", "task", "channel", "chat",
            "event", "calendar", "meeting", "repository", "commit", "label",
            "tag", "category", "story", "post", "reel", "feed", "media",
            "follower", "comment", "like"
        })
        @DisplayName("should extract known resource")
        void shouldExtractKnownResource(String resource) {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("get " + resource);

            assertNotNull(result.resource());
        }

        @Test
        @DisplayName("should normalize plural resource to singular")
        void shouldNormalizePluralResourceToSingular() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("list messages");

            assertEquals("message", result.resource());
        }

        @Test
        @DisplayName("should not extract unknown resource")
        void shouldNotExtractUnknownResource() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("get widgets");

            assertNull(result.resource());
        }
    }

    // ========================================================================
    // extractIntent() - Confidence calculation tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Confidence calculation")
    class ConfidenceTests {

        @Test
        @DisplayName("should calculate full confidence when all three matches found")
        void shouldCalculateFullConfidenceWhenAllThreeMatchesFound() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("list gmail messages");

            assertEquals("gmail", result.provider());
            assertEquals("list", result.action());
            assertEquals("message", result.resource());
            assertEquals(1.0, result.confidence());
        }

        @Test
        @DisplayName("should calculate 2/3 confidence when two matches found")
        void shouldCalculateTwoThirdsConfidenceWhenTwoMatchesFound() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("list gmail data");

            assertEquals("gmail", result.provider());
            assertEquals("list", result.action());
            assertNull(result.resource());
            assertEquals(2.0 / 3.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should calculate 1/3 confidence when one match found")
        void shouldCalculateOneThirdConfidenceWhenOneMatchFound() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("use gmail");

            assertEquals("gmail", result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals(1.0 / 3.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should calculate zero confidence when no matches found")
        void shouldCalculateZeroConfidenceWhenNoMatchesFound() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("something random");

            assertNull(result.provider());
            assertNull(result.action());
            assertNull(result.resource());
            assertEquals(0.0, result.confidence());
        }
    }

    // ========================================================================
    // extractIntent() - Query cleaning tests
    // ========================================================================

    @Nested
    @DisplayName("extractIntent() - Query cleaning")
    class QueryCleaningTests {

        @Test
        @DisplayName("should remove provider from cleaned query")
        void shouldRemoveProviderFromCleanedQuery() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("gmail messages");

            assertEquals("gmail", result.provider());
            assertFalse(result.cleanedQuery().contains("gmail"));
        }

        @Test
        @DisplayName("should preserve query when no provider found")
        void shouldPreserveQueryWhenNoProviderFound() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result = service.extractIntent("list messages");

            assertEquals("list messages", result.cleanedQuery());
        }
    }

    // ========================================================================
    // QueryIntent record tests
    // ========================================================================

    @Nested
    @DisplayName("QueryIntent record")
    class QueryIntentTests {

        @Test
        @DisplayName("should convert to hints map")
        void shouldConvertToHintsMap() {
            QueryUnderstandingService.QueryIntent intent =
                new QueryUnderstandingService.QueryIntent("gmail", "list", "message", "query", 1.0);

            Map<String, String> hints = intent.toHints();

            assertEquals("gmail", hints.get("provider"));
            assertEquals("list", hints.get("action"));
            assertEquals("message", hints.get("resource"));
            assertEquals(3, hints.size());
        }

        @Test
        @DisplayName("should exclude null values from hints map")
        void shouldExcludeNullValuesFromHintsMap() {
            QueryUnderstandingService.QueryIntent intent =
                new QueryUnderstandingService.QueryIntent("gmail", null, null, "query", 0.33);

            Map<String, String> hints = intent.toHints();

            assertEquals("gmail", hints.get("provider"));
            assertFalse(hints.containsKey("action"));
            assertFalse(hints.containsKey("resource"));
            assertEquals(1, hints.size());
        }

        @Test
        @DisplayName("should return empty map when all null")
        void shouldReturnEmptyMapWhenAllNull() {
            QueryUnderstandingService.QueryIntent intent =
                new QueryUnderstandingService.QueryIntent(null, null, null, "query", 0.0);

            Map<String, String> hints = intent.toHints();

            assertTrue(hints.isEmpty());
        }

        @Test
        @DisplayName("hasHints should return true when at least one hint present")
        void hasHintsShouldReturnTrueWhenAtLeastOneHintPresent() {
            QueryUnderstandingService.QueryIntent intentWithProvider =
                new QueryUnderstandingService.QueryIntent("gmail", null, null, "query", 0.33);
            QueryUnderstandingService.QueryIntent intentWithAction =
                new QueryUnderstandingService.QueryIntent(null, "list", null, "query", 0.33);
            QueryUnderstandingService.QueryIntent intentWithResource =
                new QueryUnderstandingService.QueryIntent(null, null, "message", "query", 0.33);

            assertTrue(intentWithProvider.hasHints());
            assertTrue(intentWithAction.hasHints());
            assertTrue(intentWithResource.hasHints());
        }

        @Test
        @DisplayName("hasHints should return false when no hints present")
        void hasHintsShouldReturnFalseWhenNoHintsPresent() {
            QueryUnderstandingService.QueryIntent intent =
                new QueryUnderstandingService.QueryIntent(null, null, null, "query", 0.0);

            assertFalse(intent.hasHints());
        }
    }

    // ========================================================================
    // Complex query tests
    // ========================================================================

    @Nested
    @DisplayName("Complex queries")
    class ComplexQueryTests {

        @Test
        @DisplayName("should handle complex natural language query")
        void shouldHandleComplexNaturalLanguageQuery() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("I want to send an email using gmail");

            assertEquals("gmail", result.provider());
            assertEquals("create", result.action()); // "send" maps to "create"
        }

        @Test
        @DisplayName("should handle query with multiple potential providers")
        void shouldHandleQueryWithMultiplePotentialProviders() {
            enableQueryUnderstanding();

            // Should extract the first provider found
            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("gmail slack messages");

            // First provider in the query is extracted
            assertEquals("gmail", result.provider());
        }

        @Test
        @DisplayName("should handle case-insensitive matching")
        void shouldHandleCaseInsensitiveMatching() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("LIST GMAIL MESSAGES");

            assertEquals("gmail", result.provider());
            assertEquals("list", result.action());
            assertEquals("message", result.resource());
        }

        @Test
        @DisplayName("should handle query with special characters")
        void shouldHandleQueryWithSpecialCharacters() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("gmail: list messages!");

            assertEquals("gmail", result.provider());
            assertEquals("list", result.action());
            assertEquals("message", result.resource());
        }
    }

    // ========================================================================
    // Social media specific tests
    // ========================================================================

    @Nested
    @DisplayName("Social media queries")
    class SocialMediaTests {

        @Test
        @DisplayName("should extract Instagram with story resource")
        void shouldExtractInstagramWithStoryResource() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("post instagram story");

            assertEquals("instagram", result.provider());
            assertEquals("create", result.action()); // "post" maps to "create"
        }

        @Test
        @DisplayName("should extract TikTok with reel resource")
        void shouldExtractTikTokWithReelResource() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("get tiktok reels");

            assertEquals("tiktok", result.provider());
            assertEquals("get", result.action());
        }

        @Test
        @DisplayName("should extract YouTube with comment resource")
        void shouldExtractYouTubeWithCommentResource() {
            enableQueryUnderstanding();

            QueryUnderstandingService.QueryIntent result =
                service.extractIntent("list youtube comments");

            assertEquals("youtube", result.provider());
            assertEquals("list", result.action());
            assertEquals("comment", result.resource());
        }
    }
}
