package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.domain.ColumnType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ColumnValueCoercer")
class ColumnValueCoercerTest {

    private ColumnValueCoercer coercer;

    @BeforeEach
    void setUp() {
        coercer = new ColumnValueCoercer();
    }

    private CoercionResult coerce(Object value, ColumnType type) {
        return coercer.coerce(value, type);
    }

    private CoercionResult coerce(Object value, ColumnType type, Map<String, Object> display) {
        return coercer.coerce(value, type, display);
    }

    // ── NULL / NULL TYPE ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Null handling")
    class NullHandling {

        @Test
        void nullValuePassesThrough() {
            CoercionResult r = coerce(null, ColumnType.TEXT);
            assertNull(r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void nullTypePassesThrough() {
            CoercionResult r = coercer.coerce("hello", null);
            assertEquals("hello", r.value());
            assertFalse(r.hasWarnings());
        }
    }

    // ── TEXT ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TEXT coercion")
    class TextCoercion {

        @Test
        void stringPassesThrough() {
            assertEquals("hello", coerce("hello", ColumnType.TEXT).value());
        }

        @Test
        void numberConvertsToString() {
            assertEquals("42", coerce(42, ColumnType.TEXT).value());
        }

        @Test
        void mapConvertsToJson() {
            Object result = coerce(Map.of("key", "val"), ColumnType.TEXT).value();
            assertTrue(result instanceof String);
            assertTrue(((String) result).contains("key"));
        }

        @Test
        void listConvertsToJson() {
            Object result = coerce(List.of("a", "b"), ColumnType.TEXT).value();
            assertTrue(result instanceof String);
            assertTrue(((String) result).contains("a"));
        }

        @Test
        void booleanConvertsToString() {
            assertEquals("true", coerce(true, ColumnType.TEXT).value());
        }

        @Test
        void fileRefExtractsReadableText() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant/wf/run/step/report.pdf");
            fileRef.put("name", "report.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 102400);
            fileRef.put("id", "77777777-7777-7777-7777-777777777777");

            String result = (String) coerce(fileRef, ColumnType.TEXT).value();
            assertTrue(result.contains("report.pdf"));
            assertTrue(result.contains("/api/proxy/files/by-id/"));
        }

        @Test
        void dbFlattenedFileExtractsReadableText() {
            Map<String, Object> dbFormat = new java.util.LinkedHashMap<>();
            dbFormat.put("file_url", "/api/proxy/files/proxy?key=t%2Ff.pdf");
            dbFormat.put("file_name", "f.pdf");
            dbFormat.put("content_type", "application/pdf");
            dbFormat.put("file_size", 100);

            String result = (String) coerce(dbFormat, ColumnType.TEXT).value();
            assertTrue(result.contains("f.pdf"));
            assertTrue(result.contains("/api/proxy/files/proxy"));
        }

        @Test
        void nonFileMapStillConvertsToJson() {
            Object result = coerce(Map.of("key", "val"), ColumnType.TEXT).value();
            assertTrue(result instanceof String);
            assertTrue(((String) result).contains("key"));
        }
    }

    // ── NUMBER ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NUMBER coercion")
    class NumberCoercion {

        @Test
        void integerPassesThrough() {
            assertEquals(42, coerce(42, ColumnType.NUMBER).value());
        }

        @Test
        void doublePassesThrough() {
            assertEquals(3.14, coerce(3.14, ColumnType.NUMBER).value());
        }

        @Test
        void stringIntegerParsed() {
            assertEquals(42L, coerce("42", ColumnType.NUMBER).value());
        }

        @Test
        void stringDoubleParsed() {
            assertEquals(3.14, coerce("3.14", ColumnType.NUMBER).value());
        }

        @Test
        void emptyStringReturnsZero() {
            assertEquals(0, coerce("", ColumnType.NUMBER).value());
        }

        @Test
        void booleanTrueReturnsOne() {
            assertEquals(1, coerce(true, ColumnType.NUMBER).value());
        }

        @Test
        void booleanFalseReturnsZero() {
            assertEquals(0, coerce(false, ColumnType.NUMBER).value());
        }

        @Test
        void currencyStripped() {
            CoercionResult r = coerce("$1,250", ColumnType.NUMBER);
            assertEquals(1250L, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void unparseableReturnsNullWithWarning() {
            CoercionResult r = coerce("abc", ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void scientificNotation() {
            assertEquals(1.5e3, coerce("1.5e3", ColumnType.NUMBER).value());
        }

        @Test
        void negativeNumber() {
            assertEquals(-42L, coerce("-42", ColumnType.NUMBER).value());
        }

        // --- Audit gap fixes ---

        @Test
        void nanReturnsNullWithWarning() {
            CoercionResult r = coerce(Double.NaN, ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void infinityReturnsNullWithWarning() {
            CoercionResult r = coerce(Double.POSITIVE_INFINITY, ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void negativeInfinityReturnsNullWithWarning() {
            CoercionResult r = coerce(Double.NEGATIVE_INFINITY, ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void nanStringReturnsNullWithWarning() {
            CoercionResult r = coerce("NaN", ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void infinityStringReturnsNullWithWarning() {
            CoercionResult r = coerce("Infinity", ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void europeanDecimalComma() {
            CoercionResult r = coerce("3,14", ColumnType.NUMBER);
            assertEquals(3.14, r.value());
            assertTrue(r.hasWarnings()); // warning about comma interpretation
        }

        @Test
        void europeanDecimalCommaLarger() {
            CoercionResult r = coerce("1234,56", ColumnType.NUMBER);
            assertEquals(1234.56, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void largeIntegerStringBeyondLong() {
            // Should not crash, returns as double
            CoercionResult r = coerce("99999999999999999999", ColumnType.NUMBER);
            assertNotNull(r.value());
            assertTrue(r.value() instanceof Number);
        }

        @Test
        void floatNanReturnsNullWithWarning() {
            CoercionResult r = coerce(Float.NaN, ColumnType.NUMBER);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }
    }

    // ── DATE ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DATE coercion")
    class DateCoercion {

        // --- ISO formats (no warning, passthrough) ---

        @Test
        void isoDatePassesThrough() {
            assertEquals("2024-01-15", coerce("2024-01-15", ColumnType.DATE).value());
            assertFalse(coerce("2024-01-15", ColumnType.DATE).hasWarnings());
        }

        @Test
        void isoInstantPassesThrough() {
            assertEquals("2024-01-15T10:30:00Z", coerce("2024-01-15T10:30:00Z", ColumnType.DATE).value());
        }

        @Test
        void isoInstantWithMillisPassesThrough() {
            assertEquals("2024-01-15T10:30:00.123Z", coerce("2024-01-15T10:30:00.123Z", ColumnType.DATE).value());
        }

        @Test
        void isoLocalDateTimePassesThrough() {
            assertEquals("2024-01-15T10:30:00", coerce("2024-01-15T10:30:00", ColumnType.DATE).value());
        }

        @Test
        void isoLocalDateTimeWithMillisPassesThrough() {
            assertEquals("2024-01-15T10:30:00.500", coerce("2024-01-15T10:30:00.500", ColumnType.DATE).value());
        }

        // --- Offset date-time → converted to UTC instant ---

        @Test
        void isoOffsetDateTime() {
            CoercionResult r = coerce("2024-01-15T10:30:00+02:00", ColumnType.DATE);
            assertEquals("2024-01-15T08:30:00Z", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void isoOffsetDateTimeNegative() {
            CoercionResult r = coerce("2024-01-15T10:30:00-05:00", ColumnType.DATE);
            assertEquals("2024-01-15T15:30:00Z", r.value());
        }

        // --- Epoch numbers ---

        @Test
        void epochMillisConverted() {
            CoercionResult r = coerce(1705312200000L, ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().startsWith("2024-01-15"));
        }

        @Test
        void epochSecondsConverted() {
            // 1705312200 seconds = same instant as 1705312200000 millis
            CoercionResult r = coerce(1705312200L, ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().startsWith("2024-01-15"));
        }

        @Test
        void epochSecondsVsMillisHeuristic() {
            // Small number → seconds
            CoercionResult sec = coerce(0L, ColumnType.DATE);
            assertEquals("1970-01-01T00:00:00Z", sec.value());

            // Large number → millis
            CoercionResult ms = coerce(1705312200000L, ColumnType.DATE);
            assertTrue(ms.value().toString().startsWith("2024-01-15"));
        }

        @Test
        void epochAsStringParsed() {
            CoercionResult r = coerce("1705312200", ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().startsWith("2024-01-15"));
        }

        // --- RFC 1123 / 2822 ---

        @Test
        void rfc1123Format() {
            CoercionResult r = coerce("Mon, 15 Jan 2024 10:30:00 GMT", ColumnType.DATE);
            assertEquals("2024-01-15T10:30:00Z", r.value());
            assertTrue(r.hasWarnings());
        }

        // --- Compact dates ---

        @Test
        void compactDateOnly() {
            CoercionResult r = coerce("20240115", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void compactDateTime() {
            CoercionResult r = coerce("20240115T103000", ColumnType.DATE);
            assertEquals("2024-01-15T10:30:00", r.value());
            assertTrue(r.hasWarnings());
        }

        // --- Date+time fallback formats (with warning) ---

        @Test
        void dateTimeSpaceSeparator() {
            CoercionResult r = coerce("2024-01-15 10:30:00", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void dateTimeSpaceSeparatorMinutesOnly() {
            CoercionResult r = coerce("2024-01-15 10:30", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddSlashMmSlashYyyyHhMmSs() {
            CoercionResult r = coerce("15/01/2024 10:30:00", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void mmSlashDdSlashYyyyHhMmSs() {
            CoercionResult r = coerce("01/15/2024 10:30:00", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddDotMmDotYyyyHhMmSs() {
            CoercionResult r = coerce("15.01.2024 10:30:00", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddDotMmDotYyyyHhMm() {
            CoercionResult r = coerce("15.01.2024 10:30", ColumnType.DATE);
            assertEquals("2024-01-15T10:30", r.value());
            assertTrue(r.hasWarnings());
        }

        // --- Date-only fallback formats (with warning) ---

        @Test
        void mmSlashDdSlashYyyy() {
            CoercionResult r = coerce("01/15/2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddSlashMmSlashYyyy() {
            CoercionResult r = coerce("15/01/2024", ColumnType.DATE);
            // Could be dd/MM or MM/dd - MM/dd tried first, fails for day=15, so dd/MM matches
            assertNotNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddDotMmDotYyyy() {
            CoercionResult r = coerce("15.01.2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddDashMmDashYyyy() {
            CoercionResult r = coerce("15-01-2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void yyyySlashMmSlashDd() {
            CoercionResult r = coerce("2024/01/15", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void mmmDdCommaYyyy() {
            CoercionResult r = coerce("Jan 15, 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void mmmDdYyyy() {
            CoercionResult r = coerce("Jan 15 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void fullMonthDdCommaYyyy() {
            CoercionResult r = coerce("January 15, 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void fullMonthDdYyyy() {
            CoercionResult r = coerce("January 15 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddMmmYyyy() {
            CoercionResult r = coerce("15 Jan 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void singleDigitDayMmmYyyy() {
            CoercionResult r = coerce("5 Jan 2024", ColumnType.DATE);
            assertEquals("2024-01-05", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ddFullMonthYyyy() {
            CoercionResult r = coerce("15 January 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        // --- Database timestamps with fractional seconds ---

        @Test
        void databaseTimestampWithMicroseconds() {
            CoercionResult r = coerce("2024-01-15 10:30:00.123456", ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().contains("2024-01-15"));
            assertTrue(r.hasWarnings());
        }

        @Test
        void databaseTimestampWithMilliseconds() {
            CoercionResult r = coerce("2024-01-15 10:30:00.123", ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().contains("2024-01-15"));
            assertTrue(r.hasWarnings());
        }

        // --- Ordinal dates ---

        @Test
        void ordinalDate() {
            CoercionResult r = coerce("January 15th, 2024", ColumnType.DATE);
            assertEquals("2024-01-15", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ordinalDateSt() {
            CoercionResult r = coerce("January 1st, 2024", ColumnType.DATE);
            assertEquals("2024-01-01", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ordinalDateNd() {
            CoercionResult r = coerce("January 2nd, 2024", ColumnType.DATE);
            assertEquals("2024-01-02", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void ordinalDateRd() {
            CoercionResult r = coerce("January 3rd, 2024", ColumnType.DATE);
            assertEquals("2024-01-03", r.value());
            assertTrue(r.hasWarnings());
        }

        // --- Timezone abbreviation stripping ---

        @Test
        void datetimeWithUtcSuffix() {
            CoercionResult r = coerce("2024-01-15 10:30:00 UTC", ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().contains("2024-01-15"));
            assertTrue(r.hasWarnings());
        }

        @Test
        void datetimeWithEstSuffix() {
            CoercionResult r = coerce("2024-01-15 10:30:00 EST", ColumnType.DATE);
            assertNotNull(r.value());
            assertTrue(r.value().toString().contains("2024-01-15"));
            assertTrue(r.hasWarnings());
        }

        // --- Time-only values (stored with sentinel date 1970-01-01) ---

        @Test
        void timeOnlyHhMm() {
            CoercionResult r = coerce("14:30", ColumnType.DATE);
            assertEquals("1970-01-01T14:30", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlyHhMmSs() {
            CoercionResult r = coerce("14:30:00", ColumnType.DATE);
            assertEquals("1970-01-01T14:30", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlyHhMmSsWithMillis() {
            CoercionResult r = coerce("14:30:00.123", ColumnType.DATE);
            assertEquals("1970-01-01T14:30:00.123", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlyMidnight() {
            CoercionResult r = coerce("00:00", ColumnType.DATE);
            assertEquals("1970-01-01T00:00", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlyEndOfDay() {
            CoercionResult r = coerce("23:59:59", ColumnType.DATE);
            assertEquals("1970-01-01T23:59:59", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlyWithDateFormatDisplay() {
            CoercionResult r = coerce("09:05", ColumnType.DATE, Map.of("dateFormat", "time"));
            assertEquals("1970-01-01T09:05", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void timeOnlySingleDigitHour() {
            CoercionResult r = coerce("9:05", ColumnType.DATE);
            assertEquals("1970-01-01T09:05", r.value());
            assertFalse(r.hasWarnings());
        }

        // --- Edge cases ---

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.DATE).value());
        }

        @Test
        void blankStringReturnsNull() {
            assertNull(coerce("   ", ColumnType.DATE).value());
        }

        @Test
        void unparseableReturnsNullWithWarning() {
            CoercionResult r = coerce("not-a-date", ColumnType.DATE);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void booleanFails() {
            CoercionResult r = coerce(true, ColumnType.DATE);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }
    }

    // ── CHECKBOX ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CHECKBOX coercion")
    class CheckboxCoercion {

        @Test
        void booleanPassesThrough() {
            assertEquals(true, coerce(true, ColumnType.CHECKBOX).value());
            assertEquals(false, coerce(false, ColumnType.CHECKBOX).value());
        }

        @Test
        void truthyStrings() {
            assertEquals(true, coerce("true", ColumnType.CHECKBOX).value());
            assertEquals(true, coerce("yes", ColumnType.CHECKBOX).value());
            assertEquals(true, coerce("1", ColumnType.CHECKBOX).value());
            assertEquals(true, coerce("on", ColumnType.CHECKBOX).value());
        }

        @Test
        void falsyStrings() {
            assertEquals(false, coerce("false", ColumnType.CHECKBOX).value());
            assertEquals(false, coerce("no", ColumnType.CHECKBOX).value());
            assertEquals(false, coerce("0", ColumnType.CHECKBOX).value());
            assertEquals(false, coerce("abc", ColumnType.CHECKBOX).value());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.CHECKBOX).value());
        }

        @Test
        void nullStringReturnsNull() {
            assertNull(coerce("null", ColumnType.CHECKBOX).value());
        }

        @Test
        void noneStringReturnsNull() {
            assertNull(coerce("none", ColumnType.CHECKBOX).value());
        }

        @Test
        void numberOneIsTrue() {
            assertEquals(true, coerce(1, ColumnType.CHECKBOX).value());
        }

        @Test
        void numberZeroIsFalse() {
            assertEquals(false, coerce(0, ColumnType.CHECKBOX).value());
        }

        @Test
        void nullPassesThrough() {
            assertNull(coerce(null, ColumnType.CHECKBOX).value());
        }
    }

    // ── SELECT ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SELECT coercion")
    class SelectCoercion {

        private final Map<String, Object> display = Map.of(
                "options", List.of(
                        Map.of("value", "opt1", "label", "Option One"),
                        Map.of("value", "opt2", "label", "Option Two")
                )
        );

        @Test
        void matchByValueExact() {
            assertEquals("opt1", coerce("opt1", ColumnType.SELECT, display).value());
        }

        @Test
        void matchByValueCaseInsensitive() {
            assertEquals("opt1", coerce("OPT1", ColumnType.SELECT, display).value());
        }

        @Test
        void matchByLabel() {
            CoercionResult r = coerce("Option One", ColumnType.SELECT, display);
            assertEquals("opt1", r.value());
        }

        @Test
        void matchByLabelCaseInsensitive() {
            assertEquals("opt1", coerce("option one", ColumnType.SELECT, display).value());
        }

        @Test
        void noMatchWarns() {
            CoercionResult r = coerce("unknown", ColumnType.SELECT, display);
            assertEquals("unknown", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void noOptionsPassesThrough() {
            CoercionResult r = coerce("anything", ColumnType.SELECT, Map.of());
            assertEquals("anything", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.SELECT, display).value());
        }

        @Test
        void simpleStringOptions() {
            Map<String, Object> simpleDisplay = Map.of("options", List.of("red", "green", "blue"));
            assertEquals("red", coerce("RED", ColumnType.SELECT, simpleDisplay).value());
        }

        @Test
        void numericDoubleMatchesStringOption() {
            // 42.0 should match "42" option
            Map<String, Object> numericDisplay = Map.of(
                    "options", List.of(Map.of("value", "42", "label", "Forty Two"))
            );
            assertEquals("42", coerce(42.0, ColumnType.SELECT, numericDisplay).value());
        }

        @Test
        void integerMatchesStringOption() {
            Map<String, Object> numericDisplay = Map.of(
                    "options", List.of(Map.of("value", "42", "label", "Forty Two"))
            );
            assertEquals("42", coerce(42, ColumnType.SELECT, numericDisplay).value());
        }
    }

    // ── MULTI_SELECT ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("MULTI_SELECT coercion")
    class MultiSelectCoercion {

        @Test
        void listPassesThrough() {
            List<String> list = List.of("a", "b");
            assertEquals(list, coerce(list, ColumnType.MULTI_SELECT).value());
        }

        @Test
        void commaSeparatedSplit() {
            assertEquals(List.of("a", "b", "c"), coerce("a, b, c", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void jsonArrayParsed() {
            assertEquals(List.of("x", "y"), coerce("[\"x\",\"y\"]", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void singleValueWrapped() {
            assertEquals(List.of("42"), coerce(42, ColumnType.MULTI_SELECT).value());
        }

        @Test
        void emptyStringReturnsEmptyList() {
            assertEquals(List.of(), coerce("", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void blankStringReturnsEmptyList() {
            assertEquals(List.of(), coerce("   ", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void semicolonSeparated() {
            assertEquals(List.of("a", "b", "c"), coerce("a; b; c", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void pipeSeparated() {
            assertEquals(List.of("a", "b", "c"), coerce("a|b|c", ColumnType.MULTI_SELECT).value());
        }

        @Test
        void listWithMixedTypes() {
            // List<Object> with non-String elements → all converted to strings
            List<Object> mixed = List.of("text", 42, true);
            List<String> result = (List<String>) coerce(mixed, ColumnType.MULTI_SELECT).value();
            assertEquals(List.of("text", "42", "true"), result);
        }
    }

    // ── RATING ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RATING coercion")
    class RatingCoercion {

        @Test
        void integerInRange() {
            assertEquals(3, coerce(3, ColumnType.RATING).value());
        }

        @Test
        void stringParsed() {
            assertEquals(4, coerce("4", ColumnType.RATING).value());
        }

        @Test
        void decimalRounded() {
            assertEquals(4, coerce(3.7, ColumnType.RATING).value());
        }

        @Test
        void negativeClampedToZero() {
            CoercionResult r = coerce(-1, ColumnType.RATING);
            assertEquals(0, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void aboveMaxClampedToMax() {
            CoercionResult r = coerce(10, ColumnType.RATING);
            assertEquals(5, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void customMaxFromDisplay() {
            CoercionResult r = coerce(8, ColumnType.RATING, Map.of("max", 10));
            assertEquals(8, r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void aboveCustomMaxClamped() {
            CoercionResult r = coerce(15, ColumnType.RATING, Map.of("max", 10));
            assertEquals(10, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.RATING).value());
        }

        @Test
        void invalidStringFails() {
            CoercionResult r = coerce("abc", ColumnType.RATING);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void extremelyLargeValueClampsToMax() {
            // Should not overflow to negative - should clamp to max
            CoercionResult r = coerce(1e18, ColumnType.RATING);
            assertEquals(5, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void extremelyLargeNegativeValueClampsToMin() {
            CoercionResult r = coerce(-1e18, ColumnType.RATING);
            assertEquals(0, r.value());
            assertTrue(r.hasWarnings());
        }
    }

    // ── SENTIMENT ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SENTIMENT coercion")
    class SentimentCoercion {

        @Test
        void positiveAliases() {
            assertEquals("up", coerce("up", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("positive", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("yes", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("good", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("like", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("thumbsup", ColumnType.SENTIMENT).value());
        }

        @Test
        void negativeAliases() {
            assertEquals("down", coerce("down", ColumnType.SENTIMENT).value());
            assertEquals("down", coerce("negative", ColumnType.SENTIMENT).value());
            assertEquals("down", coerce("no", ColumnType.SENTIMENT).value());
            assertEquals("down", coerce("bad", ColumnType.SENTIMENT).value());
        }

        @Test
        void unknownDefaultsToNeutral() {
            assertEquals("neutral", coerce("unknown", ColumnType.SENTIMENT).value());
            assertEquals("neutral", coerce("maybe", ColumnType.SENTIMENT).value());
        }

        @Test
        void booleanTrueIsUp() {
            assertEquals("up", coerce(true, ColumnType.SENTIMENT).value());
        }

        @Test
        void booleanFalseIsDown() {
            assertEquals("down", coerce(false, ColumnType.SENTIMENT).value());
        }

        @Test
        void positiveNumberIsUp() {
            assertEquals("up", coerce(1, ColumnType.SENTIMENT).value());
            assertEquals("up", coerce(42.5, ColumnType.SENTIMENT).value());
        }

        @Test
        void negativeNumberIsDown() {
            assertEquals("down", coerce(-1, ColumnType.SENTIMENT).value());
        }

        @Test
        void zeroIsNeutral() {
            assertEquals("neutral", coerce(0, ColumnType.SENTIMENT).value());
        }

        @Test
        void numericStringPositiveIsUp() {
            assertEquals("up", coerce("1", ColumnType.SENTIMENT).value());
            assertEquals("up", coerce("+1", ColumnType.SENTIMENT).value());
        }

        @Test
        void numericStringNegativeIsDown() {
            assertEquals("down", coerce("-1", ColumnType.SENTIMENT).value());
        }

        @Test
        void numericStringZeroIsNeutral() {
            assertEquals("neutral", coerce("0", ColumnType.SENTIMENT).value());
        }

        @Test
        void trueStringIsUp() {
            assertEquals("up", coerce("true", ColumnType.SENTIMENT).value());
        }

        @Test
        void falseStringIsDown() {
            assertEquals("down", coerce("false", ColumnType.SENTIMENT).value());
        }
    }

    // ── PROGRESS ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PROGRESS coercion")
    class ProgressCoercion {

        @Test
        void integerInRange() {
            assertEquals(75, coerce(75, ColumnType.PROGRESS).value());
        }

        @Test
        void percentStripped() {
            assertEquals(75, coerce("75%", ColumnType.PROGRESS).value());
        }

        @Test
        void fractionScaled() {
            // 0.75 with default max=100 → 75
            CoercionResult r = coerce(0.75, ColumnType.PROGRESS);
            assertEquals(75, r.value());
            assertTrue(r.hasWarnings()); // fraction scaling note
        }

        @Test
        void negativeClampedToZero() {
            CoercionResult r = coerce(-5, ColumnType.PROGRESS);
            assertEquals(0, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void aboveMaxClamped() {
            CoercionResult r = coerce(150, ColumnType.PROGRESS);
            assertEquals(100, r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void customMaxFromDisplay() {
            CoercionResult r = coerce(8, ColumnType.PROGRESS, Map.of("max", 10));
            assertEquals(8, r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.PROGRESS).value());
        }

        @Test
        void stringParsed() {
            assertEquals(50, coerce("50", ColumnType.PROGRESS).value());
        }

        @Test
        void stringFractionScaledSameAsDouble() {
            // "0.75" string should produce same result as double 0.75 → 75
            CoercionResult fromString = coerce("0.75", ColumnType.PROGRESS);
            CoercionResult fromDouble = coerce(0.75, ColumnType.PROGRESS);
            assertEquals(fromDouble.value(), fromString.value());
            assertEquals(75, fromString.value());
        }

        @Test
        void extremelyLargeValueClampsToMax() {
            CoercionResult r = coerce(1e18, ColumnType.PROGRESS);
            assertEquals(100, r.value());
            assertTrue(r.hasWarnings());
        }
    }

    // ── FILE ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FILE coercion")
    class FileCoercion {

        // --- Canonical format {url, name, mimeType, size} passthrough ---

        @Test
        void canonicalMapPassesThrough() {
            Map<String, Object> fileMap = Map.of("url", "https://example.com/file.pdf", "name", "file.pdf");
            assertEquals(fileMap, coerce(fileMap, ColumnType.FILE).value());
            assertFalse(coerce(fileMap, ColumnType.FILE).hasWarnings());
        }

        // --- FileRef format {_type:"file", path, name, mimeType, size} ---

        @Test
        @SuppressWarnings("unchecked")
        void fileRefNormalized() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant123/wf456/run789/step1/report.pdf");
            fileRef.put("name", "report.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 102400);
            fileRef.put("id", "11111111-1111-1111-1111-111111111111");

            CoercionResult r = coerce(fileRef, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            // Opaque, id-based url - addresses the storage row, never the s3 key (no tenant id leak).
            assertEquals("/api/proxy/files/by-id/11111111-1111-1111-1111-111111111111/raw?disposition=inline",
                    result.get("url"));
            assertEquals("report.pdf", result.get("name"));
            assertEquals("application/pdf", result.get("mimeType"));
            assertEquals(102400, result.get("size"));
        }

        // --- DB flattened format {file_url, file_name, content_type, file_size} ---

        @Test
        @SuppressWarnings("unchecked")
        void dbFlattenedNormalized() {
            Map<String, Object> dbFormat = new java.util.LinkedHashMap<>();
            dbFormat.put("file_url", "/api/proxy/files/proxy?key=tenant%2Ffile.pdf");
            dbFormat.put("file_name", "file.pdf");
            dbFormat.put("content_type", "application/pdf");
            dbFormat.put("file_size", 51200);
            dbFormat.put("source_url", "https://example.com/original.pdf");

            CoercionResult r = coerce(dbFormat, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertEquals("/api/proxy/files/proxy?key=tenant%2Ffile.pdf", result.get("url"));
            assertEquals("file.pdf", result.get("name"));
            assertEquals("application/pdf", result.get("mimeType"));
            assertEquals(51200, result.get("size"));
            assertEquals("https://example.com/original.pdf", result.get("source_url"));
        }

        // --- Generic upload format {url, storageKey, fileName, mimeType, size} ---

        @Test
        @SuppressWarnings("unchecked")
        void genericUploadNormalized() {
            Map<String, Object> upload = new java.util.LinkedHashMap<>();
            upload.put("url", "/api/proxy/files/proxy?key=tenant%2Ffile.jpg");
            upload.put("storageKey", "tenant/general/datatable/uuid_file.jpg");
            upload.put("fileName", "file.jpg");
            upload.put("mimeType", "image/jpeg");
            upload.put("size", 30000);

            CoercionResult r = coerce(upload, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            // Has url and storageKey → normalized (not passthrough)
            assertNotNull(result.get("url"));
            assertEquals("file.jpg", result.get("name"));
            assertEquals("image/jpeg", result.get("mimeType"));
        }

        // --- FileRef with storageKey only (no url) ---

        @Test
        @SuppressWarnings("unchecked")
        void storageKeyOnlyBuildsProxyUrl() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("storageKey", "tenant/wf/run/step/file.pdf");
            map.put("fileName", "file.pdf");
            map.put("id", "22222222-2222-2222-2222-222222222222");

            CoercionResult r = coerce(map, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertTrue(result.get("url").toString().contains("/api/proxy/files/by-id/"));
            assertEquals("file.pdf", result.get("name"));
        }

        // --- URL string ---

        @Test
        @SuppressWarnings("unchecked")
        void urlStringBuildsMap() {
            CoercionResult r = coerce("https://example.com/file.pdf", ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertEquals("https://example.com/file.pdf", result.get("url"));
            assertEquals("file.pdf", result.get("name"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void proxyPathStringBuildsMap() {
            CoercionResult r = coerce("/api/proxy/files/proxy?key=tenant%2Ffile.pdf", ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertEquals("/api/proxy/files/proxy?key=tenant%2Ffile.pdf", result.get("url"));
        }

        // --- JSON string ---

        @Test
        @SuppressWarnings("unchecked")
        void jsonCanonicalParsed() {
            CoercionResult r = coerce("{\"url\":\"https://example.com/a.pdf\",\"name\":\"a.pdf\"}", ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertEquals("https://example.com/a.pdf", result.get("url"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void jsonFileRefParsed() {
            String json = "{\"_type\":\"file\",\"path\":\"t/wf/run/step/f.pdf\",\"name\":\"f.pdf\",\"mimeType\":\"application/pdf\",\"size\":100,\"id\":\"33333333-3333-3333-3333-333333333333\"}";
            CoercionResult r = coerce(json, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertTrue(result.get("url").toString().contains("/api/proxy/files/by-id/"));
            assertEquals("f.pdf", result.get("name"));
        }

        // --- Edge cases ---

        @Test
        void mapWithNoRecognizableUrlWarns() {
            Map<String, Object> map = Map.of("name", "file.pdf", "something", "else");
            CoercionResult r = coerce(map, ColumnType.FILE);
            assertTrue(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.FILE).value());
        }

        @Test
        void nonUrlStringWarns() {
            CoercionResult r = coerce("not-a-url", ColumnType.FILE);
            assertTrue(r.hasWarnings());
        }

        @Test
        @SuppressWarnings("unchecked")
        void dataUriAcceptedAsFile() {
            String dataUri = "data:application/pdf;base64,JVBERi0xLjQ=";
            CoercionResult r = coerce(dataUri, ColumnType.FILE);
            Map<String, Object> result = (Map<String, Object>) r.value();
            assertEquals(dataUri, result.get("url"));
            assertFalse(r.hasWarnings());
        }
    }

    // ── IMAGE ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IMAGE coercion")
    class ImageCoercion {

        // --- URL string passthrough ---

        @Test
        void urlStringPassesThrough() {
            assertEquals("https://example.com/img.png", coerce("https://example.com/img.png", ColumnType.IMAGE).value());
        }

        @Test
        void proxyUrlPassesThrough() {
            assertEquals("/api/proxy/files/proxy?key=t%2Fimg.png", coerce("/api/proxy/files/proxy?key=t%2Fimg.png", ColumnType.IMAGE).value());
        }

        // --- Map with url ---

        @Test
        void mapWithUrlExtracted() {
            Map<String, Object> map = Map.of("url", "https://example.com/img.png", "alt", "test");
            assertEquals("https://example.com/img.png", coerce(map, ColumnType.IMAGE).value());
        }

        // --- FileRef → proxy URL ---

        @Test
        void fileRefConvertsToProxyUrl() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant/wf/run/step/img.png");
            fileRef.put("name", "img.png");
            fileRef.put("mimeType", "image/png");
            fileRef.put("size", 5000);
            fileRef.put("id", "44444444-4444-4444-4444-444444444444");

            CoercionResult r = coerce(fileRef, ColumnType.IMAGE);
            assertEquals("/api/proxy/files/by-id/44444444-4444-4444-4444-444444444444/raw?disposition=inline",
                    r.value());
        }

        // --- DB flattened → extracts file_url ---

        @Test
        void dbFlattenedExtractsFileUrl() {
            Map<String, Object> dbFormat = Map.of(
                    "file_url", "/api/proxy/files/proxy?key=t%2Fimg.png",
                    "file_name", "img.png",
                    "content_type", "image/png",
                    "file_size", 5000
            );
            assertEquals("/api/proxy/files/proxy?key=t%2Fimg.png", coerce(dbFormat, ColumnType.IMAGE).value());
        }

        // --- storageKey only → builds proxy URL ---

        @Test
        void storageKeyBuildsProxyUrl() {
            Map<String, Object> map = Map.of("storageKey", "tenant/img.png", "id", "55555555-5555-5555-5555-555555555555");
            CoercionResult r = coerce(map, ColumnType.IMAGE);
            assertTrue(r.value().toString().contains("/api/proxy/files/by-id/"));
        }

        // --- JSON string ---

        @Test
        void jsonStringExtractsUrl() {
            CoercionResult r = coerce("{\"url\":\"https://example.com/img.png\"}", ColumnType.IMAGE);
            assertEquals("https://example.com/img.png", r.value());
        }

        @Test
        void jsonFileRefExtractsProxyUrl() {
            String json = "{\"_type\":\"file\",\"path\":\"t/img.png\",\"name\":\"img.png\",\"mimeType\":\"image/png\",\"size\":100,\"id\":\"66666666-6666-6666-6666-666666666666\"}";
            CoercionResult r = coerce(json, ColumnType.IMAGE);
            assertTrue(r.value().toString().contains("/api/proxy/files/by-id/"));
        }

        // --- Edge cases ---

        @Test
        void mapWithNoUrlWarns() {
            Map<String, Object> map = Map.of("name", "img.png");
            CoercionResult r = coerce(map, ColumnType.IMAGE);
            assertTrue(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.IMAGE).value());
        }

        @Test
        void nonUrlStringWarns() {
            CoercionResult r = coerce("not-a-url", ColumnType.IMAGE);
            assertTrue(r.hasWarnings());
        }

        @Test
        void dataUriAccepted() {
            String dataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ";
            CoercionResult r = coerce(dataUri, ColumnType.IMAGE);
            assertEquals(dataUri, r.value());
            assertFalse(r.hasWarnings());
        }
    }

    // ── EMAIL ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EMAIL coercion")
    class EmailCoercion {

        @Test
        void validEmailTrimmedAndLowercased() {
            assertEquals("user@example.com", coerce("  User@Example.COM  ", ColumnType.EMAIL).value());
        }

        @Test
        void mailtoStripped() {
            assertEquals("user@example.com", coerce("mailto:user@example.com", ColumnType.EMAIL).value());
        }

        @Test
        void invalidEmailWarns() {
            CoercionResult r = coerce("not-an-email", ColumnType.EMAIL);
            assertEquals("not-an-email", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.EMAIL).value());
        }

        @Test
        void blankStringReturnsNull() {
            assertNull(coerce("   ", ColumnType.EMAIL).value());
        }

        @Test
        void displayNameExtracted() {
            CoercionResult r = coerce("John Doe <john@example.com>", ColumnType.EMAIL);
            assertEquals("john@example.com", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void displayNameWithQuotes() {
            CoercionResult r = coerce("\"Jane Smith\" <jane@example.com>", ColumnType.EMAIL);
            assertEquals("jane@example.com", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void fileRefInEmailColumnFails() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant/wf/run/step/report.pdf");
            fileRef.put("name", "report.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 102400);

            CoercionResult r = coerce(fileRef, ColumnType.EMAIL);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
            assertTrue(r.warnings().get(0).contains("File reference"));
        }
    }

    // ── PHONE ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PHONE coercion")
    class PhoneCoercion {

        @Test
        void validPhonePassesThrough() {
            CoercionResult r = coerce("+1-555-123-4567", ColumnType.PHONE);
            assertEquals("+1-555-123-4567", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void numberConverted() {
            CoercionResult r = coerce(5551234567L, ColumnType.PHONE);
            assertEquals("5551234567", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void tooFewDigitsWarns() {
            CoercionResult r = coerce("123", ColumnType.PHONE);
            assertEquals("123", r.value());
            assertTrue(r.hasWarnings());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.PHONE).value());
        }

        @Test
        void telPrefixStripped() {
            CoercionResult r = coerce("tel:+1-555-123-4567", ColumnType.PHONE);
            assertEquals("+1-555-123-4567", r.value());
            assertFalse(r.hasWarnings());
        }

        @Test
        void fileRefInPhoneColumnFails() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant/wf/run/step/report.pdf");
            fileRef.put("name", "report.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 102400);

            CoercionResult r = coerce(fileRef, ColumnType.PHONE);
            assertNull(r.value());
            assertTrue(r.hasWarnings());
            assertTrue(r.warnings().get(0).contains("File reference"));
        }
    }

    // ── URL ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("URL coercion")
    class UrlCoercion {

        @Test
        void httpsPreserved() {
            assertEquals("https://example.com", coerce("https://example.com", ColumnType.URL).value());
        }

        @Test
        void httpPreserved() {
            assertEquals("http://example.com", coerce("http://example.com", ColumnType.URL).value());
        }

        @Test
        void noSchemePrependsHttps() {
            assertEquals("https://example.com", coerce("example.com", ColumnType.URL).value());
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(coerce("", ColumnType.URL).value());
        }

        @Test
        void blankStringReturnsNull() {
            assertNull(coerce("   ", ColumnType.URL).value());
        }

        @Test
        void fileRefExtractsProxyUrl() {
            Map<String, Object> fileRef = new java.util.LinkedHashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "tenant/wf/run/step/report.pdf");
            fileRef.put("name", "report.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 102400);
            fileRef.put("id", "88888888-8888-8888-8888-888888888888");

            String result = (String) coerce(fileRef, ColumnType.URL).value();
            assertTrue(result.contains("/api/proxy/files/by-id/"));
        }

        @Test
        void dbFlattenedFileExtractsFileUrl() {
            Map<String, Object> dbFormat = Map.of(
                    "file_url", "/api/proxy/files/proxy?key=t%2Ff.pdf",
                    "file_name", "f.pdf",
                    "content_type", "application/pdf",
                    "file_size", 100
            );
            assertEquals("/api/proxy/files/proxy?key=t%2Ff.pdf", coerce(dbFormat, ColumnType.URL).value());
        }

        @Test
        void jsonFileRefStringExtractsUrl() {
            String json = "{\"_type\":\"file\",\"path\":\"t/f.pdf\",\"name\":\"f.pdf\",\"mimeType\":\"application/pdf\",\"size\":100,\"id\":\"99999999-9999-9999-9999-999999999999\"}";
            String result = (String) coerce(json, ColumnType.URL).value();
            assertTrue(result.contains("/api/proxy/files/by-id/"));
        }

        @Test
        void mapWithUrlKeyExtractsUrl() {
            Map<String, Object> map = Map.of("url", "https://example.com/page", "title", "Example");
            assertEquals("https://example.com/page", coerce(map, ColumnType.URL).value());
        }
    }

    // ── CoercionResult ──────────────────────────────────────────────────

    @Nested
    @DisplayName("CoercionResult factory methods")
    class CoercionResultTest {

        @Test
        void okHasNoWarnings() {
            CoercionResult r = CoercionResult.ok("value");
            assertEquals("value", r.value());
            assertFalse(r.hasWarnings());
            assertTrue(r.warnings().isEmpty());
        }

        @Test
        void withWarningHasOneWarning() {
            CoercionResult r = CoercionResult.withWarning("value", "oops");
            assertEquals("value", r.value());
            assertTrue(r.hasWarnings());
            assertEquals(1, r.warnings().size());
            assertEquals("oops", r.warnings().get(0));
        }

        @Test
        void failedHasNullValueAndWarning() {
            CoercionResult r = CoercionResult.failed("bad input");
            assertNull(r.value());
            assertTrue(r.hasWarnings());
            assertEquals("bad input", r.warnings().get(0));
        }
    }
}
