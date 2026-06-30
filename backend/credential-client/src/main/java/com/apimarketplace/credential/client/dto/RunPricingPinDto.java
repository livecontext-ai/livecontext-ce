package com.apimarketplace.credential.client.dto;

/**
 * Response from {@code POST /api/internal/credentials/run-pricing-pin} -
 * the persisted binding between a workflow run and a specific pricing
 * version. Read by the orchestrator to confirm which rate was locked in.
 */
public class RunPricingPinDto {

    private Long id;
    private String runId;
    private Long userId;
    private Long platformCredentialId;
    private Long pricingVersionId;
    private boolean cancelled;

    public RunPricingPinDto() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPlatformCredentialId() { return platformCredentialId; }
    public void setPlatformCredentialId(Long platformCredentialId) { this.platformCredentialId = platformCredentialId; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
