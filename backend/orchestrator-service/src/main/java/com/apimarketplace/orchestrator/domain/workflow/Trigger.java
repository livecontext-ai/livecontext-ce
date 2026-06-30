package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * Trigger/Datasource du workflow.
 *
 * IMPORTANT :
 * - {@code id} reste l'identifiant "technique" (ex: ID numérique de datasource) utilisé pour les appels DB.
 * - {@code label} est le label métier/slug lisible, utilisé pour toutes les clés de référence (trigger:<label>).
 *
 * Ainsi, les edges et les templates utilisent toujours {@code trigger:<labelSlug>},
 * et le backend retrouve l'ID réel via le Trigger correspondant dans le plan.
 *
 * For chat triggers:
 * - {@code chatMatch} defines how to match incoming messages (startsWith, contains, regex, etc.)
 */
public record Trigger(
        String id,                      // ID technique (ex: datasourceId)
        String label,                   // Label métier/slug, utilisé dans trigger:<label>
        String strategy,
        String type,
        java.util.Map<String, Object> params,
        ChatMatchConfig chatMatch       // Optional: chat trigger match configuration
) {
    public Trigger {
        id = normalizeMandatory(id, "trigger id");
        label = normalizeOptional(label, null);
        strategy = normalizeOptional(strategy, "single");
        type = normalizeOptional(type, "datasource");
        params = params != null ? java.util.Collections.unmodifiableMap(new java.util.HashMap<>(params)) : java.util.Map.of();
        // chatMatch is optional, defaults to ANY for chat triggers
        if (chatMatch == null && "chat".equals(type)) {
            chatMatch = ChatMatchConfig.any();
        }
    }

    public Trigger(String id, String label, String strategy, String type) {
        this(id, label, strategy, type, java.util.Map.of(), null);
    }

    public Trigger(String id, String label, String strategy, String type, java.util.Map<String, Object> params) {
        this(id, label, strategy, type, params, null);
    }

    /**
     * Clé normalisée utilisée partout dans l'exécution (états, dépendances, etc.).
     * On base cette clé sur le label/slug pour matcher les edges qui utilisent trigger:<label>.
     * Fallback sur l'ID technique si aucun label n'est fourni.
     */
    public String getNormalizedKey() {
        String base = (label != null && !label.isBlank()) ? label : id;
        String normalized = LabelNormalizer.normalizeLabel(base);
        return normalized != null ? "trigger:" + normalized : "trigger:" + base.toLowerCase(Locale.ROOT);
    }

    private static String normalizeMandatory(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty " + field);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

}
