package com.apimarketplace.common.storage.domain;

/**
 * enumeration des statuts possibles pour un storage
 */
public enum StorageStatus {
    ACTIVE,     // Storage actif et accessible
    ARCHIVED,   // Storage archive (peut etre restaure)
    DELETED     // Storage supprime (marque pour suppression definitive)
}
