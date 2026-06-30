package com.apimarketplace.auth.enums;

import java.util.Set;
import java.util.HashSet;

/**
 * Classe utilitaire pour la gestion des codes de plans
 * Les codes de plans sont recuperes dynamiquement depuis la base de donnees
 */
public class PlanCode {
    
    /**
     * Normalise un code de plan en le convertissant en majuscules
     * et en gerant les conversions speciales
     */
    public static String normalize(String planCode) {
        if (planCode == null || planCode.trim().isEmpty()) {
            return null;
        }
        
        String normalized = planCode.trim().toUpperCase();
        
        // Conversions speciales
        if ("ENTERPRISE".equals(normalized)) {
            normalized = "ENTERPRISE_BASIC";
        }
        
        return normalized;
    }
    
    /**
     * Verifie si un code de plan est valide en utilisant les codes disponibles
     * @param planCode Le code de plan a verifier
     * @param availablePlanCodes Les codes de plans disponibles depuis la DB
     * @return true si le plan est valide, false sinon
     */
    public static boolean isValid(String planCode, Set<String> availablePlanCodes) {
        String normalized = normalize(planCode);
        if (normalized == null || availablePlanCodes == null) {
            return false;
        }
        
        return availablePlanCodes.contains(normalized);
    }
    
    /**
     * Verifie si un code de plan est valide (version simplifiee pour FREE)
     * @param planCode Le code de plan a verifier
     * @return true si le plan est valide, false sinon
     */
    public static boolean isValid(String planCode) {
        String normalized = normalize(planCode);
        if (normalized == null) {
            return false;
        }
        
        // FREE est toujours valide
        return "FREE".equals(normalized);
        
        // Pour les autres plans, on ne peut pas valider sans les codes de la DB
        // Cette methode est utilisee uniquement pour FREE
    }
    
    /**
     * Checks if a plan code represents a credit pack (not a subscribable plan).
     */
    public static boolean isCreditPack(String planCode) {
        String n = normalize(planCode);
        return "CREDIT_PACK".equals(n);
    }

    /**
     * Recupere tous les codes de plans valides depuis la base de donnees
     * @param availablePlanCodes Les codes de plans disponibles depuis la DB
     * @return Un Set contenant tous les codes de plans valides
     */
    public static Set<String> getAllValidCodes(Set<String> availablePlanCodes) {
        Set<String> validCodes = new HashSet<>();
        
        if (availablePlanCodes != null) {
            validCodes.addAll(availablePlanCodes);
        }
        
        // FREE est toujours valide
        validCodes.add("FREE");
        
        return validCodes;
    }
}
