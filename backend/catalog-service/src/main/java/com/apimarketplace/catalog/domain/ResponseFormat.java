package com.apimarketplace.catalog.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Enumeration des formats de reponse supportes par les tool responses.
 * Utilise pour determiner si example_jsonb doit etre genere automatiquement.
 */
public enum ResponseFormat {
    JSON,
    HTML,
    CSV,
    TEXT,
    XML,
    BINARY;

    /**
     * Retourne la valeur en minuscules pour la base de donnees
     */
    @JsonValue
    public String getDatabaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Verifie si ce format necessite une validation JSON
     */
    public boolean requiresJsonValidation() {
        return this == JSON;
    }

    /**
     * Cree un ResponseFormat depuis sa valeur en base de donnees
     * Gere les cas minuscules et majuscules pour la compatibilite
     */
    public static ResponseFormat fromDatabaseValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return fromString(value);
    }

    /**
     * Cree un ResponseFormat depuis n'importe quelle casse
     * Methode alternative plus permissive
     */
    @JsonCreator
    public static ResponseFormat fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return JSON; // Valeur par defaut
        }

        String normalizedValue = value.trim().toUpperCase(Locale.ROOT);

        if (normalizedValue.contains("JSON")) {
            return JSON;
        }
        if (normalizedValue.contains("XML")) {
            return XML;
        }
        if (normalizedValue.contains("CSV")) {
            return CSV;
        }
        if (normalizedValue.contains("HTML") || normalizedValue.contains("XHTML")) {
            return HTML;
        }
        if (normalizedValue.contains("TEXT") || normalizedValue.contains("PLAIN")) {
            return TEXT;
        }
        if (normalizedValue.contains("PDF")
                || normalizedValue.contains("OCTET")
                || normalizedValue.contains("ZIP")
                || normalizedValue.contains("IMAGE")
                || normalizedValue.contains("PNG")
                || normalizedValue.contains("JPG")
                || normalizedValue.contains("JPEG")
                || normalizedValue.contains("BINARY")) {
            return BINARY;
        }

        switch (normalizedValue) {
            case "JSON":
            case "J":
                return JSON;
            case "HTML":
            case "H":
                return HTML;
            case "CSV":
            case "C":
                return CSV;
            case "TEXT":
            case "T":
                return TEXT;
            case "XML":
            case "X":
                return XML;
            case "BINARY":
            case "B":
                return BINARY;
            default:
                System.err.println("Format de reponse inconnu: '" + value + "', utilisation de JSON par defaut");
                return JSON;
        }
    }
}
