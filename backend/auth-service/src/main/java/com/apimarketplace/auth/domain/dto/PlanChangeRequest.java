package com.apimarketplace.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Requête de changement de plan (upgrade ou downgrade)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanChangeRequest {
    
    /**
     * Code du nouveau plan cible (ex: "STARTER", "PRO", "FREE")
     */
    private String targetPlanCode;
    
    /**
     * Cycle de facturation souhaité (monthly ou yearly)
     * Si null, conserve le cycle actuel
     */
    private String billingCycle;
    
    /**
     * Si true, force l'application immédiate (avec prorata)
     * Si false ou null, planifie à la fin de période pour les downgrades
     */
    private Boolean immediate;

    /**
     * Credit tier index (0-9) for the credit pack slider.
     * Maps to CreditTierConstants.CREDIT_COSTS for Stripe quantity.
     * If null, defaults to 0 (no additional credits).
     */
    private Integer creditTierIndex;
}
