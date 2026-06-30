/**
 * CoreNodeUtils - Utility for extracting decision conditions from plan data.
 *
 * Note: Core node creation (decision, switch, loop, split, etc.) is handled inline
 * by NodeCreationService.createCoreNodesInline(). This file only exports
 * the condition-extraction helper used during edge/ifLogic parsing.
 */

import type { BuilderNodeData } from '../../types';

/**
 * Extract conditions from if logic in edges
 */
export function extractConditionsFromIfLogic(
  ifLogic: any,
  nodeId: string,
  planConditions?: Array<{ id: string; type: string; label: string; expression?: string }>
): BuilderNodeData['decisionConditions'] {
  const conditions: BuilderNodeData['decisionConditions'] = [];

  // Create a map of plan conditions by type for quick lookup
  const planConditionsMap = planConditions
    ? new Map(planConditions.map(c => [c.type, c]))
    : new Map();

  // IMPORTANT: Always use nodeId-based IDs to ensure uniqueness across decision nodes
  if (ifLogic.condition) {
    const planIf = planConditionsMap.get('if');
    conditions.push({
      id: `${nodeId}-if`,
      type: 'if',
      label: planIf?.label || 'IF',
      expression: ifLogic.condition,
    });
  }

  if (ifLogic.elsif && Array.isArray(ifLogic.elsif)) {
    ifLogic.elsif.forEach((elsif: any, index: number) => {
      const planElsif = planConditionsMap.get('elseif');
      conditions.push({
        id: `${nodeId}-elseif-${index}`,
        type: 'elseif',
        label: planElsif?.label || 'ELSE IF',
        expression: elsif.condition ?? '',
      });
    });
  }

  if (ifLogic.else) {
    const planElse = planConditionsMap.get('else');
    conditions.push({
      id: `${nodeId}-else`,
      type: 'else',
      label: planElse?.label || 'ELSE',
      expression: '',
    });
  }

  // Ensure at least if and else
  if (conditions.length === 0) {
    const planIf = planConditionsMap.get('if');
    const planElse = planConditionsMap.get('else');
    conditions.push(
      {
        id: `${nodeId}-if`,
        type: 'if',
        label: planIf?.label || 'IF',
        expression: 'false'
      },
      {
        id: `${nodeId}-else`,
        type: 'else',
        label: planElse?.label || 'ELSE',
        expression: ''
      }
    );
  } else if (conditions.length === 1 && conditions[0].type === 'if') {
    const planElse = planConditionsMap.get('else');
    conditions.push({
      id: `${nodeId}-else`,
      type: 'else',
      label: planElse?.label || 'ELSE',
      expression: '',
    });
  }

  return conditions;
}
