package com.apimarketplace.common.storage.domain;

/**
 * enumeration des statuts de quota
 */
public enum QuotaStatus {
    OK,                     // Quota OK, peut stocker
    SOFT_LIMIT_REACHED,     // Limite douce atteinte, avertissement
    HARD_LIMIT_REACHED      // Limite dure atteinte, refus de stockage
}
