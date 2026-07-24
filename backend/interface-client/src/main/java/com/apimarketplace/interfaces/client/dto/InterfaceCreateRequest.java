package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating an interface.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InterfaceCreateRequest {

    private String name;
    private String description;

    @JsonProperty("html_template")
    private String htmlTemplate;

    @JsonProperty("css_template")
    private String cssTemplate;

    @JsonProperty("js_template")
    private String jsTemplate;

    @JsonProperty("target_table")
    private String targetTable;

    @JsonProperty("data_source_id")
    private Long dataSourceId;

    @JsonProperty("is_public")
    private Boolean isPublic;

    @JsonProperty("interface_type")
    private String interfaceType;

    /** Display/capture format (preset name or "WIDTHxHEIGHT"). Null/omitted = full page at 1280x800. */
    @JsonProperty("format")
    private String format;

    private Map<String, Object> data;

    @JsonProperty("source_workflow_id")
    private UUID sourceWorkflowId;

    @JsonProperty("organization_id")
    private String organizationId;

    public InterfaceCreateRequest() {}

    // Getters and setters

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getHtmlTemplate() { return htmlTemplate; }
    public void setHtmlTemplate(String htmlTemplate) { this.htmlTemplate = htmlTemplate; }

    public String getCssTemplate() { return cssTemplate; }
    public void setCssTemplate(String cssTemplate) { this.cssTemplate = cssTemplate; }

    public String getJsTemplate() { return jsTemplate; }
    public void setJsTemplate(String jsTemplate) { this.jsTemplate = jsTemplate; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public UUID getSourceWorkflowId() { return sourceWorkflowId; }
    public void setSourceWorkflowId(UUID sourceWorkflowId) { this.sourceWorkflowId = sourceWorkflowId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
}
