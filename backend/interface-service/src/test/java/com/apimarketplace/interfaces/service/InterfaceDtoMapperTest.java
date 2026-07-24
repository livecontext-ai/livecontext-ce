package com.apimarketplace.interfaces.service;

import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InterfaceDtoMapperTest {

    private InterfaceDtoMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = new InterfaceDtoMapper();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void toDto_shouldMapAllFields() {
        InterfaceEntity entity = createTestEntity();

        InterfaceDto dto = mapper.toDto(entity);

        assertThat(dto.getId()).isEqualTo(entity.getId());
        assertThat(dto.getTenantId()).isEqualTo("tenant-1");
        assertThat(dto.getName()).isEqualTo("Test Interface");
        assertThat(dto.getDescription()).isEqualTo("Description");
        assertThat(dto.getHtmlTemplate()).isEqualTo("<div>{{title}}</div>");
        assertThat(dto.getCssTemplate()).isEqualTo(".cls { color: red; }");
        assertThat(dto.getJsTemplate()).isEqualTo("console.log('hi');");
        assertThat(dto.getIsPublic()).isFalse();
        assertThat(dto.getIsActive()).isTrue();
        assertThat(dto.getInterfaceType()).isEqualTo("html");
        assertThat(dto.getTemplateVariables()).containsExactly("title");
    }

    @Test
    void toDto_shouldHandleNullEntity() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toListDto_shouldOmitHeavyTemplateFields() {
        InterfaceEntity entity = createTestEntity();
        entity.setData(Map.of("large", "payload"));
        entity.setFormFields(List.of("field"));

        InterfaceDto dto = mapper.toListDto(entity);

        assertThat(dto.getId()).isEqualTo(entity.getId());
        assertThat(dto.getName()).isEqualTo("Test Interface");
        assertThat(dto.getHtmlTemplate()).isNull();
        assertThat(dto.getCssTemplate()).isNull();
        assertThat(dto.getJsTemplate()).isNull();
        assertThat(dto.getTemplateVariables()).isNull();
        assertThat(dto.getFormFields()).isNull();
        assertThat(dto.getData()).isNull();
    }

    @Test
    void toListDto_shouldKeepFormat_soListCardsCanShapeTheirThumbnail() {
        // The list payload drops the heavy templates but MUST keep the shape: a list card sizes
        // its thumbnail from it. Nulling it here (an easy mistake when trimming this payload)
        // silently sends every card back to the 1280x800 default.
        InterfaceEntity entity = createTestEntity();
        entity.setFormat("vertical");

        InterfaceDto dto = mapper.toListDto(entity);

        assertThat(dto.getHtmlTemplate()).isNull();
        assertThat(dto.getFormat()).isEqualTo("vertical");
    }

    @Test
    void toDto_shouldCarryFormat() {
        InterfaceEntity entity = createTestEntity();
        entity.setFormat("1080x1920");

        assertThat(mapper.toDto(entity).getFormat()).isEqualTo("1080x1920");
    }

    @Test
    void toSnapshotDto_shouldCarryFormat_soRunsKeepTheirShape() {
        // The render path prefers a run snapshot over the live interface, so a snapshot without
        // the format silently reverts the run to 1280x800.
        InterfaceEntity entity = createTestEntity();
        entity.setFormat("vertical");
        InterfaceRunSnapshotEntity snapshot =
            InterfaceRunSnapshotEntity.fromInterface(entity, UUID.randomUUID());

        InterfaceSnapshotDto dto = mapper.toSnapshotDto(snapshot);

        assertThat(dto.getFormat()).isEqualTo("vertical");
    }

    @Test
    void toSnapshotDto_shouldMapAllFields() {
        InterfaceEntity entity = createTestEntity();
        UUID runId = UUID.randomUUID();
        Map<String, String> varMappings = Map.of("title", "trigger:start.title");
        Map<String, String> actionMappings = Map.of("submit", "core:process");

        InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterfaceWithMappings(
                entity, runId, varMappings, actionMappings);

        InterfaceSnapshotDto dto = mapper.toSnapshotDto(snapshot);

        assertThat(dto.getInterfaceId()).isEqualTo(entity.getId());
        assertThat(dto.getWorkflowRunId()).isEqualTo(runId);
        assertThat(dto.getName()).isEqualTo("Test Interface");
        assertThat(dto.getHtmlTemplate()).isEqualTo("<div>{{title}}</div>");
        assertThat(dto.getVariableMappings()).containsEntry("title", "trigger:start.title");
        assertThat(dto.getActionMappings()).containsEntry("submit", "core:process");
    }

    @Test
    void toSnapshotDto_shouldHandleNullEntity() {
        assertThat(mapper.toSnapshotDto(null)).isNull();
    }

    /**
     * REGRESSION TEST: Ensures InterfaceDto serializes with camelCase JSON keys,
     * NOT snake_case. The old bug had @JsonProperty("html_template") on the DTO.
     */
    @Test
    void toDto_jsonSerialization_usesCamelCase() throws Exception {
        InterfaceEntity entity = createTestEntity();
        InterfaceDto dto = mapper.toDto(entity);

        String json = objectMapper.writeValueAsString(dto);

        // Must contain camelCase keys
        assertThat(json).contains("\"htmlTemplate\"");
        assertThat(json).contains("\"cssTemplate\"");
        assertThat(json).contains("\"jsTemplate\"");
        assertThat(json).contains("\"templateVariables\"");
        assertThat(json).contains("\"interfaceType\"");

        // Must NOT contain snake_case keys
        assertThat(json).doesNotContain("\"html_template\"");
        assertThat(json).doesNotContain("\"css_template\"");
        assertThat(json).doesNotContain("\"js_template\"");
        assertThat(json).doesNotContain("\"template_variables\"");
        assertThat(json).doesNotContain("\"interface_type\"");
    }

    @Test
    void toSnapshotDto_jsonSerialization_usesCamelCase() throws Exception {
        InterfaceEntity entity = createTestEntity();
        UUID runId = UUID.randomUUID();
        InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterfaceWithMappings(
                entity, runId, Map.of("x", "y"), Map.of("a", "b"));

        InterfaceSnapshotDto dto = mapper.toSnapshotDto(snapshot);
        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"htmlTemplate\"");
        assertThat(json).contains("\"variableMappings\"");
        assertThat(json).contains("\"actionMappings\"");
        assertThat(json).contains("\"workflowRunId\"");
        assertThat(json).contains("\"interfaceId\"");

        assertThat(json).doesNotContain("\"html_template\"");
        assertThat(json).doesNotContain("\"variable_mappings\"");
        assertThat(json).doesNotContain("\"action_mappings\"");
        assertThat(json).doesNotContain("\"workflow_run_id\"");
        assertThat(json).doesNotContain("\"interface_id\"");
    }

    @Test
    void toDto_shouldMapFormFields() {
        InterfaceEntity entity = createTestEntity();
        entity.setFormFields(List.of("username", "email"));

        InterfaceDto dto = mapper.toDto(entity);

        assertThat(dto.getFormFields()).containsExactly("username", "email");
    }

    private InterfaceEntity createTestEntity() {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-1");
        entity.setName("Test Interface");
        entity.setDescription("Description");
        entity.setHtmlTemplate("<div>{{title}}</div>");
        entity.setCssTemplate(".cls { color: red; }");
        entity.setJsTemplate("console.log('hi');");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("html");
        entity.setTemplateVariables(List.of("title"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
