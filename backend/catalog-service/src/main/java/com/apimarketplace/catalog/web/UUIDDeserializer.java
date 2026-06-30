package com.apimarketplace.catalog.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.UUID;

/**
 * Custom deserializer for UUID that handles both string and numeric inputs
 */
public class UUIDDeserializer extends JsonDeserializer<UUID> {

    @Override
    public UUID deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }

        if (node.isTextual()) {
            String uuidString = node.asText();
            try {
                return UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                try {
                    long numericId = Long.parseLong(uuidString);
                    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", numericId));
                } catch (NumberFormatException ex) {
                    throw new IOException("Invalid UUID format: " + uuidString, e);
                }
            }
        } else if (node.isNumber()) {
            long numericId = node.asLong();
            return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", numericId));
        } else {
            throw new IOException("Cannot deserialize UUID from: " + node);
        }
    }
}
