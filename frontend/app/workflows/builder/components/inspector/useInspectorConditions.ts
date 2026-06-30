'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow, ConditionType } from '../../types';
import { createDefaultDecisionConditions } from '../../types';
import type { Connection } from './useInspectorConnections';

interface UseInspectorConditionsProps {
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  onUpdate: (data: BuilderNodeData) => void;
  setConnections: React.Dispatch<React.SetStateAction<Connection[]>>;
}

// Helper function to sort conditions: if first, then elseif (oldest first), then else
function sortConditions(conditions: ConditionRow[]): ConditionRow[] {
  const sorted = [...conditions];
  sorted.sort((a, b) => {
    const order = { 'if': 0, 'elseif': 1, 'else': 2 };
    const typeOrder = (order[a.type] ?? 999) - (order[b.type] ?? 999);
    
    // If same type (both elseif), sort by timestamp ascending (oldest first)
    if (typeOrder === 0 && a.type === 'elseif' && b.type === 'elseif') {
      // Extract timestamp from ID (format: elseif-{timestamp})
      const aTimestamp = parseInt(a.id.split('-').slice(-1)[0]) || 0;
      const bTimestamp = parseInt(b.id.split('-').slice(-1)[0]) || 0;
      return aTimestamp - bTimestamp; // Ascending timestamp = oldest first
    }
    
    return typeOrder;
  });
  return sorted;
}

export function useInspectorConditions({
  node,
  data,
  onUpdate,
  setConnections,
}: UseInspectorConditionsProps) {
  // Get sorted conditions
  const rawConditions: ConditionRow[] =
    (data?.decisionConditions as ConditionRow[] | undefined) ?? (node?.id ? createDefaultDecisionConditions(node.id) : []);
  
  const currentConditions = React.useMemo(() => {
    return sortConditions(rawConditions);
  }, [rawConditions, data?.decisionConditions, data?.id, node?.type]);

  // Get condition handle ID
  const getConditionHandleId = React.useCallback((condition: ConditionRow, index: number) => {
    if (!node) return '';
    if (condition.type === 'if') {
      return `condition-if-${node.id}`;
    } else if (condition.type === 'elseif') {
      const elseifIndex = currentConditions
        .slice(0, index + 1)
        .filter(c => c.type === 'elseif').length - 1;
      return `condition-elseif-${elseifIndex}-${node.id}`;
    } else {
      return `condition-else-${node.id}`;
    }
  }, [node, currentConditions]);

  // Update conditions
  const handleUpdateConditions = React.useCallback(
    (updater: (prev: ConditionRow[]) => ConditionRow[]) => {
      if (!data || !node) return;
      const existing = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
      const next = updater(existing);
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, decisionConditions: next });
    },
    [data, onUpdate, node],
  );

  // Add condition
  const handleAddCondition = React.useCallback(
    (type: ConditionType, afterIndex?: number) => {
      handleUpdateConditions((prev) => {
        const sorted = sortConditions(prev);
        
        if (type === 'else' && sorted.some((row) => row.type === 'else')) {
          return sorted;
        }
        
        const suffix = sorted.filter((row) => row.type === type).length + 1;
        const label =
          type === 'if' ? `IF ${suffix}` : type === 'elseif' ? `ELSE IF ${suffix}` : 'ELSE';
        const newCondition: ConditionRow = {
          id: `${type}-${Date.now()}`,
          type,
          label,
          expression: '',
        };
        
        if (type === 'else') {
          return sorted.concat(newCondition);
        }
        
        if (type === 'elseif' && afterIndex !== undefined && afterIndex >= 0) {
          const newConditions = [...sorted];
          // Insert new elseif after the IF (at afterIndex + 1)
          newConditions.splice(afterIndex + 1, 0, newCondition);
          
          const hasElse = newConditions.some((row) => row.type === 'else');
          if (!hasElse) {
            const elseCondition: ConditionRow = {
              id: `else-${Date.now()}`,
              type: 'else',
              label: 'ELSE',
              expression: '',
            };
            newConditions.push(elseCondition);
          }
          
          // Re-sort to maintain order (oldest elseif first)
          return sortConditions(newConditions);
        }
        
        return sortConditions(sorted.concat(newCondition));
      });
    },
    [handleUpdateConditions],
  );

  // Delete condition
  const handleDeleteCondition = React.useCallback(
    (id: string) => {
      if (!node || !data) return;
      
      const conditions = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
      const sorted = [...conditions].sort((a, b) => {
        const order = { 'if': 0, 'elseif': 1, 'else': 2 };
        return (order[a.type] ?? 999) - (order[b.type] ?? 999);
      });
      const target = sorted.find((row) => row.id === id);
      
      if (!target || target.type === 'if' || sorted.length <= 1) {
        return;
      }
      
      let conditionHandleId = '';
      let deletedElseIfIndex = -1;
      
      if (target.type === 'elseif') {
        deletedElseIfIndex = sorted.slice(0, sorted.findIndex(c => c.id === id) + 1)
          .filter(c => c.type === 'elseif').length - 1;
        conditionHandleId = `condition-elseif-${deletedElseIfIndex}-${node.id}`;
      } else if (target.type === 'else') {
        conditionHandleId = `condition-else-${node.id}`;
      }
      
      // Update connections
      setConnections(prev => {
        let newConnections = prev.filter(conn => 
          conn.source !== conditionHandleId && conn.target !== conditionHandleId
        );

        if (deletedElseIfIndex !== -1) {
          newConnections = newConnections.map(conn => {
            let newSource = conn.source;
            let newTarget = conn.target;
            let changed = false;

            const sourceMatch = conn.source.match(/condition-elseif-(\d+)-(.+)/);
            if (sourceMatch && sourceMatch[2] === node.id) {
              const index = parseInt(sourceMatch[1]);
              if (index > deletedElseIfIndex) {
                newSource = `condition-elseif-${index - 1}-${node.id}`;
                changed = true;
              }
            }

            const targetMatch = conn.target.match(/condition-elseif-(\d+)-(.+)/);
            if (targetMatch && targetMatch[2] === node.id) {
              const index = parseInt(targetMatch[1]);
              if (index > deletedElseIfIndex) {
                newTarget = `condition-elseif-${index - 1}-${node.id}`;
                changed = true;
              }
            }

            if (changed) {
              return { ...conn, source: newSource, target: newTarget };
            }
            return conn;
          });
        }
        return newConnections;
      });
      
      handleUpdateConditions((prev) => {
        const sorted = sortConditions(prev);
        return sorted.filter((row) => row.id !== id);
      });
    },
    [handleUpdateConditions, node, data, setConnections],
  );

  // Rename condition
  const handleRenameCondition = React.useCallback(
    (id: string, label: string) => {
      handleUpdateConditions((prev) =>
        prev.map((row) => (row.id === id ? { ...row, label: label || row.label } : row)),
      );
    },
    [handleUpdateConditions],
  );

  return {
    currentConditions,
    getConditionHandleId,
    handleUpdateConditions,
    handleAddCondition,
    handleDeleteCondition,
    handleRenameCondition,
  };
}

