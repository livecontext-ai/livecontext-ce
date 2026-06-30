package com.apimarketplace.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract that DB-stored {@code allowed_values} JSON arrays parse identically
 * across the workflow inspector, the catalog "get" endpoint, and the orchestrator-side
 * tool info endpoint. A divergence here would silently break the inspector dropdown
 * for one consumer while keeping it working for another.
 */
@DisplayName("AllowedValuesParser")
class AllowedValuesParserTest {

    @Nested
    @DisplayName("parseString - Object-typed input from JdbcTemplate.queryForList rows")
    class ParseObjectTests {

        @Test
        @DisplayName("returns null on null input (= no enum declared)")
        void returnsNullOnNull() {
            assertNull(AllowedValuesParser.parse(null));
            assertNull(AllowedValuesParser.parseString(null));
        }

        @Test
        @DisplayName("returns null on blank input")
        void returnsNullOnBlank() {
            assertNull(AllowedValuesParser.parseString(""));
            assertNull(AllowedValuesParser.parseString("   "));
        }

        @Test
        @DisplayName("returns null on empty array - empty enum carries no information")
        void returnsNullOnEmptyArray() {
            assertNull(AllowedValuesParser.parseString("[]"));
        }

        @Test
        @DisplayName("returns null on malformed JSON - never throws (defensive)")
        void returnsNullOnMalformedJson() {
            assertNull(AllowedValuesParser.parseString("{not valid"));
            assertNull(AllowedValuesParser.parseString("[\"unclosed"));
        }

        @Test
        @DisplayName("parses a non-empty array of strings into a typed list")
        void parsesValidArray() {
            List<String> result = AllowedValuesParser.parseString("[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-4.1\"]");

            assertNotNull(result);
            assertEquals(3, result.size());
            assertEquals("gpt-4o", result.get(0));
            assertEquals("gpt-4.1", result.get(2));
        }

        @Test
        @DisplayName("parse(Object) accepts any toString-able cell from JDBC")
        void parseObjectAcceptsAnyToString() {
            // Postgres TEXT comes back as String; PGobject would come as PGobject with .toString().
            Object pgObjectLike = new Object() {
                @Override
                public String toString() {
                    return "[\"a\",\"b\"]";
                }
            };

            List<String> result = AllowedValuesParser.parse(pgObjectLike);

            assertNotNull(result);
            assertEquals(List.of("a", "b"), result);
        }

        @Test
        @DisplayName("trims surrounding whitespace before parsing")
        void trimsWhitespace() {
            List<String> result = AllowedValuesParser.parseString("  [\"x\",\"y\"]  ");
            assertNotNull(result);
            assertEquals(2, result.size());
        }
    }
}
