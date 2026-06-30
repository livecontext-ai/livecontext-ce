package com.apimarketplace.orchestrator.domain.workflow;

import java.util.List;

/**
 * Resultat de validation
 */
public class ValidationResult {
    private final boolean valid;
    private final List<ValidationError> errors;
    private final List<ValidationWarning> warnings;
    private final int complexityScore;
    
    public ValidationResult(boolean valid, List<ValidationError> errors, 
                          List<ValidationWarning> warnings, int complexityScore) {
        this.valid = valid;
        this.errors = errors;
        this.warnings = warnings;
        this.complexityScore = complexityScore;
    }
    
    public boolean isValid() { return valid; }
    public List<ValidationError> getErrors() { return errors; }
    public List<ValidationWarning> getWarnings() { return warnings; }
    public int getComplexityScore() { return complexityScore; }
    
    public boolean hasErrors() { return errors != null && !errors.isEmpty(); }
    public boolean hasWarnings() { return warnings != null && !warnings.isEmpty(); }
}
