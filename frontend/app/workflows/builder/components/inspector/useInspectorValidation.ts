import { useMemo } from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useValidationOptional } from '../../contexts/ValidationContext';

interface ValidationError {
  message: string;
  severity: 'error' | 'warning';
  source: 'combined';
}

interface NodeValidationResult {
  isValid: boolean;
  errors: ValidationError[];
  errorCount: number;
  warningCount: number;
}

interface UseInspectorValidationProps {
  node: Node<BuilderNodeData> | null;
  toolParameters: any[];
}

interface UseInspectorValidationReturn {
  nodeValidation: NodeValidationResult;
  hasGlobalValidationErrors: boolean;
  backendErrors: any[];
}

/**
 * Hook to manage node validation in the inspector.
 *
 * Single source of truth: reads exclusively from ValidationContext (rules-v2).
 * Previously this also ran NodeValidationService independently, which caused
 * desync between the inspector footer and the canvas node borders.
 */
export function useInspectorValidation({
  node,
  toolParameters: _toolParameters,
}: UseInspectorValidationProps): UseInspectorValidationReturn {
  const validationContext = useValidationOptional();

  // Extract backend validation errors from node data (for backward compat)
  const backendErrors = useMemo(() => {
    if (!node) return [];
    return Array.isArray((node.data as any)?.validationIssues)
      ? (node.data as any).validationIssues
      : [];
  }, [node]);

  // Read validation from the centralized context - same source that colors node borders
  const nodeValidation = useMemo((): NodeValidationResult => {
    if (!node || !validationContext) {
      return { isValid: true, errors: [], errorCount: 0, warningCount: 0 };
    }

    const contextValidation = validationContext.getNodeValidation(node.id);

    const combined: ValidationError[] = [
      ...contextValidation.errors.map(msg => ({
        message: msg, severity: 'error' as const, source: 'combined' as const,
      })),
      ...contextValidation.warnings.map(msg => ({
        message: msg, severity: 'warning' as const, source: 'combined' as const,
      })),
    ];

    return {
      isValid: contextValidation.isValid,
      errors: combined,
      errorCount: contextValidation.errorCount,
      warningCount: contextValidation.warningCount,
    };
  }, [node, validationContext]);

  const hasGlobalValidationErrors = useMemo(() => {
    if (!validationContext) return false;
    return !validationContext.state.isValid || validationContext.state.errorCount > 0;
  }, [validationContext]);

  return {
    nodeValidation,
    hasGlobalValidationErrors,
    backendErrors,
  };
}
