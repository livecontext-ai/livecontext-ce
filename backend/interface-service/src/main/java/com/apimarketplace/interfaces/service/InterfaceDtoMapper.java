package com.apimarketplace.interfaces.service;

import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import org.springframework.stereotype.Component;

/**
 * Maps between JPA entities and client DTOs.
 */
@Component
public class InterfaceDtoMapper {

    public InterfaceDto toDto(InterfaceEntity entity) {
        if (entity == null) return null;
        InterfaceDto dto = new InterfaceDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setHtmlTemplate(entity.getHtmlTemplate());
        dto.setCssTemplate(entity.getCssTemplate());
        dto.setJsTemplate(entity.getJsTemplate());
        dto.setTargetTable(entity.getTargetTable());
        dto.setDataSourceId(entity.getDataSourceId());
        dto.setTemplateVariables(entity.getTemplateVariables());
        dto.setFormFields(entity.getFormFields());
        dto.setSourceWorkflowId(entity.getSourceWorkflowId());
        dto.setIsPublic(entity.getIsPublic());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setInterfaceType(entity.getInterfaceType());
        dto.setFormat(entity.getFormat());
        dto.setData(entity.getData());
        dto.setAgentId(entity.getAgentId());
        dto.setMessageId(entity.getMessageId());
        dto.setConversationId(entity.getConversationId());
        dto.setProjectId(entity.getProjectId());
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setSourcePublicationId(entity.getSourcePublicationId());
        dto.setWorkflowRunId(entity.getWorkflowRunId());
        dto.setStepDataId(entity.getStepDataId());
        return dto;
    }

    /**
     * Light list payload: the templates are dropped, but {@code format} is deliberately KEPT.
     * List cards render a thumbnail and need the interface's shape to size it - without the
     * format they would all fall back to 1280x800.
     */
    public InterfaceDto toListDto(InterfaceEntity entity) {
        InterfaceDto dto = toDto(entity);
        if (dto == null) return null;
        dto.setHtmlTemplate(null);
        dto.setCssTemplate(null);
        dto.setJsTemplate(null);
        dto.setTemplateVariables(null);
        dto.setFormFields(null);
        dto.setData(null);
        return dto;
    }

    public InterfaceSnapshotDto toSnapshotDto(InterfaceRunSnapshotEntity entity) {
        if (entity == null) return null;
        InterfaceSnapshotDto dto = new InterfaceSnapshotDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setInterfaceId(entity.getInterfaceId());
        dto.setWorkflowRunId(entity.getWorkflowRunId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setHtmlTemplate(entity.getHtmlTemplate());
        dto.setCssTemplate(entity.getCssTemplate());
        dto.setJsTemplate(entity.getJsTemplate());
        dto.setFormat(entity.getFormat());
        dto.setVariableMappings(entity.getVariableMappings());
        dto.setActionMappings(entity.getActionMappings());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
