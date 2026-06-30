package com.apimarketplace.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Result of a plan change operation.
 * Contains information about the type of change, success status, and relevant details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanChangeResult {
    
    public enum ChangeType {
        IMMEDIATE_UPGRADE,          // Upgrade applied immediately with proration
        IMMEDIATE_DOWNGRADE,        // Downgrade applied immediately (rare case)
        IMMEDIATE_CREDIT_UPGRADE,   // Credit tier upgrade applied immediately (new cycle via billing_cycle_anchor:NOW)
        SCHEDULED_DOWNGRADE,        // Downgrade scheduled for end of billing period
        SCHEDULED_CREDIT_DOWNGRADE, // Credit tier downgrade scheduled for end of billing period
        BILLING_CYCLE_CHANGE,       // Billing cycle change (monthly <-> yearly)
        CHECKOUT_REQUIRED,          // Redirect to checkout required
        NO_CHANGE,                  // No change (already on this plan)
        ERROR                       // Error occurred
    }
    
    /**
     * Type of change performed
     */
    private ChangeType changeType;
    
    /**
     * Whether the operation was successful
     */
    private boolean success;
    
    /**
     * Human-readable message
     */
    private String message;
    
    /**
     * Current plan code
     */
    private String currentPlanCode;
    
    /**
     * Target plan code
     */
    private String targetPlanCode;
    
    /**
     * Effective date of the change (null if immediate)
     */
    private LocalDateTime effectiveDate;
    
    /**
     * Checkout URL if redirect is required
     */
    private String checkoutUrl;
    
    /**
     * Stripe schedule ID if change is scheduled
     */
    private String scheduleId;
    
    /**
     * Proration amount in cents (if applicable)
     */
    private Long prorationAmount;
    
    // Factory methods for common scenarios
    
    public static PlanChangeResult immediateUpgrade(String currentPlan, String targetPlan, String message) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.IMMEDIATE_UPGRADE)
                .success(true)
                .currentPlanCode(currentPlan)
                .targetPlanCode(targetPlan)
                .message(message)
                .build();
    }
    
    public static PlanChangeResult scheduledDowngrade(String currentPlan, String targetPlan, 
                                                       LocalDateTime effectiveDate, String scheduleId) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.SCHEDULED_DOWNGRADE)
                .success(true)
                .currentPlanCode(currentPlan)
                .targetPlanCode(targetPlan)
                .effectiveDate(effectiveDate)
                .scheduleId(scheduleId)
                .message("Your plan will switch to " + targetPlan + " on " + effectiveDate.toLocalDate())
                .build();
    }
    
    public static PlanChangeResult scheduledCreditDowngrade(String planCode,
                                                              LocalDateTime effectiveDate, String scheduleId) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.SCHEDULED_CREDIT_DOWNGRADE)
                .success(true)
                .currentPlanCode(planCode)
                .targetPlanCode(planCode) // same plan, only credits change
                .effectiveDate(effectiveDate)
                .scheduleId(scheduleId)
                .message("Your credit pack will change on " + effectiveDate.toLocalDate())
                .build();
    }

    public static PlanChangeResult immediateCreditUpgrade(String planCode, String message) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.IMMEDIATE_CREDIT_UPGRADE)
                .success(true)
                .currentPlanCode(planCode)
                .targetPlanCode(planCode) // same plan, only credits change
                .message(message)
                .build();
    }

    public static PlanChangeResult checkoutRequired(String currentPlan, String targetPlan, String checkoutUrl) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.CHECKOUT_REQUIRED)
                .success(true)
                .currentPlanCode(currentPlan)
                .targetPlanCode(targetPlan)
                .checkoutUrl(checkoutUrl)
                .message("Redirecting to payment...")
                .build();
    }
    
    public static PlanChangeResult noChange(String currentPlan) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.NO_CHANGE)
                .success(true)
                .currentPlanCode(currentPlan)
                .targetPlanCode(currentPlan)
                .message("You are already on this plan")
                .build();
    }
    
    public static PlanChangeResult error(String message) {
        return PlanChangeResult.builder()
                .changeType(ChangeType.ERROR)
                .success(false)
                .message(message)
                .build();
    }
}
