package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.dsl.FieldSpec;
import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import com.apimarketplace.catalog.mapping.entity.MappingVersionEntity;
import com.apimarketplace.catalog.mapping.repository.MappingDefinitionRepository;
import com.apimarketplace.catalog.mapping.repository.MappingVersionRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MappingRegistry class.
 *
 * MappingRegistry manages mapping definitions and versions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MappingRegistry")
class MappingRegistryTest {

    @Mock
    private MappingDefinitionRepository mappingDefinitionRepository;

    @Mock
    private MappingVersionRepository mappingVersionRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private MappingRegistry mappingRegistry;

    private UUID testToolId;

    @BeforeEach
    void setUp() {
        testToolId = UUID.randomUUID();
    }

    // ========================================================================
    // findByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("findByToolId()")
    class FindByToolIdTests {

        @Test
        @DisplayName("should return mapping definitions for tool")
        void shouldReturnMappingDefinitionsForTool() {
            MappingDefinitionEntity def = new MappingDefinitionEntity(testToolId, "Test Mapping", "user1");
            List<MappingDefinitionEntity> definitions = List.of(def);

            when(mappingDefinitionRepository.findByToolId(testToolId)).thenReturn(definitions);

            List<MappingDefinitionEntity> result = mappingRegistry.findByToolId(testToolId);

            assertEquals(1, result.size());
            assertEquals("Test Mapping", result.get(0).getDisplayName());
        }

        @Test
        @DisplayName("should return empty list when no definitions exist")
        void shouldReturnEmptyListWhenNoDefinitionsExist() {
            when(mappingDefinitionRepository.findByToolId(testToolId)).thenReturn(Collections.emptyList());

            List<MappingDefinitionEntity> result = mappingRegistry.findByToolId(testToolId);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getMappingDefinitionsByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("getMappingDefinitionsByToolId()")
    class GetMappingDefinitionsByToolIdTests {

        @Test
        @DisplayName("should delegate to findByToolId")
        void shouldDelegateToFindByToolId() {
            MappingDefinitionEntity def = new MappingDefinitionEntity(testToolId, "Test", "user1");
            when(mappingDefinitionRepository.findByToolId(testToolId)).thenReturn(List.of(def));

            List<MappingDefinitionEntity> result = mappingRegistry.getMappingDefinitionsByToolId(testToolId);

            assertEquals(1, result.size());
            verify(mappingDefinitionRepository).findByToolId(testToolId);
        }
    }

    // ========================================================================
    // findLatestByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("findLatestByToolId()")
    class FindLatestByToolIdTests {

        @Test
        @DisplayName("should return latest mapping definition")
        void shouldReturnLatestMappingDefinition() {
            MappingDefinitionEntity def = new MappingDefinitionEntity(testToolId, "Latest", "user1");

            when(mappingDefinitionRepository.findLatestByToolId(testToolId)).thenReturn(Optional.of(def));

            Optional<MappingDefinitionEntity> result = mappingRegistry.findLatestByToolId(testToolId);

            assertTrue(result.isPresent());
            assertEquals("Latest", result.get().getDisplayName());
        }

        @Test
        @DisplayName("should return empty when no definition exists")
        void shouldReturnEmptyWhenNoDefinitionExists() {
            when(mappingDefinitionRepository.findLatestByToolId(testToolId)).thenReturn(Optional.empty());

            Optional<MappingDefinitionEntity> result = mappingRegistry.findLatestByToolId(testToolId);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // existsByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("existsByToolId()")
    class ExistsByToolIdTests {

        @Test
        @DisplayName("should return true when definition exists")
        void shouldReturnTrueWhenDefinitionExists() {
            when(mappingDefinitionRepository.existsByToolId(testToolId)).thenReturn(true);

            boolean result = mappingRegistry.existsByToolId(testToolId);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when definition does not exist")
        void shouldReturnFalseWhenDefinitionDoesNotExist() {
            when(mappingDefinitionRepository.existsByToolId(testToolId)).thenReturn(false);

            boolean result = mappingRegistry.existsByToolId(testToolId);

            assertFalse(result);
        }
    }

    // ========================================================================
    // save tests
    // ========================================================================

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("should save mapping definition")
        void shouldSaveMappingDefinition() {
            MappingDefinitionEntity def = new MappingDefinitionEntity(testToolId, "New Mapping", "user1");

            when(mappingDefinitionRepository.save(def)).thenReturn(def);

            MappingDefinitionEntity result = mappingRegistry.save(def);

            assertNotNull(result);
            verify(mappingDefinitionRepository).save(def);
        }
    }

    // ========================================================================
    // createMappingDefinitionWithToolId tests
    // ========================================================================

    @Nested
    @DisplayName("createMappingDefinitionWithToolId()")
    class CreateMappingDefinitionWithToolIdTests {

        @Test
        @DisplayName("should create new mapping definition")
        void shouldCreateNewMappingDefinition() {
            String displayName = "New API Mapping";
            String createdBy = "testUser";

            when(mappingDefinitionRepository.save(any(MappingDefinitionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MappingDefinitionEntity result = mappingRegistry.createMappingDefinitionWithToolId(
                    testToolId, displayName, createdBy
            );

            assertNotNull(result);
            assertEquals(testToolId, result.getToolId());
            assertEquals(displayName, result.getDisplayName());
            assertEquals(createdBy, result.getCreatedBy());
        }
    }

    // ========================================================================
    // findLatestMappingVersionByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("findLatestMappingVersionByToolId()")
    class FindLatestMappingVersionByToolIdTests {

        @Test
        @DisplayName("should return latest mapping version")
        void shouldReturnLatestMappingVersion() {
            MappingVersionEntity version = new MappingVersionEntity(1L, "1.0", "{}", "user1");

            when(mappingVersionRepository.findLatestByToolId(testToolId)).thenReturn(Optional.of(version));

            Optional<MappingVersionEntity> result = mappingRegistry.findLatestMappingVersionByToolId(testToolId);

            assertTrue(result.isPresent());
            assertEquals("1.0", result.get().getVersion());
        }

        @Test
        @DisplayName("should return empty when no version exists")
        void shouldReturnEmptyWhenNoVersionExists() {
            when(mappingVersionRepository.findLatestByToolId(testToolId)).thenReturn(Optional.empty());

            Optional<MappingVersionEntity> result = mappingRegistry.findLatestMappingVersionByToolId(testToolId);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // saveMappingVersion tests
    // ========================================================================

    @Nested
    @DisplayName("saveMappingVersion()")
    class SaveMappingVersionTests {

        @Test
        @DisplayName("should save first version as 1.0")
        void shouldSaveFirstVersionAs1_0() {
            Long mappingDefId = 1L;
            MappingSpec spec = createTestMappingSpec();
            String createdBy = "user1";

            when(mappingVersionRepository.findByMappingDefinitionIdOrderByCreatedAtDesc(mappingDefId))
                    .thenReturn(Collections.emptyList());
            when(mappingVersionRepository.save(any(MappingVersionEntity.class)))
                    .thenAnswer(invocation -> {
                        MappingVersionEntity entity = invocation.getArgument(0);
                        return entity;
                    });

            MappingVersionEntity result = mappingRegistry.saveMappingVersion(mappingDefId, spec, createdBy);

            assertNotNull(result);
            assertEquals("1.0", result.getVersion());
            assertTrue(result.getIsLatest());
            verify(mappingVersionRepository).setAllVersionsAsNotLatest(mappingDefId);
        }

        @Test
        @DisplayName("should increment minor version")
        void shouldIncrementMinorVersion() {
            Long mappingDefId = 1L;
            MappingSpec spec = createTestMappingSpec();
            String createdBy = "user1";

            MappingVersionEntity existingVersion = new MappingVersionEntity(mappingDefId, "1.5", "{}", "user1");
            when(mappingVersionRepository.findByMappingDefinitionIdOrderByCreatedAtDesc(mappingDefId))
                    .thenReturn(List.of(existingVersion));
            when(mappingVersionRepository.save(any(MappingVersionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MappingVersionEntity result = mappingRegistry.saveMappingVersion(mappingDefId, spec, createdBy);

            assertEquals("1.6", result.getVersion());
        }
    }

    // ========================================================================
    // getRequiredFields tests
    // ========================================================================

    @Nested
    @DisplayName("getRequiredFields()")
    class GetRequiredFieldsTests {

        @Test
        @DisplayName("should return empty list")
        void shouldReturnEmptyList() {
            List<String> result = mappingRegistry.getRequiredFields(testToolId);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // apiToolExists tests
    // ========================================================================

    @Nested
    @DisplayName("apiToolExists()")
    class ApiToolExistsTests {

        @Test
        @DisplayName("should return true when tool exists")
        void shouldReturnTrueWhenToolExists() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenReturn(1);

            boolean result = mappingRegistry.apiToolExists(testToolId);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when tool does not exist")
        void shouldReturnFalseWhenToolDoesNotExist() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenReturn(0);

            boolean result = mappingRegistry.apiToolExists(testToolId);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false on exception")
        void shouldReturnFalseOnException() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenThrow(new RuntimeException("DB error"));

            boolean result = mappingRegistry.apiToolExists(testToolId);

            assertFalse(result);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private MappingSpec createTestMappingSpec() {
        MappingSpec spec = new MappingSpec();

        SourceSpec source = new SourceSpec();
        source.setFormat("json");
        source.setItemsPath("$[*]");
        source.setRoot("$");
        spec.setSource(source);

        Map<String, FieldSpec> fields = new HashMap<>();
        FieldSpec field = new FieldSpec();
        field.setCandidates(List.of("name"));
        field.setTo("string");
        fields.put("name", field);
        spec.setFields(fields);

        return spec;
    }
}
