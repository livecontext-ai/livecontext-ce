'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow } from '../../types';
import { createDefaultDecisionConditions } from '../../types';
import type { Connection } from './useInspectorConnections';
import type { ConnectionType } from '../ConnectionTypeSelector';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { nodeRegistry } from '../../registry/nodeRegistry';

interface UseInspectorExpressionsProps {
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  allNodes: Node<BuilderNodeData>[];
  connectionType: ConnectionType;
  onUpdate: (data: BuilderNodeData) => void;
  setConnections: React.Dispatch<React.SetStateAction<Connection[]>>;
  selectedLoopChild?: { loopId: string; childId: string } | null;
}

export function useInspectorExpressions({
  node,
  data,
  allNodes,
  connectionType,
  onUpdate,
  setConnections,
  selectedLoopChild,
}: UseInspectorExpressionsProps) {
  // Function to extract variables from an expression
  const extractVariables = React.useCallback((expression: string): string[] => {
    // Mirrors backend TemplateEngine.EXPRESSION_PATTERN - accepts SpEL string literals
    // containing `}` so {{json('{...}')}} is parsed correctly.
    const regex = /\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}/g;
    const variables: string[] = [];
    let match;
    while ((match = regex.exec(expression)) !== null) {
      const varName = match[1].trim();
      const baseVarName = varName.split('.')[0];
      // {{$vars.name}} / {{vars:name}} reference workflow variables (org-level
      // values), not node outputs - they have no source node by design and
      // must not surface an "unknown variable" warning or auto-wiring.
      if (baseVarName === '$vars' || baseVarName.startsWith('vars:')) {
        continue;
      }
      if (baseVarName && !variables.includes(baseVarName)) {
        variables.push(baseVarName);
      }
    }
    return variables;
  }, []);

  // Get parameter expression
  const getParamExpression = React.useCallback((paramName: string): string => {
    if (!node || !data) return '';
    const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
    // Check paramExpressions first
    if (expressions[paramName]) {
      return expressions[paramName];
    }
    // Fallback: for agents, the prompt may be stored directly on data.prompt (from backend)
    if (paramName === 'prompt' && (data as any).prompt) {
      return (data as any).prompt;
    }
    // Fallback: for agents, systemPrompt may be stored directly
    if (paramName === 'systemPrompt' && (data as any).systemPrompt) {
      return (data as any).systemPrompt;
    }
    // Fallback: for classify nodes, classifyParams may be stored directly
    if (paramName === 'classifyParams' && (data as any).classifyParams) {
      return (data as any).classifyParams;
    }
    // Fallback: for guardrail nodes, guardrailParams may be stored directly
    if (paramName === 'guardrailParams' && (data as any).guardrailParams) {
      return (data as any).guardrailParams;
    }
    return '';
  }, [node, data]);

  // Get tool parameter expression
  const getToolParamExpression = React.useCallback((paramName: string): string => {
    if (!node?.data) return '';
    const expressions = (node.data.paramExpressions as Record<string, string> | undefined) || {};
    return expressions[paramName] || '';
  }, [node]);

  // Get condition expression
  const getConditionExpression = React.useCallback((conditionId: string): string => {
    if (!node || !data) return '';
    const conditions = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
    const condition = conditions.find(c => c.id === conditionId);
    return condition?.expression || '';
  }, [node, data]);

  // Get loop condition expression
  const getLoopConditionExpression = React.useCallback((): string => {
    if (!node || !data) return '';
    return data.loopCondition || '';
  }, [node, data]);

  // Get list expression (for Split node)
  const getListExpression = React.useCallback((): string => {
    if (!node || !data) return '';
    return data.list || '';
  }, [node, data]);

  // Find unknown variables
  const findUnknownVariables = React.useCallback((expressions: Record<string, string>): string[] => {
    const unknownVars: string[] = [];
    const allVariables = new Set<string>();
    
    Object.values(expressions).forEach(expr => {
      extractVariables(expr).forEach(v => allVariables.add(v));
    });
    
    // Check if we're editing a loop child and find the immediate previous child
    const isEditingLoopChild = selectedLoopChild && node?.id === selectedLoopChild.childId;
    const loopNode = isEditingLoopChild
      ? allNodes.find(n => n.id === selectedLoopChild.loopId && nodeRegistry.isLoopNode(n))
      : null;
    
    // Find the immediate previous loop child (only the one directly before the current one)
    const immediatePreviousLoopChild = (isEditingLoopChild && loopNode && Array.isArray(loopNode.data.loopChildren))
      ? (() => {
          const loopChildren = loopNode.data.loopChildren;
          const currentIndex = loopChildren.findIndex(c => c.id === selectedLoopChild.childId);
          return currentIndex > 0 ? loopChildren[currentIndex - 1] : null;
        })()
      : null;
    
    // Check if this is the first loop child (only first child should see external nodes)
    const isFirstLoopChild = (isEditingLoopChild && loopNode && Array.isArray(loopNode.data.loopChildren))
      ? (() => {
          const loopChildren = loopNode.data.loopChildren;
          const currentIndex = loopChildren.findIndex(c => c.id === selectedLoopChild.childId);
          return currentIndex === 0;
        })()
      : false;
    
    allVariables.forEach(varName => {
      let sourceNode: Node<BuilderNodeData> | undefined = undefined;
      
      // For loop children that are not the first, only check in the immediate previous loop child
      if (isEditingLoopChild && !isFirstLoopChild && immediatePreviousLoopChild) {
        // Only check in the immediate previous loop child
        const output = immediatePreviousLoopChild.output;
        const input = immediatePreviousLoopChild.params;
        
        if ((output && output.toLowerCase() === varName.toLowerCase()) ||
            (input && input.toLowerCase() === varName.toLowerCase())) {
          sourceNode = {
            id: immediatePreviousLoopChild.id,
            type: immediatePreviousLoopChild.nodeType ?? 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              ...immediatePreviousLoopChild,
            } as BuilderNodeData,
          } as Node<BuilderNodeData>;
        }
      } else {
        // For first loop child or normal nodes, check in allNodes (normal nodes and external nodes)
        sourceNode = allNodes.find(n => {
          if (n.id === node?.id) return false;
          const output = n.data.output;
          if (output && output.toLowerCase() === varName.toLowerCase()) {
            return true;
          }
          const input = n.data.params;
          if (input && input.toLowerCase() === varName.toLowerCase()) {
            return true;
          }
          return false;
        });
        
        // If not found and we're editing a loop child (first one), also check in immediate previous loop child
        // (though for first child, immediatePreviousLoopChild should be null, but just in case)
        if (!sourceNode && isEditingLoopChild && immediatePreviousLoopChild) {
          const output = immediatePreviousLoopChild.output;
          const input = immediatePreviousLoopChild.params;
          
          if ((output && output.toLowerCase() === varName.toLowerCase()) ||
              (input && input.toLowerCase() === varName.toLowerCase())) {
            sourceNode = {
              id: immediatePreviousLoopChild.id,
              type: immediatePreviousLoopChild.nodeType ?? 'flowNode',
              position: { x: 0, y: 0 },
              data: {
                ...immediatePreviousLoopChild,
              } as BuilderNodeData,
            } as Node<BuilderNodeData>;
          }
        }
      }
      
      if (!sourceNode) {
        unknownVars.push(varName);
      }
    });
    
    return unknownVars;
  }, [allNodes, node, extractVariables, selectedLoopChild]);

  // Create connections for variables in expression
  const createConnectionsForVariables = React.useCallback((expression: string, targetHandleId: string) => {
    if (!node || !data) return;

    // Mirrors backend TemplateEngine.EXPRESSION_PATTERN.
    const variableRegex = /\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}/g;
    const matches = Array.from(expression.matchAll(variableRegex));
    const variables = matches
      .map(m => m[1].trim().split('.')[0])
      // Workflow variables ($vars / vars:) are not node outputs - never
      // auto-wire a connection for them.
      .filter(v => v !== '$vars' && !v.startsWith('vars:'));
    const variablesInExpression = new Set(variables);
    
    setConnections(prev => {
      const filtered = prev.filter(conn => {
        if (conn.source !== targetHandleId && conn.target !== targetHandleId) {
          return true;
        }
        
        const otherHandleId = conn.source === targetHandleId ? conn.target : conn.source;
        const inputMatch = otherHandleId.match(/input-(.+?)-/);
        
        if (inputMatch) {
          const inputName = inputMatch[1];
          return variablesInExpression.has(inputName);
        }
        
        return true;
      });
      
      const connectionsToAdd: Connection[] = [];

      variables.forEach(variableName => {
        const hasSourceNode = allNodes.some(n => {
          if (n.id === node?.id) return false;
          const output = n.data.output;
          if (output && output.toLowerCase() === variableName.toLowerCase()) {
            return true;
          }
          const input = n.data.params;
          if (input && input.toLowerCase() === variableName.toLowerCase()) {
            return true;
          }
          return false;
        });

        if (hasSourceNode) {
          const inputHandleId = `input-${variableName}-${node.id}`;
          
          const connectionExists = filtered.some(conn => 
            (conn.source === inputHandleId && conn.target === targetHandleId) ||
            (conn.source === targetHandleId && conn.target === inputHandleId)
          );
          
          if (!connectionExists) {
            connectionsToAdd.push({
              id: `conn-auto-${inputHandleId}-${targetHandleId}-${Date.now()}-${Math.random()}`,
              source: inputHandleId,
              target: targetHandleId,
              sourceHandle: inputHandleId,
              targetHandle: targetHandleId,
              type: connectionType,
            });
          }
        }
      });
      
      if (connectionsToAdd.length > 0) {
        return [...filtered, ...connectionsToAdd];
      }
      
      return filtered;
    });
  }, [node, data, connectionType, allNodes, setConnections]);

  // Handle parameter expression change
  const handleParamExpressionChange = React.useCallback((paramName: string, expression: string) => {
    if (!node || !data) return;
    const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
    const newExpressions = { ...expressions, [paramName]: expression };
    // Clear stale validationIssues so fixed params don't keep old backend/frontend errors
    const { validationIssues, ...rest } = data as any;

    // For agents, also update the root-level fields for backend compatibility
    const updateData: any = { ...rest, paramExpressions: newExpressions };
    if (paramName === 'prompt') {
      updateData.prompt = expression;
    }
    if (paramName === 'systemPrompt') {
      updateData.systemPrompt = expression;
    }
    // For classify nodes
    if (paramName === 'classifyParams') {
      updateData.classifyParams = expression;
    }
    // For guardrail nodes
    if (paramName === 'guardrailParams') {
      updateData.guardrailParams = expression;
    }

    onUpdate(updateData);

    setTimeout(() => {
      const paramHandleId = `param-${paramName}-${node.id}`;
      createConnectionsForVariables(expression, paramHandleId);
    }, 0);
  }, [node, data, onUpdate, createConnectionsForVariables]);

  // Handle tool parameter expression change
  const handleToolParamExpressionChange = React.useCallback((paramName: string, expression: string) => {
    if (!node?.data) return;
    const data = node.data;
    const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
    const newExpressions = { ...expressions, [paramName]: expression };
    // Clear stale validationIssues so fixed params don't keep old backend/frontend errors
    const { validationIssues, ...rest } = data as any;
    onUpdate({ ...rest, paramExpressions: newExpressions });
    
    setTimeout(() => {
      const paramHandleId = `param-${paramName}-${node.id}`;
      createConnectionsForVariables(expression, paramHandleId);
    }, 0);
  }, [node, onUpdate, createConnectionsForVariables]);

  // Handle condition expression change
  const handleConditionExpressionChange = React.useCallback((conditionId: string, expression: string) => {
    if (!node || !data) return;
    const conditions = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
    const updatedConditions = conditions.map(c => 
      c.id === conditionId ? { ...c, expression } : c
    );
    const { validationIssues, ...rest } = data as any;
    onUpdate({ ...rest, decisionConditions: updatedConditions });
    
    setTimeout(() => {
      const condition = updatedConditions.find(c => c.id === conditionId);
      if (condition && node) {
        let conditionHandleId = '';
        if (condition.type === 'if') {
          conditionHandleId = `condition-if-${node.id}`;
        } else if (condition.type === 'elseif') {
          const sorted = [...updatedConditions].sort((a, b) => {
            const order = { 'if': 0, 'elseif': 1, 'else': 2 };
            return (order[a.type] ?? 999) - (order[b.type] ?? 999);
          });
          const elseifIndex = sorted.slice(0, sorted.findIndex(c => c.id === conditionId) + 1)
            .filter(c => c.type === 'elseif').length - 1;
          conditionHandleId = `condition-elseif-${elseifIndex}-${node.id}`;
        } else {
          conditionHandleId = `condition-else-${node.id}`;
        }
        createConnectionsForVariables(expression, conditionHandleId);
      }
    }, 0);
  }, [node, data, onUpdate, createConnectionsForVariables]);

  // Handle loop condition expression change
  const handleLoopConditionExpressionChange = React.useCallback((expression: string) => {
    if (!node || !data) return;
    // Clear stale validationIssues when updating loop condition
    const { validationIssues, ...rest } = data as any;
    onUpdate({ ...rest, loopCondition: expression });

    setTimeout(() => {
      const loopConditionHandleId = `loop-condition-${node.id}`;
      createConnectionsForVariables(expression, loopConditionHandleId);
    }, 0);
  }, [node, data, onUpdate, createConnectionsForVariables]);

  // Handle list expression change (for Split node)
  const handleListExpressionChange = React.useCallback((expression: string) => {
    if (!node || !data) return;
    // Clear stale validationIssues when updating list expression
    const { validationIssues, ...rest } = data as any;
    onUpdate({ ...rest, list: expression });

    setTimeout(() => {
      const listExpressionHandleId = `list-expression-${node.id}`;
      createConnectionsForVariables(expression, listExpressionHandleId);
    }, 0);
  }, [node, data, onUpdate, createConnectionsForVariables]);

  // Auto-sync connections with expressions
  const prevExpressionsRef = React.useRef<string>('');
  
  React.useEffect(() => {
    if (!node?.data) return;
    
    const expressions = (node.data.paramExpressions as Record<string, string> | undefined) || {};
    const expressionsString = JSON.stringify(expressions);
    
    if (expressionsString === prevExpressionsRef.current) {
      return;
    }
    prevExpressionsRef.current = expressionsString;
    
    setConnections(prev => {
      let updatedConnections = [...prev];
      const connectionsToRemove: string[] = [];
      const connectionsToAdd: Connection[] = [];
      
      // Delete connections for variables that are no longer complete
      prev.forEach(conn => {
        const isInputToParam = conn.source.startsWith('input-') && conn.target.startsWith('param-');
        const isParamToInput = conn.target.startsWith('input-') && conn.source.startsWith('param-');
        
        if (isInputToParam || isParamToInput) {
          const inputHandleId = isInputToParam ? conn.source : conn.target;
          const paramHandleId = isInputToParam ? conn.target : conn.source;
          
          const inputMatch = inputHandleId.match(/input-(.+?)-/);
          const paramMatch = paramHandleId.match(/param-(.+?)-/);
          
          if (inputMatch && paramMatch) {
            const inputName = inputMatch[1];
            const paramName = paramMatch[1];
            const expression = expressions[paramName] || '';
            const completeVariables = extractVariables(expression);
            const variableExists = completeVariables.some(v => v.toLowerCase() === inputName.toLowerCase());
            
            if (!variableExists) {
              connectionsToRemove.push(conn.id);
            }
          }
        }
      });
      
      if (connectionsToRemove.length > 0) {
        updatedConnections = updatedConnections.filter(conn => !connectionsToRemove.includes(conn.id));
      }
      
      // Recreate connections for complete variables
      Object.entries(expressions).forEach(([paramName, expression]) => {
        const completeVariables = extractVariables(expression);
        
        completeVariables.forEach(varName => {
          const hasSourceNode = allNodes.some(n => {
            if (n.id === node?.id) return false;
            const output = n.data.output;
            if (output && output.toLowerCase() === varName.toLowerCase()) {
              return true;
            }
            const input = n.data.params;
            if (input && input.toLowerCase() === varName.toLowerCase()) {
              return true;
            }
            return false;
          });
          
          if (hasSourceNode) {
            const inputHandleId = `input-${varName}-${node.id}`;
            const paramHandleId = `param-${paramName}-${node.id}`;
            
            const connectionExists = updatedConnections.some(conn =>
              (conn.source === inputHandleId && conn.target === paramHandleId) ||
              (conn.source === paramHandleId && conn.target === inputHandleId)
            );
            
            const isSourceInput = inputHandleId.startsWith('input-');
            const isTargetParam = paramHandleId.startsWith('param-');
            const isValid = isSourceInput && isTargetParam;
            const alreadyConnected = updatedConnections.some(
              conn => (conn.source === inputHandleId && conn.target === paramHandleId) ||
                      (conn.source === paramHandleId && conn.target === inputHandleId)
            );
            
            if (!connectionExists && isValid && !alreadyConnected) {
              connectionsToAdd.push({
                id: `conn-${inputHandleId}-${paramHandleId}-${Date.now()}`,
                source: inputHandleId,
                target: paramHandleId,
                sourceHandle: inputHandleId,
                targetHandle: paramHandleId,
                type: connectionType,
              });
            }
          }
        });
      });
      
      if (connectionsToAdd.length > 0 || connectionsToRemove.length > 0) {
        return [...updatedConnections, ...connectionsToAdd];
      }
      
      return prev;
    });
  }, [node?.id, node?.data?.paramExpressions, extractVariables, allNodes, connectionType, setConnections]);

  // Normalize to snake_case: convert to lowercase, replace spaces and special chars with underscores
  const toSnakeCase = React.useCallback((str: string): string => {
    if (!str) return '';
    return str
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')  // Replace any non-alphanumeric with underscore
      .replace(/^_+|_+$/g, '')       // Remove leading/trailing underscores
      .replace(/_+/g, '_');          // Replace multiple underscores with single
  }, []);

  // Get column expression
  const getColumnExpression = React.useCallback((columnField: string): string => {
    if (!node?.data) return '';
    const dataSourceData = (node.data as any).dataSourceData;
    const expressions = dataSourceData?.columnExpressions as Record<string, string> | undefined;
    // Normalize column field to snake_case for lookup
    const normalizedColumnField = toSnakeCase(columnField);
    return expressions?.[normalizedColumnField] || '';
  }, [node, toSnakeCase]);

  // Get column label
  const getColumnLabel = React.useCallback((columnField: string): string => {
    if (!node?.data) return '';
    const dataSourceData = (node.data as any).dataSourceData;
    const labels = dataSourceData?.columnLabels as Record<string, string> | undefined;
    // Normalize column field to snake_case for lookup
    const normalizedColumnField = toSnakeCase(columnField);
    return labels?.[normalizedColumnField] || '';
  }, [node, toSnakeCase]);

  // Handle column expression change
  const handleColumnExpressionChange = React.useCallback((columnField: string, expression: string) => {
    if (!node?.data) return;
    const data = node.data;
    const dataSourceData = (data as any).dataSourceData || {};
    const expressions = (dataSourceData.columnExpressions as Record<string, string> | undefined) || {};
    // Normalize column field key to snake_case (but NOT the expression content)
    const normalizedColumnField = toSnakeCase(columnField);
    const newExpressions = { ...expressions, [normalizedColumnField]: expression };
    const { validationIssues, ...rest } = data as any;
    onUpdate({
      ...rest,
      dataSourceData: {
        ...dataSourceData,
        columnExpressions: newExpressions,
      },
    });

    setTimeout(() => {
      const columnHandleId = `column-${normalizedColumnField}-${node.id}`;
      createConnectionsForVariables(expression, columnHandleId);
    }, 0);
  }, [node, onUpdate, createConnectionsForVariables, toSnakeCase]);

  // Handle column label change
  const handleColumnLabelChange = React.useCallback((columnField: string, label: string) => {
    if (!node?.data) return;
    const data = node.data;
    const dataSourceData = (data as any).dataSourceData || {};
    const labels = (dataSourceData.columnLabels as Record<string, string> | undefined) || {};
    const expressions = (dataSourceData.columnExpressions as Record<string, string> | undefined) || {};
    // Normalize column field key to snake_case
    const normalizedColumnField = toSnakeCase(columnField);
    const oldLabel = labels[normalizedColumnField];
    const newLabels = { ...labels, [normalizedColumnField]: label };
    
    // Expressions use unified pattern: {{trigger:label.output.field}}
    // We don't modify expressions here, only labels
    
    // Mettre à jour le trigger
    const { validationIssues, ...rest } = data as any;
    onUpdate({
      ...rest,
      dataSourceData: {
        ...dataSourceData,
        columnLabels: newLabels,
        columnExpressions: expressions, // Garder les expressions existantes, on ne modifie que les labels
      },
    });
    
    // Mettre à jour toutes les références dans les nodes tool qui utilisent l'ancien label
    if (oldLabel && oldLabel !== label && allNodes) {
      const oldLabelPattern = new RegExp(`\\{\\{${oldLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\}\\}`, 'g');
      allNodes.forEach((otherNode) => {
        if (otherNode.id === node.id) return; // Skip le trigger lui-même

        const paramExpressions = (otherNode.data as any)?.paramExpressions;
        if (!paramExpressions) return;

        let hasChanges = false;
        const updatedExpressions: Record<string, string> = {};

        Object.entries(paramExpressions).forEach(([key, expression]) => {
          if (typeof expression === 'string' && oldLabelPattern.test(expression)) {
            updatedExpressions[key] = expression.replace(oldLabelPattern, `{{${label}}}`);
            hasChanges = true;
          } else {
            updatedExpressions[key] = expression as string;
          }
        });

        if (hasChanges) {
          // Note: onUpdate est spécifique au node courant, donc on ne peut pas mettre à jour directement
          // Il faudrait passer une fonction de callback pour mettre à jour tous les nodes
          // Pour l'instant, on log juste le changement nécessaire
          console.log(`[handleColumnLabelChange] Node ${otherNode.id} devrait être mis à jour: ${oldLabel} -> ${label}`, updatedExpressions);
        }
      });
    }
  }, [node, onUpdate, toSnakeCase, allNodes]);

  // Handle column deletion
  const handleDeleteColumn = React.useCallback((columnField: string) => {
    if (!node?.data) return;
    const data = node.data;
    const dataSourceData = (data as any).dataSourceData || {};
    const expressions = (dataSourceData.columnExpressions as Record<string, string> | undefined) || {};
    const labels = (dataSourceData.columnLabels as Record<string, string> | undefined) || {};
    
    // Create new objects without the deleted column
    const newExpressions = { ...expressions };
    const newLabels = { ...labels };
    delete newExpressions[columnField];
    delete newLabels[columnField];
    
    const { validationIssues, ...rest } = data as any;
    onUpdate({
      ...rest,
      dataSourceData: {
        ...dataSourceData,
        columnExpressions: newExpressions,
        columnLabels: newLabels,
      },
    });
    
    // Remove connections for this column
    setConnections(prev => {
      return prev.filter(conn => {
        const isColumnConnection = conn.source.includes(`column-${columnField}-`) || 
                                   conn.target.includes(`column-${columnField}-`);
        return !isColumnConnection;
      });
    });
  }, [node, onUpdate, setConnections]);

  // Handle adding a new column parameter
  const handleAddColumn = React.useCallback(() => {
    if (!node?.data) return;
    const data = node.data;
    const dataSourceData = (data as any).dataSourceData || {};
    const expressions = (dataSourceData.columnExpressions as Record<string, string> | undefined) || {};
    const labels = (dataSourceData.columnLabels as Record<string, string> | undefined) || {};
    
    // Generate a unique field name for the new parameter
    let newFieldName = 'new_parameter';
    let counter = 1;
    while (expressions[newFieldName] !== undefined) {
      newFieldName = `new_parameter_${counter}`;
      counter++;
    }
    
    // For datasources, use unified pattern: {{trigger:label.output.field}}
    const triggerLabelNormalized = normalizeLabel(node?.data?.label || (node?.data as any)?.name || 'default');
    const defaultLabel = newFieldName;
    const defaultExpression = `{{trigger:${triggerLabelNormalized}.output.${newFieldName}}}`;
    
    // Add the new column
    const newExpressions = { ...expressions, [newFieldName]: defaultExpression };
    const newLabels = { ...labels, [newFieldName]: defaultLabel };
    
    const { validationIssues, ...rest } = data as any;
    onUpdate({
      ...rest,
      dataSourceData: {
        ...dataSourceData,
        columnExpressions: newExpressions,
        columnLabels: newLabels,
      },
    });
  }, [node, onUpdate, toSnakeCase]);

  // Editor expression functions
  const getEditorExpression = React.useCallback((): string => {
    if (!node?.data) return '';
    const interfaceData = (node.data as any)?.interfaceData || {};
    return interfaceData.editorExpression || '';
  }, [node]);

  const handleEditorExpressionChange = React.useCallback((value: string) => {
    if (!node?.data) return;
    const data = node.data;
    const interfaceData = (data as any)?.interfaceData || {};
    
    onUpdate({
      ...data,
      interfaceData: {
        ...interfaceData,
        editorExpression: value,
      },
    });
  }, [node, onUpdate]);

  return {
    extractVariables,
    getParamExpression,
    getToolParamExpression,
    getConditionExpression,
    getLoopConditionExpression,
    getListExpression,
    getColumnExpression,
    getColumnLabel,
    findUnknownVariables,
    createConnectionsForVariables,
    handleParamExpressionChange,
    handleToolParamExpressionChange,
    handleConditionExpressionChange,
    handleLoopConditionExpressionChange,
    handleListExpressionChange,
    handleColumnExpressionChange,
    handleColumnLabelChange,
    handleDeleteColumn,
    handleAddColumn,
    // Editor expression
    getEditorExpression,
    handleEditorExpressionChange,
  };
}

