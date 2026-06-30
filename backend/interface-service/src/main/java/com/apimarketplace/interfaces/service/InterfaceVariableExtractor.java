package com.apimarketplace.interfaces.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts generic variable names from interface templates.
 * Supports {{title}}, {{formatDate(date, 'DD/MM')}}, ${variable} syntax.
 */
@Service
public class InterfaceVariableExtractor {

    private static final Logger log = LoggerFactory.getLogger(InterfaceVariableExtractor.class);

    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{\\{([^}|]+)(?:\\|[^}]*)?\\}\\}");
    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$\\{([^}|]+)(?:\\|[^}]*)?\\}");

    private static final Pattern INPUT_NAME = Pattern.compile(
            "<input[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_NAME = Pattern.compile(
            "<select[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXTAREA_NAME = Pattern.compile(
            "<textarea[^>]*\\bname\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    public List<String> extractTemplateVariables(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> variables = new LinkedHashSet<>();
        extractFromPattern(MUSTACHE_PATTERN, template, variables);
        extractFromPattern(DOLLAR_PATTERN, template, variables);

        log.info("[VariableExtractor] Extracted {} variables: {}", variables.size(), variables);
        return new ArrayList<>(variables);
    }

    private void extractFromPattern(Pattern pattern, String template, LinkedHashSet<String> variables) {
        Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String varName = extractVariableNameFromExpression(expression);
            if (varName != null && !varName.isEmpty()) {
                variables.add(varName);
            }
        }
    }

    private String extractVariableNameFromExpression(String expression) {
        if (expression.isEmpty()) return null;
        int parenIndex = expression.indexOf('(');
        if (parenIndex > 0) {
            return extractFirstArgument(expression, parenIndex);
        }
        if (expression.contains(":")) {
            log.debug("[VariableExtractor] Skipping old-style prefixed variable: {}", expression);
            return null;
        }
        if (expression.contains(".")) {
            log.debug("[VariableExtractor] Skipping dotted path variable: {}", expression);
            return null;
        }
        return expression;
    }

    private String extractFirstArgument(String expression, int parenIndex) {
        String remaining = expression.substring(parenIndex + 1);
        int nestedParen = remaining.indexOf('(');
        int commaOrClose = findFirstOf(remaining, ',', ')');

        if (nestedParen >= 0 && nestedParen < commaOrClose) {
            return extractFirstArgument(remaining, nestedParen);
        }

        if (commaOrClose >= 0) {
            String arg = remaining.substring(0, commaOrClose).trim();
            if (arg.startsWith("'") || arg.startsWith("\"")) {
                return null;
            }
            if (arg.contains(":") || arg.contains(".")) {
                return null;
            }
            return arg.isEmpty() ? null : arg;
        }
        return null;
    }

    private int findFirstOf(String str, char a, char b) {
        int idxA = str.indexOf(a);
        int idxB = str.indexOf(b);
        if (idxA < 0) return idxB;
        if (idxB < 0) return idxA;
        return Math.min(idxA, idxB);
    }

    public List<String> extractFormFields(String htmlTemplate) {
        if (htmlTemplate == null || htmlTemplate.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> fields = new LinkedHashSet<>();
        extractFieldNames(INPUT_NAME, htmlTemplate, fields);
        extractFieldNames(SELECT_NAME, htmlTemplate, fields);
        extractFieldNames(TEXTAREA_NAME, htmlTemplate, fields);

        log.debug("[VariableExtractor] Extracted {} form fields: {}", fields.size(), fields);
        return new ArrayList<>(fields);
    }

    private void extractFieldNames(Pattern pattern, String html, LinkedHashSet<String> fields) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                fields.add(name);
            }
        }
    }
}
