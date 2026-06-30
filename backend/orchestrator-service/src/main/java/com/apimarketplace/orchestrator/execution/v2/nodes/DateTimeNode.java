package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * DateTime node - Parses, formats, converts, and manipulates date/time values.
 *
 * Operations:
 * - parse: Parse a string to a date using inputFormat
 * - format: Format a date to a string using outputFormat
 * - convertTimezone: Convert a date between timezones
 * - add: Add a duration (years/months/days/hours/minutes/seconds)
 * - subtract: Subtract a duration
 * - difference: Calculate the difference between two dates
 * - extract: Extract a part (year, month, day, hour, minute, second, dayOfWeek, dayOfYear)
 * - now: Get the current date/time in a specified timezone
 *
 * Usage:
 * - Parse user-supplied date strings into standard formats
 * - Convert between timezones for international workflows
 * - Calculate deadlines by adding durations
 * - Extract date components for conditional logic
 */
public class DateTimeNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(DateTimeNode.class);

    private final Core.DateTimeConfig dateTimeConfig;

    public DateTimeNode(String nodeId, Core.DateTimeConfig dateTimeConfig) {
        super(nodeId, NodeType.DATE_TIME);
        this.dateTimeConfig = dateTimeConfig;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String operation = dateTimeConfig != null ? dateTimeConfig.operation() : "format";
        logger.info("DateTime node executing: nodeId={}, operation={}, itemId={}",
            nodeId, operation, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Core.DateTimeConfig resolved = null;

        try {
            Map<String, Object> result = new HashMap<>();

            // Resolve {{...}} templates in all string fields before consuming.
            // Without this, expressions like {{trigger:start.output.fired_at}} hit the
            // parser literally and fail with DateTimeParseException.
            resolved = resolveConfig(context);

            Object operationResult = executeOperation(operation, resolved);
            result.put("result", operationResult);
            result.put("operation", operation);

            // MANDATORY metadata
            result.put("node_type", "DATE_TIME");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", buildInputDataMap(operation, resolved));

            logger.info("DateTime completed: nodeId={}, operation={}, result={}",
                nodeId, operation, operationResult);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("DateTime execution failed: nodeId={}, operation={}, error={}",
                nodeId, operation, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "DATE_TIME");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(operation, resolved));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    private Core.DateTimeConfig resolveConfig(ExecutionContext context) {
        if (dateTimeConfig == null) return null;
        return new Core.DateTimeConfig(
            dateTimeConfig.operation(),
            resolveTemplateString(dateTimeConfig.value(), context),
            resolveTemplateString(dateTimeConfig.inputFormat(), context),
            resolveTemplateString(dateTimeConfig.outputFormat(), context),
            resolveTemplateString(dateTimeConfig.timezone(), context),
            resolveTemplateString(dateTimeConfig.targetTimezone(), context),
            resolveTemplateString(dateTimeConfig.durationUnit(), context),
            dateTimeConfig.durationAmount(),
            resolveTemplateString(dateTimeConfig.secondValue(), context),
            resolveTemplateString(dateTimeConfig.extractPart(), context)
        );
    }

    private Object executeOperation(String operation, Core.DateTimeConfig config) {
        return switch (operation) {
            case "parse" -> executeParse(config);
            case "format" -> executeFormat(config);
            case "convertTimezone" -> executeConvertTimezone(config);
            case "add" -> executeAddSubtract(config, false);
            case "subtract" -> executeAddSubtract(config, true);
            case "difference" -> executeDifference(config);
            case "extract" -> executeExtract(config);
            case "now" -> executeNow(config);
            default -> throw new IllegalArgumentException("Unknown date/time operation: " + operation);
        };
    }

    /**
     * Parse a string to ISO-8601 date/time using inputFormat.
     */
    private String executeParse(Core.DateTimeConfig config) {
        String value = config.value();
        String inputFormat = config.inputFormat();
        String timezone = getTimezoneOrDefault(config);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for parse operation");
        }

        if (inputFormat != null && !inputFormat.isBlank()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(inputFormat);
            try {
                // Try parsing as date-time first
                LocalDateTime ldt = LocalDateTime.parse(value, formatter);
                ZonedDateTime zdt = ldt.atZone(ZoneId.of(timezone));
                return zdt.toInstant().toString();
            } catch (DateTimeParseException e) {
                // Try parsing as date only
                try {
                    LocalDate ld = LocalDate.parse(value, formatter);
                    ZonedDateTime zdt = ld.atStartOfDay(ZoneId.of(timezone));
                    return zdt.toInstant().toString();
                } catch (DateTimeParseException e2) {
                    throw new IllegalArgumentException(
                        "Cannot parse '" + value + "' with format '" + inputFormat + "': " + e2.getMessage());
                }
            }
        }

        // No input format - try ISO parsing
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(value);
            return zdt.toInstant().toString();
        } catch (DateTimeParseException e) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(value);
                ZonedDateTime zdt = ldt.atZone(ZoneId.of(timezone));
                return zdt.toInstant().toString();
            } catch (DateTimeParseException e2) {
                try {
                    LocalDate ld = LocalDate.parse(value);
                    ZonedDateTime zdt = ld.atStartOfDay(ZoneId.of(timezone));
                    return zdt.toInstant().toString();
                } catch (DateTimeParseException e3) {
                    throw new IllegalArgumentException(
                        "Cannot parse '" + value + "' as date/time: " + e3.getMessage());
                }
            }
        }
    }

    /**
     * Format a date/time to a string using outputFormat.
     */
    private String executeFormat(Core.DateTimeConfig config) {
        String value = config.value();
        String outputFormat = config.outputFormat();
        String timezone = getTimezoneOrDefault(config);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for format operation");
        }

        ZonedDateTime zdt = parseToZonedDateTime(config, value, timezone);

        if (outputFormat != null && !outputFormat.isBlank()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(outputFormat);
            return zdt.format(formatter);
        }

        return zdt.toInstant().toString();
    }

    /**
     * Convert a date/time between timezones.
     */
    private String executeConvertTimezone(Core.DateTimeConfig config) {
        String value = config.value();
        String timezone = getTimezoneOrDefault(config);
        String targetTimezone = config.targetTimezone();

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for convertTimezone operation");
        }
        if (targetTimezone == null || targetTimezone.isBlank()) {
            throw new IllegalArgumentException("Target timezone is required for convertTimezone operation");
        }

        ZonedDateTime source = parseToZonedDateTime(config, value, timezone);
        ZonedDateTime converted = source.withZoneSameInstant(ZoneId.of(targetTimezone));

        String outputFormat = config.outputFormat();
        if (outputFormat != null && !outputFormat.isBlank()) {
            return converted.format(DateTimeFormatter.ofPattern(outputFormat));
        }
        return converted.toInstant().toString();
    }

    private String executeAddSubtract(Core.DateTimeConfig config, boolean subtract) {
        String value = config.value();
        String timezone = getTimezoneOrDefault(config);
        String durationUnit = config.durationUnit();
        long amount = config.durationAmount();

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for " + (subtract ? "subtract" : "add") + " operation");
        }

        ZonedDateTime zdt = parseToZonedDateTime(config, value, timezone);
        long effectiveAmount = subtract ? -amount : amount;

        ZonedDateTime result = switch (durationUnit) {
            case "years" -> zdt.plusYears(effectiveAmount);
            case "months" -> zdt.plusMonths(effectiveAmount);
            case "days" -> zdt.plusDays(effectiveAmount);
            case "hours" -> zdt.plusHours(effectiveAmount);
            case "minutes" -> zdt.plusMinutes(effectiveAmount);
            case "seconds" -> zdt.plusSeconds(effectiveAmount);
            default -> throw new IllegalArgumentException("Unknown duration unit: " + durationUnit);
        };

        String outputFormat = config.outputFormat();
        if (outputFormat != null && !outputFormat.isBlank()) {
            return result.format(DateTimeFormatter.ofPattern(outputFormat));
        }
        return result.toInstant().toString();
    }

    /**
     * Calculate the difference between two dates in specified duration unit.
     */
    private Map<String, Object> executeDifference(Core.DateTimeConfig config) {
        String value = config.value();
        String secondValue = config.secondValue();
        String timezone = getTimezoneOrDefault(config);
        String durationUnit = config.durationUnit();

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for difference operation");
        }
        if (secondValue == null || secondValue.isBlank()) {
            throw new IllegalArgumentException("Second value is required for difference operation");
        }

        ZonedDateTime first = parseToZonedDateTime(config, value, timezone);
        ZonedDateTime second = parseToZonedDateTime(config, secondValue, timezone);

        Duration duration = Duration.between(first, second);
        Period period = Period.between(first.toLocalDate(), second.toLocalDate());

        Map<String, Object> diffResult = new LinkedHashMap<>();
        diffResult.put("totalSeconds", duration.getSeconds());
        diffResult.put("totalMinutes", duration.toMinutes());
        diffResult.put("totalHours", duration.toHours());
        diffResult.put("totalDays", duration.toDays());
        diffResult.put("years", period.getYears());
        diffResult.put("months", period.getMonths());
        diffResult.put("days", period.getDays());

        // Return the primary difference in the requested unit
        long primaryDifference = switch (durationUnit) {
            case "years" -> (long) period.getYears();
            case "months" -> period.toTotalMonths();
            case "days" -> duration.toDays();
            case "hours" -> duration.toHours();
            case "minutes" -> duration.toMinutes();
            case "seconds" -> duration.getSeconds();
            default -> duration.toDays();
        };
        diffResult.put("difference", primaryDifference);
        diffResult.put("unit", durationUnit);

        return diffResult;
    }

    /**
     * Extract a part from a date/time.
     */
    private Object executeExtract(Core.DateTimeConfig config) {
        String value = config.value();
        String extractPart = config.extractPart();
        String timezone = getTimezoneOrDefault(config);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value is required for extract operation");
        }
        if (extractPart == null || extractPart.isBlank()) {
            throw new IllegalArgumentException("Extract part is required for extract operation");
        }

        ZonedDateTime zdt = parseToZonedDateTime(config, value, timezone);

        return switch (extractPart) {
            case "year" -> zdt.getYear();
            case "month" -> zdt.getMonthValue();
            case "day" -> zdt.getDayOfMonth();
            case "hour" -> zdt.getHour();
            case "minute" -> zdt.getMinute();
            case "second" -> zdt.getSecond();
            case "dayOfWeek" -> zdt.getDayOfWeek().getValue();
            case "dayOfYear" -> zdt.getDayOfYear();
            default -> throw new IllegalArgumentException("Unknown extract part: " + extractPart);
        };
    }

    /**
     * Get the current date/time in specified timezone.
     */
    private String executeNow(Core.DateTimeConfig config) {
        String timezone = getTimezoneOrDefault(config);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));

        String outputFormat = config.outputFormat();
        if (outputFormat != null && !outputFormat.isBlank()) {
            return now.format(DateTimeFormatter.ofPattern(outputFormat));
        }
        return now.toInstant().toString();
    }

    /**
     * Parse a value string to ZonedDateTime, trying multiple formats.
     */
    private ZonedDateTime parseToZonedDateTime(Core.DateTimeConfig config, String value, String timezone) {
        String inputFormat = config != null ? config.inputFormat() : null;

        // Try with explicit input format first
        if (inputFormat != null && !inputFormat.isBlank()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(inputFormat);
            try {
                return ZonedDateTime.parse(value, formatter.withZone(ZoneId.of(timezone)));
            } catch (DateTimeParseException e) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(value, formatter);
                    return ldt.atZone(ZoneId.of(timezone));
                } catch (DateTimeParseException e2) {
                    try {
                        LocalDate ld = LocalDate.parse(value, formatter);
                        return ld.atStartOfDay(ZoneId.of(timezone));
                    } catch (DateTimeParseException e3) {
                        throw new IllegalArgumentException(
                            "Cannot parse '" + value + "' with format '" + inputFormat + "'");
                    }
                }
            }
        }

        // Try ISO parsing
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                Instant instant = Instant.parse(value);
                return instant.atZone(ZoneId.of(timezone));
            } catch (DateTimeParseException e2) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(value);
                    return ldt.atZone(ZoneId.of(timezone));
                } catch (DateTimeParseException e3) {
                    try {
                        LocalDate ld = LocalDate.parse(value);
                        return ld.atStartOfDay(ZoneId.of(timezone));
                    } catch (DateTimeParseException e4) {
                        throw new IllegalArgumentException(
                            "Cannot parse '" + value + "' as date/time");
                    }
                }
            }
        }
    }

    private String getTimezoneOrDefault(Core.DateTimeConfig config) {
        String tz = config != null ? config.timezone() : null;
        return (tz != null && !tz.isBlank()) ? tz : "UTC";
    }

    private Map<String, Object> buildInputDataMap(String operation, Core.DateTimeConfig resolved) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("operation", operation);
        if (resolved != null) {
            if (resolved.value() != null) inputData.put("value", resolved.value());
            if (resolved.inputFormat() != null) inputData.put("inputFormat", resolved.inputFormat());
            if (resolved.outputFormat() != null) inputData.put("outputFormat", resolved.outputFormat());
            if (resolved.timezone() != null) inputData.put("timezone", resolved.timezone());
            if (resolved.targetTimezone() != null) inputData.put("targetTimezone", resolved.targetTimezone());
            if (resolved.durationUnit() != null) inputData.put("durationUnit", resolved.durationUnit());
            if (resolved.durationAmount() != 0) inputData.put("durationAmount", resolved.durationAmount());
            if (resolved.secondValue() != null) inputData.put("secondValue", resolved.secondValue());
            if (resolved.extractPart() != null) inputData.put("extractPart", resolved.extractPart());
        }
        return inputData;
    }

    // Getters
    public Core.DateTimeConfig getDateTimeConfig() { return dateTimeConfig; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.DateTimeConfig dateTimeConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder dateTimeConfig(Core.DateTimeConfig dateTimeConfig) { this.dateTimeConfig = dateTimeConfig; return this; }
        public DateTimeNode build() { return new DateTimeNode(nodeId, dateTimeConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
