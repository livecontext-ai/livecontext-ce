package com.apimarketplace.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JPA Entity for the node_type_documentation table.
 * Stores documentation for workflow node types, enabling full-text search
 * for the workflow(action='search') functionality.
 */
@Entity
@Table(name = "node_type_documentation")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class NodeTypeDocumentationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique node type identifier (e.g., "trigger_datasource", "split", "decision")
     */
    @Column(name = "type", nullable = false, unique = true, length = 50)
    private String type;

    /**
     * Human-readable name (e.g., "Split Loop", "Decision Branch")
     */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    /**
     * Category: trigger, action, control_flow, ai
     */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /**
     * Description of what this node does
     */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /**
     * Required/optional parameters as JSON
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    /**
     * Output fields as JSON
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outputs", columnDefinition = "jsonb")
    private Map<String, Object> outputs;

    /**
     * Global variables available (e.g., {{core:label.output.iteration}}, {{core:label.output.current_item}})
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "global_variables", columnDefinition = "jsonb")
    private Map<String, Object> globalVariables;

    /**
     * workflow() examples as JSON array
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "examples", columnDefinition = "jsonb")
    private List<String> examples;

    /**
     * Additional search keywords
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", columnDefinition = "jsonb")
    private List<String> keywords;

    /**
     * Variable prefix for this node type (trigger, agent, core, table, mcp, interface)
     * Used to organize nodes by the variable syntax they use: {{prefix:label.output.field}}
     */
    @Column(name = "variable_prefix", length = 20)
    private String variablePrefix;

    /**
     * Edge ports for branching nodes (decision, switch, fork, loop, classify)
     * Contains port naming patterns and examples
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edge_ports", columnDefinition = "jsonb")
    private Map<String, Object> edgePorts;

    /**
     * Key concepts for understanding this node type
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concepts", columnDefinition = "jsonb")
    private List<String> concepts;

    /**
     * Comparison tables (vs other similar nodes)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comparison", columnDefinition = "jsonb")
    private Map<String, Object> comparison;

    /**
     * Whether this node type is enabled (visible to the AI agent).
     * Disabled nodes are hidden from search and cannot be created.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Documentation language (default: 'en')
     */
    @Column(name = "language", length = 10)
    private String language = "en";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public void setOutputs(Map<String, Object> outputs) {
        this.outputs = outputs;
    }

    public Map<String, Object> getGlobalVariables() {
        return globalVariables;
    }

    public void setGlobalVariables(Map<String, Object> globalVariables) {
        this.globalVariables = globalVariables;
    }

    public List<String> getExamples() {
        return examples;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getVariablePrefix() {
        return variablePrefix;
    }

    public void setVariablePrefix(String variablePrefix) {
        this.variablePrefix = variablePrefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, Object> getEdgePorts() {
        return edgePorts;
    }

    public void setEdgePorts(Map<String, Object> edgePorts) {
        this.edgePorts = edgePorts;
    }

    public List<String> getConcepts() {
        return concepts;
    }

    public void setConcepts(List<String> concepts) {
        this.concepts = concepts;
    }

    public Map<String, Object> getComparison() {
        return comparison;
    }

    public void setComparison(Map<String, Object> comparison) {
        this.comparison = comparison;
    }

    /**
     * Convert to a Map for JSON serialization in tool responses.
     * Only includes non-null fields to keep responses concise.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("type", type);
        result.put("label", label);
        result.put("category", category);
        result.put("enabled", enabled);
        if (variablePrefix != null) result.put("variable_prefix", variablePrefix);
        result.put("description", description);
        if (parameters != null && !parameters.isEmpty()) result.put("parameters", parameters);
        if (outputs != null && !outputs.isEmpty()) result.put("outputs", outputs);
        if (globalVariables != null && !globalVariables.isEmpty()) result.put("global_variables", globalVariables);
        if (edgePorts != null && !edgePorts.isEmpty()) result.put("edge_ports", edgePorts);
        if (concepts != null && !concepts.isEmpty()) result.put("concepts", concepts);
        if (comparison != null && !comparison.isEmpty()) result.put("comparison", comparison);
        if (examples != null && !examples.isEmpty()) result.put("examples", examples);
        return result;
    }

    /**
     * Convert to a concise Map optimized for LLM help.
     * Focuses on actionable information: what it does, parameters, outputs.
     */
    public Map<String, Object> toHelpMap() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("type", type);
        result.put("description", description);
        if (variablePrefix != null) result.put("prefix", variablePrefix);
        if (parameters != null && !parameters.isEmpty()) result.put("params", parameters);
        if (outputs != null && !outputs.isEmpty()) result.put("outputs", outputs);
        if (globalVariables != null && !globalVariables.isEmpty()) result.put("body_variables", globalVariables);
        if (edgePorts != null && !edgePorts.isEmpty()) result.put("ports", edgePorts);
        if (concepts != null && !concepts.isEmpty()) result.put("concepts", concepts);
        if (comparison != null && !comparison.isEmpty()) result.put("comparison", comparison);
        if (examples != null && !examples.isEmpty() && examples.size() == 1) {
            result.put("example", examples.get(0));
        } else if (examples != null && !examples.isEmpty()) {
            result.put("examples", examples);
        }
        return result;
    }
}
