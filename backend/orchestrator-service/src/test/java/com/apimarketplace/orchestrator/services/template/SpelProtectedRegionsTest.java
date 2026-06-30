package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SpelProtectedRegions - scanner")
class SpelProtectedRegionsTest {

    @Nested
    @DisplayName("String literals")
    class StringLiterals {

        @Test
        @DisplayName("Single-quoted string is one region")
        void singleQuoted() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("'hello'");
            assertEquals(1, regions.size());
            assertEquals(0, regions.get(0).start());
            assertEquals(7, regions.get(0).end());
        }

        @Test
        @DisplayName("Double-quoted string is one region")
        void doubleQuoted() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("\"hello\"");
            assertEquals(1, regions.size());
            assertEquals(0, regions.get(0).start());
            assertEquals(7, regions.get(0).end());
        }

        @Test
        @DisplayName("SpEL doubled-quote escape 'it''s' stays single region")
        void doubledQuoteEscape() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("'it''s'");
            assertEquals(1, regions.size());
            assertEquals(0, regions.get(0).start());
            assertEquals(7, regions.get(0).end());
        }

        @Test
        @DisplayName("Backslash escape in double-quoted string")
        void backslashEscape() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("\"say \\\"hi\\\"\"");
            assertEquals(1, regions.size());
            assertEquals(0, regions.get(0).start());
            assertEquals(12, regions.get(0).end());
        }

        @Test
        @DisplayName("Two strings → two regions")
        void twoStrings() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("'a' + 'b'");
            assertEquals(2, regions.size());
        }

        @Test
        @DisplayName("Unterminated string closes at EOF (no crash)")
        void unterminatedString() {
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find("'never ends");
            assertEquals(1, regions.size());
            assertEquals(0, regions.get(0).start());
            assertEquals(11, regions.get(0).end());
        }
    }

    @Nested
    @DisplayName("Selection / projection brackets")
    class Selection {

        @Test
        @DisplayName(".?[ is protected")
        void selectionAll() {
            String expr = "users.?[age > 18]";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            SpelProtectedRegions.Region r = regions.get(0);
            assertEquals(5, r.start());
            assertEquals(expr.length(), r.end());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("age"), regions));
        }

        @Test
        @DisplayName(".^[ (first match) is protected")
        void selectionFirst() {
            String expr = "headers.^[name == 'From']";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("name"), regions));
            assertFalse(SpelProtectedRegions.isProtected(0, regions), "identifier 'headers' outside region");
        }

        @Test
        @DisplayName(".$[ (last match) is protected")
        void selectionLast() {
            String expr = "items.$[price < 100]";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("price"), regions));
        }

        @Test
        @DisplayName(".![ (projection) is protected")
        void projection() {
            String expr = "users.![name]";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("name"), regions));
        }

        @Test
        @DisplayName("Chained selection+projection → two regions")
        void chainedSelectionProjection() {
            String expr = "users.?[age > 18].![name]";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(2, regions.size());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("age"), regions));
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("name"), regions));
        }

        @Test
        @DisplayName("Nested brackets inside selection do not end region prematurely")
        void nestedBrackets() {
            String expr = "users.?[roles[0] == 'admin']";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertEquals(expr.length(), regions.get(0).end());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("roles"), regions));
        }

        @Test
        @DisplayName("Closing ] inside string does not end region")
        void closingBracketInString() {
            String expr = "users.?[name == ']not end[']";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertEquals(expr.length(), regions.get(0).end());
        }

        @Test
        @DisplayName("Unterminated selection closes at EOF (no crash)")
        void unterminatedSelection() {
            String expr = "users.?[age > 18";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size());
            assertEquals(expr.length(), regions.get(0).end());
        }
    }

    @Nested
    @DisplayName("NOT protected (deliberately)")
    class NotProtected {

        @Test
        @DisplayName("Plain bracket index items[idx] → idx stays rewritable")
        void plainBracketIndex() {
            String expr = "items[idx]";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertTrue(regions.isEmpty(), "plain [idx] is not a protected region");
            assertFalse(SpelProtectedRegions.isProtected(expr.indexOf("idx"), regions));
        }

        @Test
        @DisplayName("Method call size(items) → items stays rewritable")
        void methodCall() {
            String expr = "size(items)";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertTrue(regions.isEmpty(), "plain (items) is not a protected region");
        }

        @Test
        @DisplayName("Quoted key in plain bracket → string region only")
        void quotedKeyPlainBracket() {
            String expr = "data['my key']";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            assertEquals(1, regions.size(), "only the quoted string is protected");
            assertEquals('\'', expr.charAt(regions.get(0).start()));
        }
    }

    @Nested
    @DisplayName("Mixed")
    class Mixed {

        @Test
        @DisplayName("String + selection in same expression")
        void stringAndSelection() {
            String expr = "prefix + headers.^[name == 'From'].value + 'end'";
            List<SpelProtectedRegions.Region> regions =
                SpelProtectedRegions.find(expr);
            // One selection region that *contains* the 'From' string (not merged) - and one final 'end' string.
            assertEquals(2, regions.size());
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("name"), regions));
            assertTrue(SpelProtectedRegions.isProtected(expr.indexOf("'end'"), regions));
            assertFalse(SpelProtectedRegions.isProtected(0, regions), "'prefix' identifier is rewritable");
            assertFalse(SpelProtectedRegions.isProtected(expr.indexOf(".value") + 1, regions),
                ".value after selection is property access, not protected by regions");
        }

        @Test
        @DisplayName("Null / empty input → empty list")
        void nullAndEmpty() {
            assertTrue(SpelProtectedRegions.find(null).isEmpty());
            assertTrue(SpelProtectedRegions.find("").isEmpty());
        }
    }
}
