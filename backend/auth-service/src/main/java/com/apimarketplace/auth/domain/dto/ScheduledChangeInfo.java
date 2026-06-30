package com.apimarketplace.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Information about a scheduled plan change.
 * Used to display pending changes to the user and allow cancellation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledChangeInfo {
    
    /**
     * Stripe schedule ID
     */
    private String scheduleId;
    
    /**
     * Current plan code
     */
    private String currentPlanCode;
    
    /**
     * Current plan display name
     */
    private String currentPlanName;
    
    /**
     * Target plan code (after the change)
     */
    private String targetPlanCode;
    
    /**
     * Target plan display name
     */
    private String targetPlanName;
    
    /**
     * Date when the change will take effect
     */
    private LocalDateTime effectiveDate;
    
    /**
     * Type of change (downgrade, billing_cycle_change, etc.)
     */
    private String changeType;
    
    /**
     * Current billing cycle
     */
    private String currentBillingCycle;
    
    /**
     * Target billing cycle (if changing)
     */
    private String targetBillingCycle;
    
    /**
     * Schedule status (active, released, canceled, etc.)
     */
    private String status;
    
    /**
     * Whether the change can be cancelled
     */
    private boolean cancellable;
    
    /**
     * User-facing message describing the scheduled change
     */
    private String userMessage;

    /** Current credit amount, e.g. 100000 for 100K credits (for credit_tier_change) */
    private Integer currentCreditQty;

    /** Target credit amount, e.g. 10000 for 10K credits (for credit_tier_change) */
    private Integer targetCreditQty;
}
