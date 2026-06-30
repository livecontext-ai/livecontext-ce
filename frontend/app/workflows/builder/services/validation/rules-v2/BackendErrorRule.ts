/**
 * BackendErrorRule - Processes backend validation errors
 *
 * This rule converts backend validation errors into frontend validation issues.
 * It runs first to ensure backend errors are visible immediately.
 */

import type { ValidationContext, ValidationIssue, ElementType } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';

export class BackendErrorRule extends BaseValidationRule {
  readonly ruleName = 'BackendError' as const;
  readonly isCritical = true;
  readonly priority = 1; // Always runs first

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { backendErrors } = context;

    if (!backendErrors || backendErrors.length === 0) {
      return this.buildResult(issues);
    }

    backendErrors.forEach((error) => {
      // Determine element key from error path or context
      let elementKey = 'global';
      let elementType: ElementType = 'mcp';

      if (error.path) {
        // Parse path to determine element
        // Backend paths are like: "steps[0]", "triggers[0]", "edges[0]", "cores[0]"
        const stepMatch = error.path.match(/steps\[(\d+)\]/);
        const triggerMatch = error.path.match(/triggers\[(\d+)\]/);
        const controlMatch = error.path.match(/cores\[(\d+)\]/);
        const edgeMatch = error.path.match(/edges\[(\d+)\]/);

        if (stepMatch) {
          elementType = 'mcp';
          elementKey = `mcp:index_${stepMatch[1]}`;
        } else if (triggerMatch) {
          elementType = 'trigger';
          elementKey = `trigger:index_${triggerMatch[1]}`;
        } else if (controlMatch) {
          elementType = 'core';
          elementKey = `core:index_${controlMatch[1]}`;
        } else if (edgeMatch) {
          elementType = 'edge';
          elementKey = `edge:index_${edgeMatch[1]}`;
        }
      }

      // Use context to determine element key if available
      if (error.context) {
        const ctx = error.context as Record<string, unknown>;
        if (ctx.stepLabel) {
          elementKey = `mcp:${ctx.stepLabel}`;
          elementType = 'mcp';
        } else if (ctx.triggerLabel) {
          elementKey = `trigger:${ctx.triggerLabel}`;
          elementType = 'trigger';
        } else if (ctx.coreLabel) {
          elementKey = `core:${ctx.coreLabel}`;
          elementType = 'core';
        } else if (ctx.edgeId) {
          elementKey = `edge:${ctx.edgeId}`;
          elementType = 'edge';
        }
      }

      issues.push(
        this.createIssue(elementKey, elementType, error.message, 'error', 'backend', {
          originalError: error,
          backendErrorType: error.type,
          backendErrorPath: error.path,
        })
      );
    });

    return this.buildResult(issues);
  }

  shouldRun(context: ValidationContext): boolean {
    return !!(context.backendErrors && context.backendErrors.length > 0);
  }
}
