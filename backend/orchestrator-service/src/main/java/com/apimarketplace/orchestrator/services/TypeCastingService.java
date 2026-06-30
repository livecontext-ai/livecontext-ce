package com.apimarketplace.orchestrator.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service pour convertir les valeurs selon les types attendus par les parametres d'API
 */
@Service
public class TypeCastingService {
    
    private static final Logger logger = LoggerFactory.getLogger(TypeCastingService.class);
    
    /**
     * Convertit une valeur selon le type attendu
     * 
     * @param value La valeur a convertir
     * @param expectedType Le type attendu (number, string, array, boolean)
     * @param parameterName Le nom du parametre (pour les messages d'erreur)
     * @return La valeur convertie ou null si la conversion echoue
     * @throws TypeConversionException Si la conversion echoue
     */
    public Object castValue(Object value, String expectedType, String parameterName) throws TypeConversionException {
        if (value == null) {
            return null;
        }
        
        logger.debug("Conversion de '{}' en type '{}' pour le parametre '{}'", value, expectedType, parameterName);
        
        try {
            switch (expectedType.toLowerCase()) {
                case "number":
                    return castToNumber(value, parameterName);
                case "string":
                    return castToString(value, parameterName);
                case "array":
                    return castToArray(value, parameterName);
                case "boolean":
                    return castToBoolean(value, parameterName);
                default:
                    throw new TypeConversionException(
                        String.format("Type non supporte: '%s' pour le parametre '%s'", expectedType, parameterName)
                    );
            }
        } catch (Exception e) {
            throw new TypeConversionException(
                String.format("Impossible de convertir '%s' en type '%s' pour le parametre '%s': %s",
                    value, expectedType, parameterName, e.getMessage())
            );
        }
    }
    
    /**
     * Convertit une valeur en nombre
     */
    private Object castToNumber(Object value, String parameterName) throws TypeConversionException {
        if (value instanceof Number) {
            return value;
        }
        
        if (value instanceof final String strValue) {
            try {
                // Essayer d'abord comme int
                if (strValue.matches("-?\\d+")) {
                    return Integer.parseInt(strValue);
                }
                // Puis comme double
                return Double.parseDouble(strValue);
            } catch (NumberFormatException e) {
                throw new TypeConversionException(
                    String.format("Impossible de convertir la chaîne '%s' en nombre pour le parametre '%s'",
                        strValue, parameterName)
                );
            }
        }
        
        throw new TypeConversionException(
            String.format("Type de valeur non convertible en nombre: %s pour le parametre '%s'",
                value.getClass().getSimpleName(), parameterName)
        );
    }
    
    /**
     * Convertit une valeur en chaîne
     */
    private Object castToString(Object value, String parameterName) throws TypeConversionException {
        if (value instanceof String) {
            return value;
        }
        
        return String.valueOf(value);
    }
    
    /**
     * Convertit une valeur en tableau
     */
    private Object castToArray(Object value, String parameterName) throws TypeConversionException {
        if (value instanceof List) {
            return value;
        }
        
        if (value instanceof Object[]) {
            return List.of((Object[]) value);
        }
        
        if (value instanceof String) {
            // Pour les chaînes, on peut les traiter comme un tableau d'un element
            return List.of(value);
        }
        
        throw new TypeConversionException(
            String.format("Type de valeur non convertible en tableau: %s pour le parametre '%s'",
                value.getClass().getSimpleName(), parameterName)
        );
    }
    
    /**
     * Convertit une valeur en booleen
     */
    private Object castToBoolean(Object value, String parameterName) throws TypeConversionException {
        if (value instanceof Boolean) {
            return value;
        }
        
        if (value instanceof String) {
            String strValue = ((String) value).toLowerCase();
            if ("true".equals(strValue) || "1".equals(strValue) || "yes".equals(strValue)) {
                return true;
            }
            if ("false".equals(strValue) || "0".equals(strValue) || "no".equals(strValue)) {
                return false;
            }
            throw new TypeConversionException(
                String.format("Impossible de convertir la chaîne '%s' en booleen pour le parametre '%s'",
                    strValue, parameterName)
            );
        }
        
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        
        throw new TypeConversionException(
            String.format("Type de valeur non convertible en booleen: %s pour le parametre '%s'",
                value.getClass().getSimpleName(), parameterName)
        );
    }
    
    /**
     * Exception pour les erreurs de conversion de type
     */
    public static class TypeConversionException extends RuntimeException {
        public TypeConversionException(String message) {
            super(message);
        }
    }
}
