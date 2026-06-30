package com.apimarketplace.common.storage.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classe utilitaire centralisee pour les operations communes de stockage.
 * Respecte le principe DRY en centralisant les methodes utilitaires.
 */
@Component
public class StorageUtils {

    private static final Logger logger = LoggerFactory.getLogger(StorageUtils.class);
    private static final String SHA_256 = "SHA-256";

    /**
     * Calcule le checksum SHA-256 pour n'importe quel type de donnees.
     * Methode unifiee eliminant la duplication de code.
     */
    public String calculateChecksum(Object data) {
        if (data == null) {
            return null;
        }

        try {
            byte[] bytes = convertToBytes(data);
            if (bytes == null) {
                return null;
            }
            return bytesToHex(computeSha256(bytes));
        } catch (NoSuchAlgorithmException e) {
            logger.warn("SHA-256 algorithm not available: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Calcule la taille des donnees en bytes.
     */
    public int calculateSize(Object data) {
        if (data == null) {
            return 0;
        }

        if (data instanceof byte[]) {
            return ((byte[]) data).length;
        }

        if (data instanceof String) {
            return ((String) data).getBytes(StandardCharsets.UTF_8).length;
        }

        // Pour les autres objets, convertir en String
        try {
            return data.toString().getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            logger.warn("Error calculating size for type {}: {}",
                data.getClass().getSimpleName(), e.getMessage());
            return 0;
        }
    }

    /**
     * Extrait l'extension d'un nom de fichier.
     */
    public String extractFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }

        return null;
    }

    /**
     * Convertit les collections immutables en collections mutables.
     * Utile pour la serialisation JSONB de PostgreSQL.
     */
    @SuppressWarnings("unchecked")
    public Object convertToMutableCollection(Object data) {
        if (data == null) {
            return null;
        }

        String className = data.getClass().getName();

        // Si c'est deja une collection mutable standard, la retourner
        if (data instanceof Map && !className.contains("ImmutableCollections")) {
            return data;
        }

        // Convertir Map immutable en HashMap
        if (data instanceof Map<?, ?> originalMap) {
            Map<Object, Object> mutableMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
                Object key = convertToMutableCollection(entry.getKey());
                Object value = convertToMutableCollection(entry.getValue());
                mutableMap.put(key, value);
            }
            return mutableMap;
        }

        // Convertir List immutable en ArrayList
        if (data instanceof List<?> originalList) {
            List<Object> mutableList = new ArrayList<>();
            for (Object item : originalList) {
                mutableList.add(convertToMutableCollection(item));
            }
            return mutableList;
        }

        // Pour les types primitifs, retourner tel quel
        return data;
    }

    /**
     * Determine le type de stockage base sur le MIME type.
     */
    public StorageType determineStorageType(String mimeType) {
        if (mimeType == null) {
            return StorageType.JSON;
        }

        String lower = mimeType.toLowerCase();

        if (lower.contains("json")) {
            return StorageType.JSON;
        }

        if (lower.startsWith("text/") || lower.contains("xml") || lower.contains("csv")) {
            return StorageType.TEXT;
        }

        if (lower.startsWith("image/") || lower.startsWith("video/") ||
            lower.startsWith("audio/") || lower.equals("application/pdf") ||
            lower.equals("application/octet-stream")) {
            return StorageType.BINARY;
        }

        return StorageType.JSON;
    }

    // ========== Methodes privees ==========

    private byte[] convertToBytes(Object data) {
        if (data instanceof byte[]) {
            return (byte[]) data;
        }

        if (data instanceof String) {
            return ((String) data).getBytes(StandardCharsets.UTF_8);
        }

        // For other objects, use toString()
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] computeSha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(SHA_256);
        return digest.digest(data);
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Enum pour les types de stockage.
     */
    public enum StorageType {
        JSON,
        TEXT,
        BINARY
    }
}
