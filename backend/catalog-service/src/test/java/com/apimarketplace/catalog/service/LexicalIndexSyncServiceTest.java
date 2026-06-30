package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.LexicalIndexSyncService.EnrichedSynthesisData;
import com.apimarketplace.catalog.service.LexicalIndexSyncService.SyncData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LexicalIndexSyncServiceTest {

    @Mock
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private LexicalIndexSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new LexicalIndexSyncService(namedJdbcTemplate, jdbcTemplate);
    }

    @Nested
    @DisplayName("SyncData Builder Tests")
    class SyncDataBuilderTests {

        @Test
        @DisplayName("should build sync data with all fields")
        void shouldBuildWithAllFields() {
            SyncData data = SyncData.builder()
                .toolName("Get Stories")
                .provider("social/instagram")
                .resource("user_stories")
                .action("list")
                .endpoint("/instagram/stories/{user_id}")
                .paramsRequired(List.of("user_id"))
                .paramsOptional(List.of("limit", "cursor"))
                .paramExamples(List.of("12345"))
                .summary("Basic summary")
                .summaryExtended("Extended summary with more details")
                .keywords("legacy keywords")
                .keywordsPrimary(List.of("get stories", "fetch stories"))
                .keywordsSynonyms(List.of("ephemeral content", "temporary posts"))
                .keywordsParams(List.of("user_id:target user"))
                .useCases(List.of("social monitoring", "content backup"))
                .category("Social Media")
                .subcategory("Instagram")
                .build();

            assertThat(data.toolName()).isEqualTo("Get Stories");
            assertThat(data.provider()).isEqualTo("social/instagram");
            assertThat(data.resource()).isEqualTo("user_stories");
            assertThat(data.action()).isEqualTo("list");
            assertThat(data.endpoint()).isEqualTo("/instagram/stories/{user_id}");
            assertThat(data.paramsRequired()).containsExactly("user_id");
            assertThat(data.paramsOptional()).containsExactly("limit", "cursor");
            assertThat(data.category()).isEqualTo("Social Media");
            assertThat(data.subcategory()).isEqualTo("Instagram");
        }
    }

    @Nested
    @DisplayName("EnrichedSynthesisData Builder Tests")
    class EnrichedSynthesisDataBuilderTests {

        @Test
        @DisplayName("should build enriched data with all fields")
        void shouldBuildWithAllFields() {
            EnrichedSynthesisData data = EnrichedSynthesisData.builder()
                .toolName("Get Stories")
                .provider("social/instagram")
                .resource("user_stories")
                .action("list")
                .endpoint("/instagram/stories/{user_id}")
                .paramsRequired(List.of("user_id"))
                .paramsOptional(List.of("limit", "cursor"))
                .paramExamples(List.of("12345"))
                .summary("Basic summary")
                .summaryExtended("Extended summary with more details")
                .keywords("legacy keywords")
                .keywordsPrimary(List.of("get stories", "fetch stories"))
                .keywordsSynonyms(List.of("ephemeral content", "temporary posts"))
                .keywordsParams(List.of("user_id:target user"))
                .useCases(List.of("social monitoring", "content backup"))
                .build();

            assertThat(data.provider()).isEqualTo("social/instagram");
            assertThat(data.resource()).isEqualTo("user_stories");
            assertThat(data.action()).isEqualTo("list");
            assertThat(data.keywordsPrimary()).containsExactly("get stories", "fetch stories");
            assertThat(data.useCases()).containsExactly("social monitoring", "content backup");
        }

        @Test
        @DisplayName("should build with default empty lists")
        void shouldBuildWithDefaults() {
            EnrichedSynthesisData data = EnrichedSynthesisData.builder()
                .provider("test/provider")
                .resource("test_resource")
                .action("get")
                .build();

            assertThat(data.provider()).isEqualTo("test/provider");
            assertThat(data.paramsRequired()).isEmpty();
            assertThat(data.keywordsPrimary()).isEmpty();
            assertThat(data.useCases()).isEmpty();
        }
    }

    @Nested
    @DisplayName("sync() Tests")
    class SyncTests {

        @Test
        @DisplayName("should execute upsert with all fields")
        void shouldExecuteUpsertWithAllFields() {
            UUID toolId = UUID.randomUUID();
            SyncData data = createFullSyncData();

            when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

            syncService.sync(toolId, data);

            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(namedJdbcTemplate).update(anyString(), paramsCaptor.capture());

            MapSqlParameterSource params = paramsCaptor.getValue();
            assertThat(params.getValue("apiToolId")).isEqualTo(toolId);
            assertThat(params.getValue("toolName")).isEqualTo("Get Stories");
            assertThat(params.getValue("provider")).isEqualTo("social/instagram");
            assertThat(params.getValue("resource")).isEqualTo("user_stories");
            assertThat(params.getValue("action")).isEqualTo("list");
        }

        @Test
        @DisplayName("should convert empty lists to null arrays")
        void shouldConvertEmptyListsToNull() {
            UUID toolId = UUID.randomUUID();
            SyncData data = SyncData.builder()
                .toolName("Test Tool")
                .provider("test/provider")
                .resource("test_resource")
                .action("get")
                .endpoint("/test")
                .summary("Test summary")
                .paramsRequired(List.of())
                .keywordsPrimary(List.of())
                .build();

            when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

            syncService.sync(toolId, data);

            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(namedJdbcTemplate).update(anyString(), paramsCaptor.capture());

            MapSqlParameterSource params = paramsCaptor.getValue();
            assertThat(params.getValue("paramsRequired")).isNull();
            assertThat(params.getValue("keywordsPrimary")).isNull();
        }

        @Test
        @DisplayName("should throw exception on database error")
        void shouldThrowExceptionOnDatabaseError() {
            UUID toolId = UUID.randomUUID();
            SyncData data = createFullSyncData();

            when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("Database error"));

            assertThatThrownBy(() -> syncService.sync(toolId, data))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to sync lexical index");
        }
    }

    @Nested
    @DisplayName("syncApiToolEnriched() Tests")
    class SyncApiToolEnrichedTests {

        @Test
        @DisplayName("should delegate to sync() method")
        void shouldDelegateToSync() {
            UUID toolId = UUID.randomUUID();
            EnrichedSynthesisData data = createFullEnrichedData();

            when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

            syncService.syncApiToolEnriched(toolId, data);

            verify(namedJdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
        }
    }

    @Nested
    @DisplayName("Stats Tests")
    class StatsTests {

        @Test
        @DisplayName("should return stats from database")
        void shouldReturnStats() {
            when(namedJdbcTemplate.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(Map.of("total", 10, "providers", 5, "resources", 3));

            LexicalIndexSyncService.LexicalIndexStats stats = syncService.getStats();

            assertThat(stats.totalEntries()).isEqualTo(10);
            assertThat(stats.uniqueProviders()).isEqualTo(5);
            assertThat(stats.uniqueResources()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return empty stats on error")
        void shouldReturnEmptyStatsOnError() {
            when(namedJdbcTemplate.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("DB error"));

            LexicalIndexSyncService.LexicalIndexStats stats = syncService.getStats();

            assertThat(stats.totalEntries()).isEqualTo(0);
            assertThat(stats.uniqueProviders()).isEqualTo(0);
            assertThat(stats.uniqueResources()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("syncAllTools() Tests")
    class SyncAllToolsTests {

        @Test
        @DisplayName("should update all rows to trigger tsvector rebuild")
        void shouldTriggerTsvectorRebuild() {
            when(jdbcTemplate.update(anyString())).thenReturn(100);

            syncService.syncAllTools();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture());

            assertThat(sqlCaptor.getValue()).contains("UPDATE catalog.lexical_search_index SET updated_at");
        }
    }

    @Nested
    @DisplayName("remove() Tests")
    class RemoveTests {

        @Test
        @DisplayName("should delete tool from lexical index")
        void shouldDeleteTool() {
            UUID toolId = UUID.randomUUID();
            when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

            syncService.remove(toolId);

            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(namedJdbcTemplate).update(anyString(), paramsCaptor.capture());

            assertThat(paramsCaptor.getValue().getValue("apiToolId")).isEqualTo(toolId);
        }
    }

    // Helper methods

    private SyncData createFullSyncData() {
        return SyncData.builder()
            .toolName("Get Stories")
            .provider("social/instagram")
            .resource("user_stories")
            .action("list")
            .endpoint("/instagram/stories/{user_id}")
            .paramsRequired(List.of("user_id"))
            .paramsOptional(List.of("limit", "cursor"))
            .paramExamples(List.of("12345"))
            .summary("Basic summary")
            .summaryExtended("Extended summary with more details")
            .keywords("legacy keywords")
            .keywordsPrimary(List.of("get stories", "fetch stories"))
            .keywordsSynonyms(List.of("ephemeral content", "temporary posts"))
            .keywordsParams(List.of("user_id:target user"))
            .useCases(List.of("social monitoring", "content backup"))
            .category("Social Media")
            .subcategory("Instagram")
            .build();
    }

    private EnrichedSynthesisData createFullEnrichedData() {
        return EnrichedSynthesisData.builder()
            .toolName("Get Stories")
            .provider("social/instagram")
            .resource("user_stories")
            .action("list")
            .endpoint("/instagram/stories/{user_id}")
            .paramsRequired(List.of("user_id"))
            .paramsOptional(List.of("limit", "cursor"))
            .paramExamples(List.of("12345"))
            .summary("Basic summary")
            .summaryExtended("Extended summary with more details")
            .keywords("legacy keywords")
            .keywordsPrimary(List.of("get stories", "fetch stories"))
            .keywordsSynonyms(List.of("ephemeral content", "temporary posts"))
            .keywordsParams(List.of("user_id:target user"))
            .useCases(List.of("social monitoring", "content backup"))
            .build();
    }
}
