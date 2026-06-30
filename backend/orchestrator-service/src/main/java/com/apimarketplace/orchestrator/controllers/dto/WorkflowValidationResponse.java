package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.ValidationError;
import com.apimarketplace.orchestrator.domain.workflow.ValidationWarning;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowValidationResponse {

    private final boolean isValid;
    private final List<String> errors;
    private final List<ValidationErrorDetail> errorDetails;
    private final List<String> warnings;
    private final List<ValidationWarningDetail> warningDetails;
    private final int complexityScore;
    private final Instant timestamp;

    public WorkflowValidationResponse(boolean isValid,
                                      List<String> errors,
                                      List<ValidationErrorDetail> errorDetails,
                                      List<String> warnings,
                                      List<ValidationWarningDetail> warningDetails,
                                      int complexityScore,
                                      Instant timestamp) {
        this.isValid = isValid;
        this.errors = errors;
        this.errorDetails = errorDetails;
        this.warnings = warnings;
        this.warningDetails = warningDetails;
        this.complexityScore = complexityScore;
        this.timestamp = timestamp;
    }

    public boolean isValid() {
        return isValid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<ValidationErrorDetail> getErrorDetails() {
        return errorDetails;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<ValidationWarningDetail> getWarningDetails() {
        return warningDetails;
    }

    public int getComplexityScore() {
        return complexityScore;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Détail d'une erreur de validation avec contexte
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationErrorDetail {
        private final String type;
        private final String message;
        private final String path;
        private final Map<String, Object> context;

        public ValidationErrorDetail(String type, String message, String path, Map<String, Object> context) {
            this.type = type;
            this.message = message;
            this.path = path;
            this.context = context;
        }

        public String getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }

        public String getPath() {
            return path;
        }

        public Map<String, Object> getContext() {
            return context;
        }
    }

    /**
     * Détail d'un avertissement de validation avec contexte
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationWarningDetail {
        private final String type;
        private final String message;
        private final String path;
        private final Map<String, Object> context;

        public ValidationWarningDetail(String type, String message, String path, Map<String, Object> context) {
            this.type = type;
            this.message = message;
            this.path = path;
            this.context = context;
        }

        public String getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }

        public String getPath() {
            return path;
        }

        public Map<String, Object> getContext() {
            return context;
        }
    }
}
