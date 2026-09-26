package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

/**
 * Reads a verified API-catalog payload (the gzipped canonical JSON) WITHOUT holding it whole.
 *
 * <p><b>Why.</b> The payload gunzips to one JSON document ({@code apis}, {@code credentialTemplates},
 * {@code generationPrices}). Reading it as a byte array and then as a map tree costs several times
 * its raw size, and the raw size is 243 MB for the bundle activated on 2026-09-03 (931 APIs, 31,772
 * tools). The CE image runs a 1 GB heap, so every apply of that bundle died in
 * {@code OutOfMemoryError} at the gunzip. The merge never needed the whole list: it upserts one API
 * per transaction and only needs the templates and the present-id list at the end.
 *
 * <p><b>How.</b> Two passes over the same verified gzip bytes, both streamed:
 * <ol>
 *   <li>{@link #scan} validates the whole document and counts the APIs without materialising any,
 *       and returns the (small) templates and prices. Every refusal the applier makes on the
 *       payload's shape happens here, before a single row is written.</li>
 *   <li>{@link #apis} then yields the API objects one at a time, so the peak is the largest single
 *       API (about 23 MB of JSON today), not the catalog.</li>
 * </ol>
 * Key order in the document does not matter; unknown root keys are skipped.
 *
 * <p><b>Stricter than the whole-document parse it replaced, on purpose.</b> Every case below was
 * either accepted silently or failed part-way through a write before; a signed cloud bundle never
 * contains any of them, and each is now refused by {@link #scan} before a row is touched:
 * <ul>
 *   <li>data after the root object (the old parse ignored it);</li>
 *   <li>a second {@code apis} key (the old parse kept the last one);</li>
 *   <li>an {@code apis} entry that is not an object (was a ClassCastException mid-merge, after
 *       some APIs were already written);</li>
 *   <li>a {@code credentialTemplates} or {@code generationPrices} entry that is not an object
 *       (was a failure after the whole catalog had been merged).</li>
 * </ul>
 */
final class ApiCatalogPayloadStream {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {};

    static final String APIS = "apis";
    static final String TEMPLATES = "credentialTemplates";
    static final String PRICES = "generationPrices";

    private final ObjectMapper objectMapper;
    private final byte[] gzipped;

    ApiCatalogPayloadStream(ObjectMapper objectMapper, byte[] gzipped) {
        this.objectMapper = objectMapper;
        this.gzipped = gzipped;
    }

    /**
     * What the payload declares, read in one streaming pass.
     *
     * @param apisIsArray     {@code apis} is present and is an array (a missing or non-array
     *                        {@code apis} is what the applier reports as "payload has no 'apis' array")
     * @param duplicateApis   the document has more than one {@code apis} key
     * @param apiCount        number of entries in {@code apis}
     * @param nonObjectApis   entries of {@code apis} that are not JSON objects
     * @param templates       {@code credentialTemplates}, or an empty list when absent
     * @param prices          {@code generationPrices}, or null when absent ("says nothing about prices")
     */
    record Scan(boolean apisIsArray, boolean duplicateApis, int apiCount, int nonObjectApis,
                List<Map<String, Object>> templates, List<Map<String, Object>> prices) {}

    /** Validates the whole document and reads everything but the APIs themselves. */
    Scan scan() throws IOException {
        boolean apisIsArray = false;
        boolean duplicateApis = false;
        boolean apisSeen = false;
        int apiCount = 0;
        int nonObjectApis = 0;
        List<Map<String, Object>> templates = List.of();
        List<Map<String, Object>> prices = null;
        try (JsonParser p = open()) {
            expectRootObject(p);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (APIS.equals(field)) {
                    duplicateApis |= apisSeen;
                    apisSeen = true;
                }
                if (APIS.equals(field) && value == JsonToken.START_ARRAY) {
                    apisIsArray = true;
                    JsonToken t;
                    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                        apiCount++;
                        if (t != JsonToken.START_OBJECT) {
                            nonObjectApis++;
                        }
                        // Tokenises the entry, so a malformed API fails here, before any write.
                        p.skipChildren();
                    }
                } else if (TEMPLATES.equals(field) && value == JsonToken.START_ARRAY) {
                    templates = p.readValueAs(JSON_LIST);
                } else if (PRICES.equals(field) && value == JsonToken.START_ARRAY) {
                    prices = p.readValueAs(JSON_LIST);
                } else {
                    p.skipChildren();
                }
            }
            if (p.currentToken() != JsonToken.END_OBJECT || p.nextToken() != null) {
                throw new IOException("payload is not a single JSON object");
            }
        }
        return new Scan(apisIsArray, duplicateApis, apiCount, nonObjectApis, templates, prices);
    }

    /**
     * The {@code apis} entries, parsed one at a time as the merge asks for them. Call only after
     * {@link #scan} accepted the payload: a read failure surfaces as {@link UncheckedIOException}.
     * Each call to {@link Iterable#iterator()} opens a fresh pass.
     */
    Iterable<Map<String, Object>> apis() {
        return () -> new ApiIterator(open(APIS));
    }

    private JsonParser open() throws IOException {
        return objectMapper.getFactory().createParser(new GZIPInputStream(new ByteArrayInputStream(gzipped)));
    }

    /** A parser positioned just inside the {@code field} array, or null when it is absent. */
    private JsonParser open(String field) {
        JsonParser p = null;
        try {
            p = open();
            expectRootObject(p);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals(name) && value == JsonToken.START_ARRAY) {
                    return p;
                }
                p.skipChildren();
            }
            closeQuietly(p);
            return null;
        } catch (IOException e) {
            closeQuietly(p);
            throw new UncheckedIOException("could not read the '" + field + "' array of the catalog payload", e);
        }
    }

    /** Closes the parser and, through it, the gzip stream and its native inflater. */
    private static void closeQuietly(JsonParser p) {
        if (p == null) {
            return;
        }
        try {
            p.close();
        } catch (IOException ignored) {
            // Nothing left to read from it either way.
        }
    }

    private static void expectRootObject(JsonParser p) throws IOException {
        if (p.nextToken() != JsonToken.START_OBJECT) {
            throw new IOException("payload root is not a JSON object");
        }
    }

    private static final class ApiIterator implements Iterator<Map<String, Object>> {
        private final JsonParser parser;
        private JsonToken next;

        ApiIterator(JsonParser parser) {
            this.parser = parser;
            advance();
        }

        private void advance() {
            if (parser == null) {
                next = null;
                return;
            }
            try {
                next = parser.nextToken();
                if (next == JsonToken.END_ARRAY || next == null) {
                    next = null;
                    closeQuietly(parser);
                }
            } catch (IOException e) {
                next = null;
                closeQuietly(parser);
                throw new UncheckedIOException("could not read the next API of the catalog payload", e);
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Map<String, Object> next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            Map<String, Object> api;
            try {
                api = parser.readValueAs(JSON_MAP);
            } catch (IOException e) {
                next = null;
                closeQuietly(parser);
                throw new UncheckedIOException("could not read an API of the catalog payload", e);
            }
            advance();
            return api;
        }
    }
}
