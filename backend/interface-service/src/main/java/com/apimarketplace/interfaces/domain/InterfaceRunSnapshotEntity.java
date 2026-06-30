package com.apimarketplace.interfaces.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the interface_run_snapshots table.
 * Stores a frozen copy of the HTML template and variable mappings at workflow execution time.
 */
@Entity
@Table(name = "interface_run_snapshots")
public class InterfaceRunSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "interface_id", nullable = false)
    private UUID interfaceId;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "html_template", nullable = false)
    private String htmlTemplate;

    @Lob
    @Column(name = "css_template")
    private String cssTemplate;

    @Lob
    @Column(name = "js_template")
    private String jsTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variable_mappings", columnDefinition = "jsonb")
    private Map<String, String> variableMappings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_mappings", columnDefinition = "jsonb")
    private Map<String, String> actionMappings;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InterfaceRunSnapshotEntity() {
    }

    public InterfaceRunSnapshotEntity(String tenantId, UUID interfaceId, UUID workflowRunId,
                                       String name, String description, String htmlTemplate) {
        this.tenantId = tenantId;
        this.interfaceId = interfaceId;
        this.workflowRunId = workflowRunId;
        this.name = name;
        this.description = description;
        this.htmlTemplate = htmlTemplate;
    }

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Creates a snapshot from an InterfaceEntity (without mapping).
     */
    public static InterfaceRunSnapshotEntity fromInterface(InterfaceEntity iface, UUID workflowRunId) {
        InterfaceRunSnapshotEntity snapshot = new InterfaceRunSnapshotEntity(
            iface.getTenantId(),
            iface.getId(),
            workflowRunId,
            iface.getName(),
            iface.getDescription(),
            iface.getHtmlTemplate()
        );
        snapshot.setCssTemplate(iface.getCssTemplate());
        snapshot.setJsTemplate(iface.getJsTemplate());
        return snapshot;
    }

    /**
     * Creates a snapshot from an InterfaceEntity with variable mapping.
     */
    public static InterfaceRunSnapshotEntity fromInterfaceWithMapping(
            InterfaceEntity iface, UUID workflowRunId, Map<String, String> mapping) {
        InterfaceRunSnapshotEntity snapshot = fromInterface(iface, workflowRunId);
        snapshot.setVariableMappings(mapping);
        return snapshot;
    }

    /**
     * Creates a snapshot from an InterfaceEntity with both variable and action mappings.
     */
    public static InterfaceRunSnapshotEntity fromInterfaceWithMappings(
            InterfaceEntity iface, UUID workflowRunId,
            Map<String, String> variableMapping, Map<String, String> actionMapping) {
        InterfaceRunSnapshotEntity snapshot = fromInterfaceWithMapping(iface, workflowRunId, variableMapping);
        snapshot.setActionMappings(actionMapping);
        return snapshot;
    }

    // Getters / Setters

    public UUID getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getInterfaceId() { return interfaceId; }
    public void setInterfaceId(UUID interfaceId) { this.interfaceId = interfaceId; }

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

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

    public Map<String, String> getVariableMappings() { return variableMappings; }
    public void setVariableMappings(Map<String, String> variableMappings) { this.variableMappings = variableMappings; }

    public Map<String, String> getActionMappings() { return actionMappings; }
    public void setActionMappings(Map<String, String> actionMappings) { this.actionMappings = actionMappings; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
