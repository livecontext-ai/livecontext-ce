package com.apimarketplace.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Informations d'évaluation d'une décision (decision node)
 * Contient les détails de l'évaluation des conditions avec templates et résolution
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionEvaluationInfo(
    String decisionNodeId,
    String decisionNodeLabel,
    String sourceStepId,
    String selectedBranch,
    List<ConditionEvaluation> conditions,
    Map<String, Object> contextSnapshot
) {
    
    public DecisionEvaluationInfo {
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
        if (contextSnapshot == null) {
            contextSnapshot = new HashMap<>();
        }
    }
    
    /**
     * Informations d'évaluation d'une condition individuelle (if, elseif, else)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionEvaluation(
        String type, // "if", "elseif", "else"
        String originalExpression, // Expression originale avec templates ${}
        String resolvedExpression, // Expression après résolution des templates
        Boolean result, // Résultat de l'évaluation (true/false)
        Boolean selected, // Si cette branche a été sélectionnée
        String targetBranch, // StepId de la branche cible
        String errorMessage // Message d'erreur si l'évaluation a échoué
    ) {}
}

