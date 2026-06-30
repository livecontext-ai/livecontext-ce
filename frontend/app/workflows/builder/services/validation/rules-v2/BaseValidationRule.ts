/**
 * Base class for validation rules
 * Provides common utilities and structure
 */

import type {
  ValidationRule,
  ValidationRuleName,
  ValidationRuleResult,
  ValidationContext,
  ValidationIssue,
  Severity,
  ValidationSource,
  ElementType,
} from '../core/types';

export abstract class BaseValidationRule implements ValidationRule {
  abstract readonly ruleName: ValidationRuleName;
  abstract readonly isCritical: boolean;
  abstract readonly priority: number;

  abstract validate(context: ValidationContext): ValidationRuleResult;

  /**
   * Creates a validation issue
   */
  protected createIssue(
    elementKey: string,
    elementType: ElementType,
    message: string,
    severity: Severity = 'error',
    source: ValidationSource = 'frontend',
    context?: Record<string, unknown>
  ): ValidationIssue {
    return {
      elementKey,
      elementType,
      message,
      severity,
      source,
      ruleName: this.ruleName,
      context,
    };
  }

  /**
   * Creates an error issue
   */
  protected createError(
    elementKey: string,
    elementType: ElementType,
    message: string,
    context?: Record<string, unknown>
  ): ValidationIssue {
    return this.createIssue(elementKey, elementType, message, 'error', 'frontend', context);
  }

  /**
   * Creates a warning issue
   */
  protected createWarning(
    elementKey: string,
    elementType: ElementType,
    message: string,
    context?: Record<string, unknown>
  ): ValidationIssue {
    return this.createIssue(elementKey, elementType, message, 'warning', 'frontend', context);
  }

  /**
   * Creates a global error (not tied to a specific element)
   */
  protected createGlobalError(message: string, context?: Record<string, unknown>): ValidationIssue {
    return this.createError('global', 'trigger', message, context);
  }

  /**
   * Creates a global warning
   */
  protected createGlobalWarning(message: string, context?: Record<string, unknown>): ValidationIssue {
    return this.createWarning('global', 'trigger', message, context);
  }

  /**
   * Builds the validation result from issues
   */
  protected buildResult(issues: ValidationIssue[]): ValidationRuleResult {
    return {
      ruleName: this.ruleName,
      issues,
      hasErrors: issues.some((i) => i.severity === 'error'),
      hasWarnings: issues.some((i) => i.severity === 'warning'),
    };
  }

  /**
   * Optional: Check if this rule should run
   */
  shouldRun?(_context: ValidationContext): boolean {
    return true;
  }
}
