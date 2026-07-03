package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(unique = true, nullable = false)
    private String code; // FREE, STARTER, PRO, ENTERPRISE, PAYG

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Quotas inclus par cycle (NULL = illimite)
    @Column(name = "included_tool_credits")
    private Long includedToolCredits;

    @Column(name = "included_llm_tokens")
    private Long includedLlmTokens;

    @Column(name = "included_storage_bytes")
    private Long includedStorageBytes;

    // PAYG: tarifs unitaires optionnels (en micro-cents / unite) pour surconsommation
    @Column(name = "payg_price_per_tool_credit")
    private Long paygPricePerToolCredit; // nullable

    @Column(name = "payg_price_per_llm_token")
    private Long paygPricePerLlmToken; // nullable

    @Column(name = "payg_price_per_gb_month")
    private Long paygPricePerGbMonth; // nullable (GB-mois)

    @Column(name = "max_members")
    private Integer maxMembers = 1;

    // Per-plan resource creation limits (NULL = unlimited).
    // See V57__add_plan_resource_limits.sql for seed values and rationale.
    @Column(name = "max_workflows")
    private Integer maxWorkflows;

    @Column(name = "max_agents")
    private Integer maxAgents;

    @Column(name = "max_datasources")
    private Integer maxDatasources;

    @Column(name = "max_interfaces")
    private Integer maxInterfaces;

    @Column(name = "max_applications")
    private Integer maxApplications;

    // Max marketplace publications the org may PUBLISH (distinct from max_applications
    // = acquired). NULL = unlimited.
    @Column(name = "max_publications")
    private Integer maxPublications;

    // Max organizations a user may own (incl. their personal one). NULL = unlimited.
    // Shared-wallet model: workspaces are organizational containers, billing stays owner-level.
    @Column(name = "max_workspaces")
    private Integer maxWorkspaces;

    // Max workflow variables ({{$vars.name}}) per scope. NULL = unlimited.
    // See V383__workflow_variables.sql for seed values (FREE=3).
    @Column(name = "max_workflow_variables")
    private Integer maxWorkflowVariables;

    // Constructeurs
    public Plan() {}

    public Plan(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    // Getters et Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getIncludedToolCredits() {
        return includedToolCredits;
    }

    public void setIncludedToolCredits(Long includedToolCredits) {
        this.includedToolCredits = includedToolCredits;
    }

    public Long getIncludedLlmTokens() {
        return includedLlmTokens;
    }

    public void setIncludedLlmTokens(Long includedLlmTokens) {
        this.includedLlmTokens = includedLlmTokens;
    }

    public Long getIncludedStorageBytes() {
        return includedStorageBytes;
    }

    public void setIncludedStorageBytes(Long includedStorageBytes) {
        this.includedStorageBytes = includedStorageBytes;
    }

    public Long getPaygPricePerToolCredit() {
        return paygPricePerToolCredit;
    }

    public void setPaygPricePerToolCredit(Long paygPricePerToolCredit) {
        this.paygPricePerToolCredit = paygPricePerToolCredit;
    }

    public Long getPaygPricePerLlmToken() {
        return paygPricePerLlmToken;
    }

    public void setPaygPricePerLlmToken(Long paygPricePerLlmToken) {
        this.paygPricePerLlmToken = paygPricePerLlmToken;
    }

    public Long getPaygPricePerGbMonth() {
        return paygPricePerGbMonth;
    }

    public void setPaygPricePerGbMonth(Long paygPricePerGbMonth) {
        this.paygPricePerGbMonth = paygPricePerGbMonth;
    }

    public Integer getMaxMembers() {
        return maxMembers;
    }

    public void setMaxMembers(Integer maxMembers) {
        this.maxMembers = maxMembers;
    }

    public Integer getMaxWorkflows() {
        return maxWorkflows;
    }

    public void setMaxWorkflows(Integer maxWorkflows) {
        this.maxWorkflows = maxWorkflows;
    }

    public Integer getMaxAgents() {
        return maxAgents;
    }

    public void setMaxAgents(Integer maxAgents) {
        this.maxAgents = maxAgents;
    }

    public Integer getMaxDatasources() {
        return maxDatasources;
    }

    public void setMaxDatasources(Integer maxDatasources) {
        this.maxDatasources = maxDatasources;
    }

    public Integer getMaxInterfaces() {
        return maxInterfaces;
    }

    public void setMaxInterfaces(Integer maxInterfaces) {
        this.maxInterfaces = maxInterfaces;
    }

    public Integer getMaxApplications() {
        return maxApplications;
    }

    public void setMaxApplications(Integer maxApplications) {
        this.maxApplications = maxApplications;
    }

    public Integer getMaxPublications() {
        return maxPublications;
    }

    public void setMaxPublications(Integer maxPublications) {
        this.maxPublications = maxPublications;
    }

    public Integer getMaxWorkspaces() {
        return maxWorkspaces;
    }

    public void setMaxWorkspaces(Integer maxWorkspaces) {
        this.maxWorkspaces = maxWorkspaces;
    }

    public Integer getMaxWorkflowVariables() {
        return maxWorkflowVariables;
    }

    public void setMaxWorkflowVariables(Integer maxWorkflowVariables) {
        this.maxWorkflowVariables = maxWorkflowVariables;
    }

    /**
     * Returns the configured creation limit for the given resource type.
     * NULL means unlimited (consistent with included_* quotas).
     */
    public Integer getResourceLimit(String resourceType) {
        if (resourceType == null) return null;
        return switch (resourceType.toUpperCase()) {
            case "WORKFLOW"   -> maxWorkflows;
            case "AGENT"      -> maxAgents;
            case "DATASOURCE" -> maxDatasources;
            case "INTERFACE"  -> maxInterfaces;
            case "APPLICATION" -> maxApplications;
            case "PUBLICATION" -> maxPublications;
            case "WORKFLOW_VARIABLE" -> maxWorkflowVariables;
            default -> null;
        };
    }

    public boolean supportsTeam() {
        return code != null && (code.equals("TEAM") || code.startsWith("ENTERPRISE_"));
    }

    // Methodes utilitaires
    public boolean isUnlimited() {
        return "ENTERPRISE".equals(code);
    }
}
