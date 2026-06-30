package com.apimarketplace.agent.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A model execution link (CLOUD-only). Decouples a model's BILLING identity from
 * its EXECUTION transport: the {@code billed_provider}/{@code billed_model} pair
 * the user selects and is charged for (e.g. {@code anthropic/claude-opus-4-8})
 * is executed through {@code execution_provider}/{@code execution_model} - which
 * may be a CLI bridge (claude-code, codex, ...) OR a regular API provider (e.g.
 * {@code openrouter}). The billed identity is re-stamped onto the execution
 * response so credit consumption keeps charging the billed price.
 *
 * <p>The mapping is intentionally free: any billed pair may target any execution
 * provider/model. A {@link #scope} narrows the link to a single app surface (e.g.
 * only general chat, or only workflow agent nodes), with {@link ModelExecutionLinkScope#ALL}
 * the wildcard default. Resolution is centralised in {@code ModelExecutionLinkService};
 * the feature is gated behind {@code model-catalog.execution-links.enabled} (cloud
 * only).
 *
 * <p>See migrations {@code V365__create_model_execution_links.sql} +
 * {@code V369__rename_model_execution_link_bridge_to_execution.sql} +
 * {@code V370__add_scope_to_model_execution_links.sql}.
 */
@Entity
@Table(name = "model_execution_links")
public class ModelExecutionLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "billed_provider", nullable = false, length = 50)
    private String billedProvider;

    @Column(name = "billed_model", nullable = false, length = 150)
    private String billedModel;

    /** A CLI bridge slug (claude-code, ...) OR a regular API provider (e.g. openrouter). */
    @Column(name = "execution_provider", nullable = false, length = 50)
    private String executionProvider;

    /** NULL = reuse {@code billedModel} verbatim as the execution model id. */
    @Column(name = "execution_model", length = 150)
    private String executionModel;

    /** App surface this link applies to; {@link ModelExecutionLinkScope#ALL} = every surface. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private ModelExecutionLinkScope scope = ModelExecutionLinkScope.ALL;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBilledProvider() { return billedProvider; }
    public void setBilledProvider(String billedProvider) { this.billedProvider = billedProvider; }

    public String getBilledModel() { return billedModel; }
    public void setBilledModel(String billedModel) { this.billedModel = billedModel; }

    public String getExecutionProvider() { return executionProvider; }
    public void setExecutionProvider(String executionProvider) { this.executionProvider = executionProvider; }

    public String getExecutionModel() { return executionModel; }
    public void setExecutionModel(String executionModel) { this.executionModel = executionModel; }

    public ModelExecutionLinkScope getScope() { return scope; }
    public void setScope(ModelExecutionLinkScope scope) { this.scope = scope; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
