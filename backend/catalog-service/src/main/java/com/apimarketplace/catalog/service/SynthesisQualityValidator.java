package com.apimarketplace.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for validating the quality of AI-generated synthesis data
 * Ensures that syntheses are optimized for RRF search
 */
@Service
@Slf4j
public class SynthesisQualityValidator {

    // Allowed actions list
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
        // Core data operations
        "list", "get", "create", "update", "delete",
        // Search & discovery
        "search", "filter", "browse",
        // Content management
        "upload", "download", "export", "import",
        // Social interactions
        "follow", "unfollow", "like", "share", "comment", "react", "pin", "block",
        // Communication
        "send", "receive", "broadcast", "invite",
        // Workflow & management
        "manage", "assign", "approve", "moderate", "audit",
        // Business operations
        "order", "pay", "refund", "invoice", "checkout", "fulfill",
        // Scheduling & time
        "schedule", "cancel", "reschedule",
        // Analysis & processing
        "analyze", "generate", "calculate", "aggregate",
        // Validation & verification
        "validate", "verify", "check",
        // Synchronization & backup
        "sync", "backup", "restore",
        // Streaming & media
        "stream", "record", "play",
        // Collaboration
        "join", "leave", "collaborate",
        // Authentication & access
        "login", "logout", "authorize",
        // Monitoring & tracking
        "monitor", "track", "watch"
    );

    // Quality thresholds
    private static final int MIN_SUMMARY_WORDS = 80;
    private static final int MAX_SUMMARY_WORDS = 300;
    private static final int OPTIMAL_MIN_SUMMARY_WORDS = 100;
    private static final int OPTIMAL_MAX_SUMMARY_WORDS = 200;
    private static final int MIN_KEYWORDS_PRIMARY = 3;
    private static final int MAX_KEYWORDS_PRIMARY = 6;
    private static final int MIN_KEYWORDS_SYNONYMS = 2;
    private static final int MAX_KEYWORDS_SYNONYMS = 6;
    private static final int MIN_USE_CASES = 2;
    private static final int MAX_USE_CASES = 5;
    private static final double MIN_KEYWORD_UNIQUENESS_RATIO = 0.7;

    /**
     * Validates the complete synthesis data
     * @param data The synthesis data to validate
     * @return ValidationResult containing errors, warnings and overall validity
     */
    public ValidationResult validate(SynthesisData data) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Validate action
        validateAction(data.action(), errors);

        // 2. Validate summary
        validateSummary(data.summary(), data.action(), data.resource(), errors, warnings);

        // 3. Validate keywords primary
        validateKeywordsPrimary(data.keywordsPrimary(), errors, warnings);

        // 4. Validate keywords synonyms
        validateKeywordsSynonyms(data.keywordsSynonyms(), errors, warnings);

        // 5. Validate keywords params
        validateKeywordsParams(data.keywordsParams(), warnings);

        // 6. Validate use cases
        validateUseCases(data.useCases(), errors, warnings);

        // 7. Validate keyword uniqueness across all keyword fields
        validateKeywordUniqueness(data, warnings);

        // 8. Validate coherence (summary mentions key terms)
        validateCoherence(data, warnings);

        boolean isValid = errors.isEmpty();
        int qualityScore = calculateQualityScore(data, errors, warnings);

        return new ValidationResult(errors, warnings, isValid, qualityScore);
    }

    private void validateAction(String action, List<String> errors) {
        if (action == null || action.trim().isEmpty()) {
            errors.add("Action is required");
            return;
        }
        if (!ALLOWED_ACTIONS.contains(action.toLowerCase().trim())) {
            errors.add("Invalid action: '" + action + "'. Must be one of: " +
                String.join(", ", ALLOWED_ACTIONS.stream().sorted().limit(10).toList()) + "...");
        }
    }

    private void validateSummary(String summary, String action, String resource,
                                  List<String> errors, List<String> warnings) {
        if (summary == null || summary.trim().isEmpty()) {
            errors.add("Summary is required");
            return;
        }

        int wordCount = countWords(summary);

        if (wordCount < MIN_SUMMARY_WORDS) {
            errors.add("Summary too short: " + wordCount + " words (minimum " + MIN_SUMMARY_WORDS + ")");
        } else if (wordCount > MAX_SUMMARY_WORDS) {
            warnings.add("Summary too long: " + wordCount + " words (maximum " + MAX_SUMMARY_WORDS + ")");
        } else if (wordCount < OPTIMAL_MIN_SUMMARY_WORDS) {
            warnings.add("Summary below optimal length: " + wordCount + " words (recommended " +
                OPTIMAL_MIN_SUMMARY_WORDS + "-" + OPTIMAL_MAX_SUMMARY_WORDS + ")");
        } else if (wordCount > OPTIMAL_MAX_SUMMARY_WORDS) {
            warnings.add("Summary above optimal length: " + wordCount + " words (recommended " +
                OPTIMAL_MIN_SUMMARY_WORDS + "-" + OPTIMAL_MAX_SUMMARY_WORDS + ")");
        }
    }

    private void validateKeywordsPrimary(List<String> keywords, List<String> errors, List<String> warnings) {
        if (keywords == null || keywords.isEmpty()) {
            errors.add("Primary keywords are required (minimum " + MIN_KEYWORDS_PRIMARY + ")");
            return;
        }

        // Filter out empty/null entries
        List<String> validKeywords = keywords.stream()
            .filter(k -> k != null && !k.trim().isEmpty())
            .toList();

        if (validKeywords.size() < MIN_KEYWORDS_PRIMARY) {
            errors.add("Need at least " + MIN_KEYWORDS_PRIMARY + " primary keywords (found " + validKeywords.size() + ")");
        } else if (validKeywords.size() > MAX_KEYWORDS_PRIMARY) {
            warnings.add("Too many primary keywords: " + validKeywords.size() + " (recommended max " + MAX_KEYWORDS_PRIMARY + ")");
        }

        // Check for very short keywords (likely too generic)
        for (String keyword : validKeywords) {
            if (countWords(keyword) < 2) {
                warnings.add("Primary keyword '" + keyword + "' may be too generic (single word)");
            }
        }
    }

    private void validateKeywordsSynonyms(List<String> keywords, List<String> errors, List<String> warnings) {
        if (keywords == null || keywords.isEmpty()) {
            warnings.add("No synonym keywords provided (recommended at least " + MIN_KEYWORDS_SYNONYMS + ")");
            return;
        }

        List<String> validKeywords = keywords.stream()
            .filter(k -> k != null && !k.trim().isEmpty())
            .toList();

        if (validKeywords.size() < MIN_KEYWORDS_SYNONYMS) {
            warnings.add("Few synonym keywords: " + validKeywords.size() + " (recommended " + MIN_KEYWORDS_SYNONYMS + ")");
        } else if (validKeywords.size() > MAX_KEYWORDS_SYNONYMS) {
            warnings.add("Too many synonym keywords: " + validKeywords.size() + " (recommended max " + MAX_KEYWORDS_SYNONYMS + ")");
        }
    }

    private void validateKeywordsParams(List<String> keywords, List<String> warnings) {
        if (keywords == null || keywords.isEmpty()) {
            warnings.add("No parameter keywords provided");
            return;
        }

        // Check format: "param_name:description"
        for (String keyword : keywords) {
            if (keyword != null && !keyword.contains(":")) {
                warnings.add("Parameter keyword '" + keyword + "' should follow format 'param_name:description'");
            }
        }
    }

    private void validateUseCases(List<String> useCases, List<String> errors, List<String> warnings) {
        if (useCases == null || useCases.isEmpty()) {
            warnings.add("No use cases provided (recommended at least " + MIN_USE_CASES + ")");
            return;
        }

        List<String> validUseCases = useCases.stream()
            .filter(uc -> uc != null && !uc.trim().isEmpty())
            .toList();

        if (validUseCases.size() < MIN_USE_CASES) {
            warnings.add("Few use cases: " + validUseCases.size() + " (recommended " + MIN_USE_CASES + ")");
        } else if (validUseCases.size() > MAX_USE_CASES) {
            warnings.add("Too many use cases: " + validUseCases.size() + " (recommended max " + MAX_USE_CASES + ")");
        }
    }

    private void validateKeywordUniqueness(SynthesisData data, List<String> warnings) {
        Set<String> allKeywords = new HashSet<>();
        int totalCount = 0;

        if (data.keywordsPrimary() != null) {
            for (String k : data.keywordsPrimary()) {
                if (k != null && !k.trim().isEmpty()) {
                    allKeywords.add(k.toLowerCase().trim());
                    totalCount++;
                }
            }
        }

        if (data.keywordsSynonyms() != null) {
            for (String k : data.keywordsSynonyms()) {
                if (k != null && !k.trim().isEmpty()) {
                    allKeywords.add(k.toLowerCase().trim());
                    totalCount++;
                }
            }
        }

        if (totalCount > 0) {
            double uniquenessRatio = (double) allKeywords.size() / totalCount;
            if (uniquenessRatio < MIN_KEYWORD_UNIQUENESS_RATIO) {
                warnings.add("Too many duplicate keywords: " +
                    String.format("%.0f%%", uniquenessRatio * 100) + " unique (recommended " +
                    String.format("%.0f%%", MIN_KEYWORD_UNIQUENESS_RATIO * 100) + "+)");
            }
        }
    }

    private void validateCoherence(SynthesisData data, List<String> warnings) {
        if (data.summary() == null) return;

        String summaryLower = data.summary().toLowerCase();

        // Check if action is mentioned in summary
        if (data.action() != null && !summaryLower.contains(data.action().toLowerCase())) {
            // Allow synonyms for common actions
            boolean hasActionSynonym = switch (data.action().toLowerCase()) {
                case "list" -> summaryLower.contains("retriev") || summaryLower.contains("fetch") || summaryLower.contains("return");
                case "get" -> summaryLower.contains("retriev") || summaryLower.contains("fetch") || summaryLower.contains("return");
                case "create" -> summaryLower.contains("add") || summaryLower.contains("new") || summaryLower.contains("generat");
                case "update" -> summaryLower.contains("modif") || summaryLower.contains("edit") || summaryLower.contains("chang");
                case "delete" -> summaryLower.contains("remov") || summaryLower.contains("delet");
                default -> false;
            };

            if (!hasActionSynonym) {
                warnings.add("Summary should mention the action '" + data.action() + "' or a related term");
            }
        }

        // Check if resource is mentioned in summary
        if (data.resource() != null && !data.resource().isEmpty()) {
            String resourceClean = data.resource().replace("_", " ").toLowerCase();
            if (!summaryLower.contains(resourceClean) && !summaryLower.contains(data.resource().toLowerCase())) {
                warnings.add("Summary should mention the resource '" + data.resource() + "'");
            }
        }
    }

    private int calculateQualityScore(SynthesisData data, List<String> errors, List<String> warnings) {
        int score = 100;

        // Deduct for errors (major issues)
        score -= errors.size() * 20;

        // Deduct for warnings (minor issues)
        score -= warnings.size() * 5;

        // Bonus for optimal summary length
        if (data.summary() != null) {
            int wordCount = countWords(data.summary());
            if (wordCount >= OPTIMAL_MIN_SUMMARY_WORDS && wordCount <= OPTIMAL_MAX_SUMMARY_WORDS) {
                score += 5;
            }
        }

        // Bonus for having all keyword types
        if (data.keywordsPrimary() != null && !data.keywordsPrimary().isEmpty()) score += 5;
        if (data.keywordsSynonyms() != null && !data.keywordsSynonyms().isEmpty()) score += 5;
        if (data.keywordsParams() != null && !data.keywordsParams().isEmpty()) score += 3;
        if (data.useCases() != null && !data.useCases().isEmpty()) score += 5;

        return Math.max(0, Math.min(100, score));
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    /**
     * Quick validation for essential fields only
     */
    public boolean isMinimallyValid(SynthesisData data) {
        if (data.action() == null || data.action().trim().isEmpty()) return false;
        if (data.summary() == null || countWords(data.summary()) < 50) return false;
        if (data.keywordsPrimary() == null || data.keywordsPrimary().isEmpty()) return false;
        return true;
    }

    /**
     * Synthesis data record for validation
     */
    public record SynthesisData(
        String action,
        String summary,
        String resource,
        List<String> keywordsPrimary,
        List<String> keywordsSynonyms,
        List<String> keywordsParams,
        List<String> useCases
    ) {
        public static SynthesisData fromJson(com.fasterxml.jackson.databind.JsonNode node, String resource) {
            return new SynthesisData(
                getStringOrNull(node, "action"),
                getStringOrNull(node, "summary"),
                resource,
                getStringListOrEmpty(node, "keywords_primary"),
                getStringListOrEmpty(node, "keywords_synonyms"),
                getStringListOrEmpty(node, "keywords_params"),
                getStringListOrEmpty(node, "use_cases")
            );
        }

        private static String getStringOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
        }

        private static List<String> getStringListOrEmpty(com.fasterxml.jackson.databind.JsonNode node, String field) {
            if (!node.has(field) || !node.get(field).isArray()) {
                return new ArrayList<>();
            }
            List<String> result = new ArrayList<>();
            node.get(field).forEach(item -> {
                if (!item.isNull()) {
                    result.add(item.asText());
                }
            });
            return result;
        }
    }

    /**
     * Validation result record
     */
    public record ValidationResult(
        List<String> errors,
        List<String> warnings,
        boolean isValid,
        int qualityScore
    ) {
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Quality Score: ").append(qualityScore).append("/100");
            sb.append(" | Valid: ").append(isValid);
            if (!errors.isEmpty()) {
                sb.append(" | Errors: ").append(errors.size());
            }
            if (!warnings.isEmpty()) {
                sb.append(" | Warnings: ").append(warnings.size());
            }
            return sb.toString();
        }
    }
}
