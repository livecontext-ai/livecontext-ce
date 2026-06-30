package com.apimarketplace.common.storage.service.mapping;

import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Composant responsable de la normalisation des specifications de mapping.
 * Respecte SRP: seule responsabilite = normaliser les specs.
 */
@Component
public class MappingSpecNormalizer {

    private static final String DEFAULT_FORMAT = "json";
    private static final String DEFAULT_PATH = "$";

    /**
     * Cree une copie nettoyee du MappingSpec.
     */
    public MappingSpec cleanSpec(MappingSpec spec) {
        if (spec == null) {
            return null;
        }

        MappingSpec cleaned = new MappingSpec();

        if (spec.getSource() != null) {
            cleaned.setSource(copySourceSpec(spec.getSource()));
        }

        if (spec.getFields() != null) {
            cleaned.setFields(copyFieldSpecs(spec.getFields()));
        }

        if (spec.getGlobals() != null) {
            cleaned.setGlobals(copyFieldSpecs(spec.getGlobals()));
        }

        return cleaned;
    }

    /**
     * Normalise le SourceSpec (itemsPath, rootAlternatives, etc.).
     */
    public void normalizeSourceSpec(MappingSpec spec) {
        if (spec == null || spec.getSource() == null) {
            return;
        }

        SourceSpec source = spec.getSource();

        String itemsPath = normalizeItemsPath(source.getItemsPath(), DEFAULT_PATH);
        source.setItemsPath(itemsPath);

        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        alternatives.add(itemsPath);

        if (source.getRootAlternatives() != null) {
            for (String alt : source.getRootAlternatives()) {
                String normalized = normalizeAbsolutePath(alt);
                if (normalized != null) {
                    alternatives.add(normalized);
                }
            }
        }

        source.setRootAlternatives(new ArrayList<>(alternatives));
        source.setFormat(DEFAULT_FORMAT);
    }

    /**
     * Normalise les candidates dans les fields et globals.
     */
    public void normalizeCandidates(MappingSpec spec) {
        if (spec == null) {
            return;
        }

        String itemsPath = spec.getSource() != null ? spec.getSource().getItemsPath() : null;

        // Normaliser les fields
        if (spec.getFields() != null) {
            spec.getFields().forEach((name, field) ->
                normalizeFieldCandidates(field, itemsPath, true));
        }

        // Normaliser les globals
        if (spec.getGlobals() != null) {
            spec.getGlobals().forEach((name, field) ->
                normalizeFieldCandidates(field, DEFAULT_PATH, false));
        }
    }

    /**
     * Pipeline complet de normalisation.
     */
    public MappingSpec normalizeComplete(MappingSpec spec) {
        MappingSpec cleaned = cleanSpec(spec);
        if (cleaned != null) {
            normalizeSourceSpec(cleaned);
            normalizeCandidates(cleaned);
        }
        return cleaned;
    }

    // ========== Methodes de normalisation de path ==========

    /**
     * Normalise un itemsPath.
     */
    public String normalizeItemsPath(String value, String fallback) {
        String path = (value == null || value.isBlank()) ? fallback : value.trim();

        // Supprimer les parentheses
        path = removeParentheses(path);

        // Ajouter le prefixe $ si necessaire
        return ensureJsonPathPrefix(path);
    }

    /**
     * Normalise un path absolu.
     */
    public String normalizeAbsolutePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = removeParentheses(path.trim());
        normalized = ensureJsonPathPrefix(normalized);

        if ("$.null".equalsIgnoreCase(normalized)) {
            return null;
        }

        return normalized;
    }

    /**
     * Normalise un candidate (peut etre relatif @ ou absolu $).
     */
    public String normalizeCandidate(String raw, String basePath, boolean allowRelativeAt) {
        String candidate = removeParentheses(raw.trim());

        // Deja un path recursif ou avec index
        if (candidate.startsWith("$..") || candidate.startsWith("$[")) {
            return candidate;
        }

        // Path relatif @
        if (candidate.startsWith("@")) {
            return normalizeRelativePath(candidate, basePath, allowRelativeAt);
        }

        // Path absolu $
        if (candidate.startsWith("$")) {
            return ensureJsonPathPrefix(candidate);
        }

        // Sinon, c'est un nom de champ simple, le prefixer avec @.
        return "@." + candidate;
    }

    // ========== Methodes privees ==========

    private SourceSpec copySourceSpec(SourceSpec source) {
        SourceSpec copy = new SourceSpec();
        copy.setFormat(source.getFormat());
        copy.setItemsPath(source.getItemsPath());
        copy.setRoot(source.getRoot());
        copy.setRootAlternatives(source.getRootAlternatives() != null
            ? new ArrayList<>(source.getRootAlternatives())
            : null);
        return copy;
    }

    private Map<String, FieldSpec> copyFieldSpecs(Map<String, FieldSpec> fields) {
        Map<String, FieldSpec> copy = new LinkedHashMap<>();
        fields.forEach((key, field) -> {
            FieldSpec newField = new FieldSpec();
            newField.setCandidates(field.getCandidates() != null
                ? new ArrayList<>(field.getCandidates())
                : null);
            newField.setTo(field.getTo());
            newField.setRequired(field.getRequired());
            newField.setDefaultValue(field.getDefaultValue());
            copy.put(key, newField);
        });
        return copy;
    }

    private void normalizeFieldCandidates(FieldSpec field, String basePath, boolean allowRelativeAt) {
        if (field == null || field.getCandidates() == null) {
            return;
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();

        for (String raw : field.getCandidates()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String candidate = normalizeCandidate(raw, basePath, allowRelativeAt);
            normalized.add(candidate);

            // Si relatif et basePath disponible, ajouter aussi la version absolue
            if (basePath != null && candidate.startsWith("@.")) {
                String tail = candidate.substring(2);
                if (!tail.isEmpty() && !tail.startsWith(".")) {
                    tail = "." + tail;
                }
                normalized.add(basePath + tail);
            }
        }

        field.setCandidates(new ArrayList<>(normalized));
    }

    private String removeParentheses(String path) {
        String result = path;
        if (result.startsWith("(") && result.endsWith(")")) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result.replace("(", "").replace(")", "");
    }

    private String ensureJsonPathPrefix(String path) {
        if (path.startsWith("$..") || path.startsWith("$[") || path.startsWith("$.")) {
            return path;
        }

        if (path.startsWith("$")) {
            return path.replaceFirst("^\\$", "\\$.");
        }

        if (path.startsWith(".")) {
            return "$" + path;
        }

        return "$." + path;
    }

    private String normalizeRelativePath(String candidate, String basePath, boolean allowRelativeAt) {
        if (!allowRelativeAt) {
            String tail = candidate.replaceFirst("^@\\.?", "");
            if (!tail.isEmpty() && !tail.startsWith(".")) {
                tail = "." + tail;
            }
            return (basePath == null ? "$" : basePath) + tail;
        }

        // S'assurer que @ est suivi de .
        if (!candidate.startsWith("@.")) {
            return candidate.replaceFirst("^@", "@.");
        }

        return candidate;
    }
}
