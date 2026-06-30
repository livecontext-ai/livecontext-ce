package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DateTimeNode.
 * DateTimeNode parses, formats, converts, and manipulates date/time values.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DateTimeNode")
class DateTimeNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("date", "2025-06-15T10:30:00Z");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create DateTimeNode with nodeId and config")
        void shouldCreateDateTimeNodeWithNodeIdAndConfig() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-01-01T00:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);

            assertEquals("core:datetime", node.getNodeId());
            assertEquals(NodeType.DATE_TIME, node.getType());
            assertNotNull(node.getDateTimeConfig());
            assertEquals("format", node.getDateTimeConfig().operation());
        }

        @Test
        @DisplayName("Should handle null config gracefully during execution")
        void shouldHandleNullConfigGracefully() {
            DateTimeNode node = new DateTimeNode("core:datetime", null);

            assertEquals("core:datetime", node.getNodeId());
            assertNull(node.getDateTimeConfig());
        }

        @Test
        @DisplayName("Should create DateTimeNode using builder")
        void shouldCreateDateTimeNodeUsingBuilder() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null, "UTC", null, "days", 0, null, null);

            DateTimeNode node = DateTimeNode.builder()
                .nodeId("core:my_datetime")
                .dateTimeConfig(config)
                .build();

            assertEquals("core:my_datetime", node.getNodeId());
            assertNotNull(node.getDateTimeConfig());
            assertEquals("now", node.getDateTimeConfig().operation());
        }

        @Test
        @DisplayName("Should default operation to format when null")
        void shouldDefaultOperationToFormatWhenNull() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                null, "2025-01-01T00:00:00Z", null, null, null, null, null, 0, null, null);

            assertEquals("format", config.operation());
        }

        @Test
        @DisplayName("Should default durationUnit to days when null")
        void shouldDefaultDurationUnitToDaysWhenNull() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-01-01T00:00:00Z", null, null, null, null, null, 5, null, null);

            assertEquals("days", config.durationUnit());
        }
    }

    // ===============================================================
    // execute() - Parse operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Parse")
    class ExecuteParseTests {

        @Test
        @DisplayName("Should parse ISO date/time string")
        void shouldParseIsoDateTimeString() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "2025-06-15T10:30:00Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
            assertEquals("parse", result.output().get("operation"));
        }

        @Test
        @DisplayName("Should parse date with custom format")
        void shouldParseDateWithCustomFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "15/06/2025", "dd/MM/yyyy", null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("Should parse date-time with custom format")
        void shouldParseDateTimeWithCustomFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "2025-06-15 10:30:00", "yyyy-MM-dd HH:mm:ss", null,
                "Europe/Paris", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("Should parse ISO local date without format")
        void shouldParseIsoLocalDateWithoutFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "2025-06-15", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail for invalid date format")
        void shouldFailForInvalidDateFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "not-a-date", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when value is null for parse")
        void shouldFailWhenValueIsNullForParse() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", null, null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when value does not match custom format")
        void shouldFailWhenValueDoesNotMatchCustomFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "2025-06-15", "dd/MM/yyyy HH:mm:ss", null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Format operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Format")
    class ExecuteFormatTests {

        @Test
        @DisplayName("Should format date with output format")
        void shouldFormatDateWithOutputFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-06-15T10:30:00Z", null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2025-06-15", result.output().get("result"));
        }

        @Test
        @DisplayName("Should format date with time components")
        void shouldFormatDateWithTimeComponents() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-06-15T10:30:45Z", null, "HH:mm:ss",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("10:30:45", result.output().get("result"));
        }

        @Test
        @DisplayName("Should return ISO format when outputFormat is null")
        void shouldReturnIsoFormatWhenOutputFormatIsNull() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-06-15T10:30:00Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("Should fail when value is null for format")
        void shouldFailWhenValueIsNullForFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", null, null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - ConvertTimezone operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - ConvertTimezone")
    class ExecuteConvertTimezoneTests {

        @Test
        @DisplayName("Should convert between timezones")
        void shouldConvertBetweenTimezones() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "convertTimezone", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd HH:mm:ss",
                "UTC", "America/New_York", "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2025-06-15 06:00:00", result.output().get("result"));
        }

        @Test
        @DisplayName("Should convert UTC to Asia/Tokyo")
        void shouldConvertUtcToAsiaTokyo() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "convertTimezone", "2025-06-15T10:00:00Z", null, "HH:mm",
                "UTC", "Asia/Tokyo", "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("19:00", result.output().get("result"));
        }

        @Test
        @DisplayName("Should fail when target timezone is missing")
        void shouldFailWhenTargetTimezoneIsMissing() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "convertTimezone", "2025-06-15T10:00:00Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when value is null for convertTimezone")
        void shouldFailWhenValueIsNullForConvertTimezone() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "convertTimezone", null, null, null,
                "UTC", "America/New_York", "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Add operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Add")
    class ExecuteAddTests {

        @Test
        @DisplayName("Should add days to date")
        void shouldAddDaysToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "days", 5, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2025-06-20", result.output().get("result"));
        }

        @Test
        @DisplayName("Should add hours to date")
        void shouldAddHoursToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "HH:mm",
                "UTC", null, "hours", 3, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("13:00", result.output().get("result"));
        }

        @Test
        @DisplayName("Should add months to date")
        void shouldAddMonthsToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "months", 2, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2025-08-15", result.output().get("result"));
        }

        @Test
        @DisplayName("Should add years to date")
        void shouldAddYearsToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "years", 1, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2026-06-15", result.output().get("result"));
        }

        @Test
        @DisplayName("Should add minutes to date")
        void shouldAddMinutesToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "HH:mm",
                "UTC", null, "minutes", 45, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("10:45", result.output().get("result"));
        }

        @Test
        @DisplayName("Should add seconds to date")
        void shouldAddSecondsToDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "HH:mm:ss",
                "UTC", null, "seconds", 30, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("10:00:30", result.output().get("result"));
        }

        @Test
        @DisplayName("Should fail when value is null for add")
        void shouldFailWhenValueIsNullForAdd() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", null, null, null,
                "UTC", null, "days", 5, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail for unknown duration unit")
        void shouldFailForUnknownDurationUnit() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, null,
                "UTC", null, "weeks", 1, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Subtract operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Subtract")
    class ExecuteSubtractTests {

        @Test
        @DisplayName("Should subtract days from date")
        void shouldSubtractDaysFromDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "subtract", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "days", 5, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2025-06-10", result.output().get("result"));
        }

        @Test
        @DisplayName("Should subtract hours from date")
        void shouldSubtractHoursFromDate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "subtract", "2025-06-15T10:00:00Z", null, "HH:mm",
                "UTC", null, "hours", 3, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("07:00", result.output().get("result"));
        }

        @Test
        @DisplayName("Should subtract months crossing year boundary")
        void shouldSubtractMonthsCrossingYearBoundary() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "subtract", "2025-02-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "months", 3, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("2024-11-15", result.output().get("result"));
        }
    }

    // ===============================================================
    // execute() - Difference operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Difference")
    class ExecuteDifferenceTests {

        @Test
        @DisplayName("Should calculate difference in days")
        void shouldCalculateDifferenceInDays() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-10T00:00:00Z", null, null,
                "UTC", null, "days", 0, "2025-06-15T00:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> diffResult = (Map<String, Object>) result.output().get("result");
            assertEquals(5L, diffResult.get("difference"));
            assertEquals("days", diffResult.get("unit"));
            assertEquals(5L, diffResult.get("totalDays"));
        }

        @Test
        @DisplayName("Should calculate difference in hours")
        void shouldCalculateDifferenceInHours() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-15T10:00:00Z", null, null,
                "UTC", null, "hours", 0, "2025-06-15T13:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> diffResult = (Map<String, Object>) result.output().get("result");
            assertEquals(3L, diffResult.get("difference"));
            assertEquals("hours", diffResult.get("unit"));
        }

        @Test
        @DisplayName("Should calculate negative difference when second is before first")
        void shouldCalculateNegativeDifference() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-15T00:00:00Z", null, null,
                "UTC", null, "days", 0, "2025-06-10T00:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> diffResult = (Map<String, Object>) result.output().get("result");
            assertEquals(-5L, diffResult.get("difference"));
        }

        @Test
        @DisplayName("Should fail when second value is missing")
        void shouldFailWhenSecondValueIsMissing() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-15T00:00:00Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when first value is missing")
        void shouldFailWhenFirstValueIsMissing() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", null, null, null,
                "UTC", null, "days", 0, "2025-06-15T00:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should include totalSeconds, totalMinutes, totalHours, totalDays in result")
        void shouldIncludeAllDifferenceFields() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-10T00:00:00Z", null, null,
                "UTC", null, "days", 0, "2025-06-15T12:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> diffResult = (Map<String, Object>) result.output().get("result");
            assertNotNull(diffResult.get("totalSeconds"));
            assertNotNull(diffResult.get("totalMinutes"));
            assertNotNull(diffResult.get("totalHours"));
            assertNotNull(diffResult.get("totalDays"));
            assertNotNull(diffResult.get("years"));
            assertNotNull(diffResult.get("months"));
            assertNotNull(diffResult.get("days"));
        }

        @Test
        @DisplayName("Should calculate difference in months")
        void shouldCalculateDifferenceInMonths() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-01-01T00:00:00Z", null, null,
                "UTC", null, "months", 0, "2025-07-01T00:00:00Z", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> diffResult = (Map<String, Object>) result.output().get("result");
            assertEquals(6L, diffResult.get("difference"));
            assertEquals("months", diffResult.get("unit"));
        }
    }

    // ===============================================================
    // execute() - Extract operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Extract")
    class ExecuteExtractTests {

        @Test
        @DisplayName("Should extract year")
        void shouldExtractYear() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "year");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2025, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract month")
        void shouldExtractMonth() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "month");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(6, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract day")
        void shouldExtractDay() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "day");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(15, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract hour")
        void shouldExtractHour() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "hour");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(10, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract minute")
        void shouldExtractMinute() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "minute");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(30, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract second")
        void shouldExtractSecond() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "second");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(45, result.output().get("result"));
        }

        @Test
        @DisplayName("Should extract dayOfWeek")
        void shouldExtractDayOfWeek() {
            // 2025-06-15 is a Sunday = 7 in ISO
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "dayOfWeek");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(7, result.output().get("result")); // Sunday = 7 in ISO
        }

        @Test
        @DisplayName("Should extract dayOfYear")
        void shouldExtractDayOfYear() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "dayOfYear");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(166, result.output().get("result")); // June 15 = day 166
        }

        @Test
        @DisplayName("Should fail for unknown extract part")
        void shouldFailForUnknownExtractPart() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, "weekNumber");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when extract part is null")
        void shouldFailWhenExtractPartIsNull() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", "2025-06-15T10:30:45Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should fail when value is null for extract")
        void shouldFailWhenValueIsNullForExtract() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "extract", null, null, null,
                "UTC", null, "days", 0, null, "year");

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Now operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Now")
    class ExecuteNowTests {

        @Test
        @DisplayName("Should return current time in UTC")
        void shouldReturnCurrentTimeInUtc() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
            String resultStr = (String) result.output().get("result");
            // Should be a valid instant string
            assertDoesNotThrow(() -> Instant.parse(resultStr));
        }

        @Test
        @DisplayName("Should return current time with output format")
        void shouldReturnCurrentTimeWithOutputFormat() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String resultStr = (String) result.output().get("result");
            // Should match yyyy-MM-dd pattern
            assertTrue(resultStr.matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Test
        @DisplayName("Should return current time in specified timezone")
        void shouldReturnCurrentTimeInSpecifiedTimezone() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                "Asia/Tokyo", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("Should default to UTC when timezone is null")
        void shouldDefaultToUtcWhenTimezoneIsNull() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                null, null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }
    }

    // ===============================================================
    // execute() - Unknown operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Unknown operation")
    class ExecuteUnknownOperationTests {

        @Test
        @DisplayName("Should fail for unknown operation")
        void shouldFailForUnknownOperation() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "invalid_op", "2025-06-15T10:00:00Z", null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Timezone tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Timezones")
    class ExecuteTimezoneTests {

        @Test
        @DisplayName("Should handle Europe/London timezone")
        void shouldHandleEuropeLondonTimezone() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                "Europe/London", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle America/Los_Angeles timezone")
        void shouldHandleAmericaLosAngelesTimezone() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd HH:mm",
                "America/Los_Angeles", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertEquals("DATE_TIME", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include operation in output")
        void shouldIncludeOperationInOutput() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null,
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertEquals("now", result.output().get("operation"));
        }

        @Test
        @DisplayName("Should include resolved_params with operation details")
        void shouldIncludeInputDataWithOperationDetails() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "add", "2025-06-15T10:00:00Z", null, "yyyy-MM-dd",
                "UTC", null, "days", 5, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("add", inputData.get("operation"));
            assertEquals("2025-06-15T10:00:00Z", inputData.get("value"));
            assertEquals("days", inputData.get("durationUnit"));
            assertEquals(5L, inputData.get("durationAmount"));
        }
    }

    // ===============================================================
    // execute() - Various date formats
    // ===============================================================

    @Nested
    @DisplayName("execute() - Various formats")
    class ExecuteVariousFormatsTests {

        @Test
        @DisplayName("Should parse LocalDateTime without zone info")
        void shouldParseLocalDateTimeWithoutZoneInfo() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "parse", "2025-06-15T10:30:00", null, null,
                "Europe/Paris", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should format with full pattern")
        void shouldFormatWithFullPattern() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-06-15T10:30:45Z", null, "dd MMMM yyyy, HH:mm:ss",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String resultStr = (String) result.output().get("result");
            assertTrue(resultStr.contains("2025"));
            assertTrue(resultStr.contains("10:30:45"));
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null, "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:datetime", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null, "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:datetime", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null, "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = NodeExecutionResult.success("core:datetime", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "now", null, null, null, "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = NodeExecutionResult.failure("core:datetime", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields set")
        void shouldBuildWithAllFieldsSet() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "2025-01-01T00:00:00Z", "yyyy-MM-dd'T'HH:mm:ss'Z'", "dd/MM/yyyy",
                "UTC", "Asia/Tokyo", "hours", 3, "2025-12-31T23:59:59Z", "year");

            DateTimeNode node = DateTimeNode.builder()
                .nodeId("core:full_datetime")
                .dateTimeConfig(config)
                .build();

            assertEquals("core:full_datetime", node.getNodeId());
            assertEquals(NodeType.DATE_TIME, node.getType());
            assertEquals("format", node.getDateTimeConfig().operation());
            assertEquals("2025-01-01T00:00:00Z", node.getDateTimeConfig().value());
            assertEquals("dd/MM/yyyy", node.getDateTimeConfig().outputFormat());
        }

        @Test
        @DisplayName("Should build with null config")
        void shouldBuildWithNullConfig() {
            DateTimeNode node = DateTimeNode.builder()
                .nodeId("core:datetime")
                .dateTimeConfig(null)
                .build();

            assertEquals("core:datetime", node.getNodeId());
            assertNull(node.getDateTimeConfig());
        }
    }

    // ===============================================================
    // #D10 Template resolution
    // ===============================================================

    @Nested
    @DisplayName("#D10 Template resolution")
    class TemplateResolutionTests {

        @Test
        @DisplayName("Should resolve {{...}} in value before parsing")
        void shouldResolveValueTemplate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "{{trigger:webhook.output.date}}", null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("2025-06-15T10:00:00Z"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "Template in value must be resolved before parseToZonedDateTime runs (#D10)");
            assertEquals("2025-06-15", result.output().get("result"));
        }

        @Test
        @DisplayName("Should resolve {{...}} in secondValue for difference op")
        void shouldResolveSecondValueTemplate() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "difference", "2025-06-10T00:00:00Z", null, null,
                "UTC", null, "days", 0, "{{trigger:webhook.output.end}}", null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("2025-06-15T00:00:00Z"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "Template in secondValue must be resolved before difference op (#D10)");
            @SuppressWarnings("unchecked")
            Map<String, Object> diff = (Map<String, Object>) result.output().get("result");
            assertEquals(5L, diff.get("difference"));
        }

        @Test
        @DisplayName("Should fail without template resolution when value is a raw placeholder")
        void shouldFailWithoutTemplateResolutionForRawPlaceholder() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "{{trigger:webhook.output.date}}", null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            // No templateAdapter injected - resolveTemplateString returns the input as-is
            DateTimeNode node = new DateTimeNode("core:datetime", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess(),
                "Without resolution the raw placeholder must fail parsing - proves fix is needed");
        }

        @Test
        @DisplayName("resolved_params should reflect resolved value")
        void resolvedParamsShouldShowResolvedValue() {
            Core.DateTimeConfig config = new Core.DateTimeConfig(
                "format", "{{trigger:webhook.output.date}}", null, "yyyy-MM-dd",
                "UTC", null, "days", 0, null, null);

            DateTimeNode node = new DateTimeNode("core:datetime", config);
            node.acceptServices(ServiceRegistry.builder()
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(echoUnlessTemplate("2025-06-15T10:00:00Z"));

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("2025-06-15T10:00:00Z", params.get("value"),
                "resolved_params must record the resolved value, not the raw placeholder");
        }

        /**
         * Returns an Answer that echoes back the input Map unchanged unless the __v__ value
         * looks like a template ({{...}}), in which case it returns the provided replacement.
         * Keeps format/timezone/etc. fields untouched while resolving placeholders.
         */
        private org.mockito.stubbing.Answer<Map<String, Object>> echoUnlessTemplate(String replacement) {
            return invocation -> {
                Map<String, Object> in = invocation.getArgument(0);
                Object raw = in.get("__v__");
                if (raw instanceof String s && s.contains("{{")) {
                    return Map.of("__v__", replacement);
                }
                return in;
            };
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
