package com.apimarketplace.catalog.util;

/**
 * Utility class for slug generation and management.
 * All methods are static - this is not a Spring bean.
 */
public final class SlugUtils {

    private SlugUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Genere un slug a partir d'un texte
     * @param text Le texte source
     * @return Le slug genere (minuscules, tirets au lieu des espaces/caracteres speciaux)
     */
    public static String generateSlug(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        // Convertir en minuscules
        String slug = text.toLowerCase().trim();

        // Remplacer les espaces et underscores par des tirets
        slug = slug.replaceAll("[\\s_]+", "-");

        // Supprimer les caracteres speciaux (garder seulement lettres, chiffres et tirets)
        slug = slug.replaceAll("[^a-z0-9\\-]", "");

        // Supprimer les tirets multiples consecutifs
        slug = slug.replaceAll("-+", "-");

        // Supprimer les tirets au debut et a la fin
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");

        return slug;
    }

    /**
     * Genere un slug unique en ajoutant un suffixe numerique si necessaire
     * @param baseSlug Le slug de base
     * @param existingSlugs Liste des slugs existants pour verifier l'unicite
     * @return Un slug unique
     */
    public static String generateUniqueSlug(String baseSlug, java.util.List<String> existingSlugs) {
        if (existingSlugs == null || existingSlugs.isEmpty() || !existingSlugs.contains(baseSlug)) {
            return baseSlug;
        }

        int counter = 1;
        String uniqueSlug = baseSlug + "-" + counter;

        while (existingSlugs.contains(uniqueSlug)) {
            counter++;
            uniqueSlug = baseSlug + "-" + counter;
        }

        return uniqueSlug;
    }

    /**
     * Valide qu'un slug est correctement formate
     * @param slug Le slug a valider
     * @return true si le slug est valide
     */
    public static boolean isValidSlug(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return false;
        }

        // Le slug ne doit contenir que des lettres minuscules, chiffres et tirets
        return slug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    }

    /**
     * Nettoie un slug existant pour s'assurer qu'il respecte le format
     * @param slug Le slug a nettoyer
     * @return Le slug nettoye
     */
    public static String sanitizeSlug(String slug) {
        if (slug == null) {
            return "";
        }

        return generateSlug(slug);
    }

    /**
     * Genere un slug unique pour une categorie d'outil
     * @param name Le nom de la categorie
     * @param existingSlugs Liste des slugs existants
     * @return Un slug unique
     */
    public static String generateToolCategorySlug(String name, java.util.List<String> existingSlugs) {
        String baseSlug = generateSlug(name);
        return generateUniqueSlug(baseSlug, existingSlugs);
    }

    /**
     * Genere un slug unique pour un nom d'outil
     * @param name Le nom de l'outil
     * @param existingSlugs Liste des slugs existants
     * @return Un slug unique
     */
    public static String generateToolNameSlug(String name, java.util.List<String> existingSlugs) {
        String baseSlug = generateSlug(name);
        return generateUniqueSlug(baseSlug, existingSlugs);
    }

    public static String appendRandomSuffix(String baseSlug) {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 6);
        return baseSlug + "-" + suffix;
    }

    /**
     * Genere un slug unique pour une categorie d'API
     * @param name Le nom de la categorie
     * @param existingSlugs Liste des slugs existants
     * @return Un slug unique
     */
    public static String generateApiCategorySlug(String name, java.util.List<String> existingSlugs) {
        String baseSlug = generateSlug(name);
        return generateUniqueSlug(baseSlug, existingSlugs);
    }

    /**
     * Genere un slug unique pour une sous-categorie d'API
     * @param name Le nom de la sous-categorie
     * @param existingSlugs Liste des slugs existants
     * @return Un slug unique
     */
    public static String generateApiSubcategorySlug(String name, java.util.List<String> existingSlugs) {
        String baseSlug = generateSlug(name);
        return generateUniqueSlug(baseSlug, existingSlugs);
    }

    /**
     * Genere un slug unique pour une sous-categorie d'API avec prefixe de categorie
     * @param categorySlug Le slug de la categorie parente
     * @param name Le nom de la sous-categorie
     * @param existingSlugs Liste des slugs existants
     * @return Un slug unique avec prefixe
     */
    public static String generateApiSubcategorySlugWithPrefix(String categorySlug, String name, java.util.List<String> existingSlugs) {
        String baseSlug = generateSlug(categorySlug + "-" + name);
        return generateUniqueSlug(baseSlug, existingSlugs);
    }
}
