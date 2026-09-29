package com.apimarketplace.catalog.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which caller parameters count as "I asked for more fields". A false positive only keeps a few
 * extra fields in the output; a false negative is the silent drop the change exists to end. The
 * cases below are spellings taken from real seeds.
 */
class RequestedFieldSelectionTest {

    @ParameterizedTest(name = "{0} is a field selection")
    @ValueSource(strings = {
        "tweet.fields", "user.fields", "media.fields", "expansions",   // X
        "$select", "$expand",                                          // Microsoft Graph, SharePoint
        "expand", "expand[]",                                          // Jira, Stripe
        "fields", "opt_fields", "include_fields",                      // Google, Asana, Zendesk
        "include", "embed", "properties", "select",                    // JSON:API, WP, HubSpot, Supabase
        "part", "readMask", "projection",                              // YouTube, Google People, GCS
        "outputFields", "propertiesWithHistory", "field_ids",          // camelCase, HubSpot, Crunchbase
        "card_field_ids", "cardFieldIds", "show-fields",               // Crunchbase, The Guardian
        "custom_output_fields", "return_fields",                       // Bright Data: "output" wins over "custom"
        "export_columns", "columns", "attributes",                     // Semrush, Checkmk, Personio
        "fields[articles]", "page.fields[x]",                          // JSON:API sparse fieldsets
        "  Tweet.Fields  "                                             // casing and stray spaces
    })
    void selectionNames(String name) {
        assertTrue(RequestedFieldSelection.isRequested(Map.of(name, "a,b")));
    }

    @ParameterizedTest(name = "{0} is not a field selection")
    @ValueSource(strings = {"id", "limit", "query", "include_deleted", "fieldset", "max_results", "expanded", "text", "[fields]"})
    void otherNames(String name) {
        assertFalse(RequestedFieldSelection.isRequested(Map.of(name, "a,b")));
    }

    /**
     * A name ending in "fields" whose prefix changes its meaning. exclude_fields asks the provider
     * for LESS: counting it would hand back every undeclared key, a bigger answer than a call with
     * no parameter at all. The others filter, sort, rank, merge or write fields.
     */
    @ParameterizedTest(name = "{0} ends in fields but does not select them")
    @ValueSource(strings = {"exclude_fields", "excludeFields", "search_fields", "sort_fields", "rank_fields",
        "merge_fields", "custom_fields", "required_fields", "dynamic_fields", "omit_fields", "filter_fields",
        "additional_sort_fields", "reportCustomFields", "updatedFields"})   // the word is not first
    void invertingOrRepurposingPrefixes(String name) {
        assertFalse(RequestedFieldSelection.isRequested(Map.of(name, "a,b")));
    }

    @Test
    @DisplayName("a list of names counts, as a caller may pass tweet.fields as an array")
    void listOfNamesCounts() {
        assertTrue(RequestedFieldSelection.isRequested(Map.of("tweet.fields", List.of("entities", "attachments"))));
    }

    @Test
    @DisplayName("a blank string asks for nothing")
    void blankStringDoesNotCount() {
        assertFalse(RequestedFieldSelection.isRequested(Map.of("tweet.fields", "   ")));
    }

    @Test
    @DisplayName("an empty list asks for nothing")
    void emptyListDoesNotCount() {
        assertFalse(RequestedFieldSelection.isRequested(Map.of("fields", List.of())));
    }

    @Test
    @DisplayName("an object value is a request body (HubSpot create properties), not a selection")
    void objectValueIsABody() {
        assertFalse(RequestedFieldSelection.isRequested(Map.of("properties", Map.of("email", "a@b.c"))));
    }

    @Test
    @DisplayName("a list of objects is a request body too, not a list of field names")
    void listOfObjectsIsABody() {
        assertFalse(RequestedFieldSelection.isRequested(Map.of("attributes", List.of(Map.of("id", 1, "value", "x")))));
    }

    @Test
    @DisplayName("a boolean flag named like a selection does not count")
    void booleanDoesNotCount() {
        assertFalse(RequestedFieldSelection.isRequested(Map.of("include", true)));
    }

    @Test
    @DisplayName("null or empty parameters, and a null value, are tolerated")
    void nullsTolerated() {
        assertFalse(RequestedFieldSelection.isRequested(null));
        assertFalse(RequestedFieldSelection.isRequested(Map.of()));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("fields", null);
        withNull.put(null, "x");
        assertFalse(RequestedFieldSelection.isRequested(withNull));
    }

    @Test
    @DisplayName("one selection among ordinary parameters is enough")
    void oneAmongMany() {
        assertTrue(RequestedFieldSelection.isRequested(
            Map.of("id", "2040536646546755584", "max_results", 20, "tweet.fields", "entities")));
    }
}
