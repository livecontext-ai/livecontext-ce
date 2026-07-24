package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request DTO for updating an interface.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InterfaceUpdateRequest {

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

    @JsonProperty("clear_data_source")
    private Boolean clearDataSource;

    /** Display/capture format (preset name or "WIDTHxHEIGHT"). Null = leave unchanged. */
    @JsonProperty("format")
    private String format;

    /**
     * Clears the format back to "unset" (full page at 1280x800). Needed because a null
     * `format` means "leave unchanged" on this merge-style update, mirroring
     * {@code clear_data_source}.
     */
    @JsonProperty("clear_format")
    private Boolean clearFormat;

    @JsonProperty("is_public")
    private Boolean isPublic;

    @JsonProperty("is_active")
    private Boolean isActive;

    private Map<String, Object> data;

    public InterfaceUpdateRequest() {}

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

    public Boolean getClearDataSource() { return clearDataSource; }
    public void setClearDataSource(Boolean clearDataSource) { this.clearDataSource = clearDataSource; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public Boolean getClearFormat() { return clearFormat; }
    public void setClearFormat(Boolean clearFormat) { this.clearFormat = clearFormat; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
