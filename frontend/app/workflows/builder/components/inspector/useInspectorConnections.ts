'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow } from '../../types';
import { createDefaultDecisionConditions } from '../../types';
import type { ConnectionType } from '../ConnectionTypeSelector';

export interface Connection {
  id: string;
  source: string;
  target: string;
  sourceHandle: string | null;
  targetHandle: string | null;
  type?: ConnectionType;
}

export interface HandlePosition {
  id: string;
  x: number;
  y: number;
}

interface UseInspectorConnectionsProps {
  node: Node<BuilderNodeData> | null;
  connectionType: ConnectionType;
  allNodes: Node<BuilderNodeData>[];
  onUpdate: (data: BuilderNodeData) => void;
  panelRef: React.RefObject<HTMLDivElement>;
}

export function useInspectorConnections({
  node,
  connectionType,
  allNodes,
  onUpdate,
  panelRef,
}: UseInspectorConnectionsProps) {
  const [connections, setConnections] = React.useState<Connection[]>([]);
  const [draggingFromHandle, setDraggingFromHandle] = React.useState<string | null>(null);
  const [mousePosition, setMousePosition] = React.useState<{ x: number; y: number } | null>(null);
  const [hoveredTargetHandle, setHoveredTargetHandle] = React.useState<string | null>(null);
  const [dragStartPosition, setDragStartPosition] = React.useState<{ x: number; y: number } | null>(null);
  const handleRefs = React.useRef<Map<string, HTMLDivElement>>(new Map());
  const [handlePositions, setHandlePositions] = React.useState<Map<string, HandlePosition>>(new Map());

  // Function to determine if a connection is valid
  const isValidConnection = React.useCallback((sourceId: string, targetId: string): boolean => {
    if (sourceId === targetId) return false;
    
    const isSourceInput = sourceId.startsWith('input-');
    const isTargetInput = targetId.startsWith('input-');
    if (isSourceInput && isTargetInput) return false;
    
    const isSourceParam = sourceId.startsWith('param-');
    const isTargetParam = targetId.startsWith('param-');
    if (isSourceParam && isTargetParam) return false;
    
    const isSourceCondition = sourceId.startsWith('condition-');
    const isTargetCondition = targetId.startsWith('condition-');
    if (isSourceCondition && isTargetCondition) return false;
    
    const isSourceLoopCondition = sourceId.startsWith('loop-condition-');
    const isTargetLoopCondition = targetId.startsWith('loop-condition-');
    if (isSourceLoopCondition && isTargetLoopCondition) return false;
    
    if ((isSourceCondition && isTargetLoopCondition) || (isSourceLoopCondition && isTargetCondition)) return false;
    
    const alreadyConnected = connections.some(
      conn => (conn.source === sourceId && conn.target === targetId) ||
              (conn.source === targetId && conn.target === sourceId)
    );
    if (alreadyConnected) return false;
    
    return true;
  }, [connections]);

  // Helper to create connection and update data
  const handleCreateConnection = React.useCallback((sourceHandleId: string, targetHandleId: string) => {
    if (!isValidConnection(sourceHandleId, targetHandleId)) return;
    
    const newConnection: Connection = {
      id: `conn-${sourceHandleId}-${targetHandleId}-${Date.now()}`,
      source: sourceHandleId,
      target: targetHandleId,
      sourceHandle: sourceHandleId,
      targetHandle: targetHandleId,
      type: connectionType,
    };
    setConnections(prev => [...prev, newConnection]);
    
    // Insert variables into expressions automatically
    if (sourceHandleId.startsWith('input-') && targetHandleId.startsWith('param-')) {
      const inputMatch = sourceHandleId.match(/input-(.+?)-/);
      const paramMatch = targetHandleId.match(/param-(.+?)-/);
      if (inputMatch && paramMatch && node?.data) {
        const inputName = inputMatch[1];
        const paramName = paramMatch[1];
        const data = node.data;
        const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
        const existingExpression = expressions[paramName] || '';
        const variable = `\${${inputName}}`;
        
        if (!existingExpression.includes(variable)) {
          const newExpression = existingExpression.trim() === '' 
            ? variable 
            : `${existingExpression} ${variable}`;
          const newExpressions = { ...expressions, [paramName]: newExpression };
          queueMicrotask(() => {
            onUpdate({ ...data, paramExpressions: newExpressions });
          });
        }
      }
    } else if (targetHandleId.startsWith('input-') && sourceHandleId.startsWith('param-')) {
      const inputMatch = targetHandleId.match(/input-(.+?)-/);
      const paramMatch = sourceHandleId.match(/param-(.+?)-/);
      if (inputMatch && paramMatch && node?.data) {
        const inputName = inputMatch[1];
        const paramName = paramMatch[1];
        const data = node.data;
        const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
        const existingExpression = expressions[paramName] || '';
        const variable = `\${${inputName}}`;
        
        if (!existingExpression.includes(variable)) {
          const newExpression = existingExpression.trim() === '' 
            ? variable 
            : `${existingExpression} ${variable}`;
          const newExpressions = { ...expressions, [paramName]: newExpression };
          queueMicrotask(() => {
            onUpdate({ ...data, paramExpressions: newExpressions });
          });
        }
      }
    } else if (sourceHandleId.startsWith('input-') && targetHandleId.startsWith('condition-')) {
      const inputMatch = sourceHandleId.match(/input-(.+?)-/);
      const conditionMatch = targetHandleId.match(/condition-(if|elseif-\d+|else)-(.+)/);
      if (inputMatch && conditionMatch && node?.data) {
        const inputName = inputMatch[1];
        const conditionTypeStr = conditionMatch[1];
        const currentData = node.data;
        const conditions = (currentData.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(currentData.id);
        let condition: ConditionRow | undefined;
        if (conditionTypeStr === 'if') {
          condition = conditions.find(c => c.type === 'if');
        } else if (conditionTypeStr === 'else') {
          condition = conditions.find(c => c.type === 'else');
        } else if (conditionTypeStr.startsWith('elseif-')) {
          const elseifIndex = parseInt(conditionTypeStr.split('-')[1]);
          const elseifConditions = conditions.filter(c => c.type === 'elseif');
          condition = elseifConditions[elseifIndex];
        }
        if (condition && condition.type !== 'else') {
          const existingExpression = condition.expression || '';
          const variable = `\${${inputName}}`;
          if (!existingExpression.includes(variable)) {
            const newExpression = existingExpression.trim() === '' 
              ? variable 
              : `${existingExpression} ${variable}`;
            const updatedConditions = conditions.map(c => 
              c.id === condition.id ? { ...c, expression: newExpression } : c
            );
            setTimeout(() => {
              onUpdate({ ...currentData, decisionConditions: updatedConditions });
            }, 0);
          }
        }
      }
    } else if (targetHandleId.startsWith('input-') && sourceHandleId.startsWith('condition-')) {
      const inputMatch = targetHandleId.match(/input-(.+?)-/);
      const conditionMatch = sourceHandleId.match(/condition-(if|elseif-\d+|else)-(.+)/);
      if (inputMatch && conditionMatch && node?.data) {
        const inputName = inputMatch[1];
        const conditionTypeStr = conditionMatch[1];
        const currentData = node.data;
        const conditions = (currentData.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(currentData.id);
        let condition: ConditionRow | undefined;
        if (conditionTypeStr === 'if') {
          condition = conditions.find(c => c.type === 'if');
        } else if (conditionTypeStr === 'else') {
          condition = conditions.find(c => c.type === 'else');
        } else if (conditionTypeStr.startsWith('elseif-')) {
          const elseifIndex = parseInt(conditionTypeStr.split('-')[1]);
          const elseifConditions = conditions.filter(c => c.type === 'elseif');
          condition = elseifConditions[elseifIndex];
        }
        if (condition && condition.type !== 'else') {
          const existingExpression = condition.expression || '';
          const variable = `\${${inputName}}`;
          if (!existingExpression.includes(variable)) {
            const newExpression = existingExpression.trim() === '' 
              ? variable 
              : `${existingExpression} ${variable}`;
            const updatedConditions = conditions.map(c => 
              c.id === condition.id ? { ...c, expression: newExpression } : c
            );
            setTimeout(() => {
              onUpdate({ ...currentData, decisionConditions: updatedConditions });
            }, 0);
          }
        }
      }
    } else if (sourceHandleId.startsWith('input-') && targetHandleId.startsWith('loop-condition-')) {
      const inputMatch = sourceHandleId.match(/input-(.+?)-/);
      if (inputMatch && node?.data) {
        const inputName = inputMatch[1];
        const data = node.data;
        const existingExpression = data.loopCondition || '';
        const variable = `\${${inputName}}`;
        if (!existingExpression.includes(variable)) {
          const newExpression = existingExpression.trim() === '' 
            ? variable 
            : `${existingExpression} ${variable}`;
          queueMicrotask(() => {
            onUpdate({ ...data, loopCondition: newExpression });
          });
        }
      }
    } else if (targetHandleId.startsWith('input-') && sourceHandleId.startsWith('loop-condition-')) {
      const inputMatch = targetHandleId.match(/input-(.+?)-/);
      if (inputMatch && node?.data) {
        const inputName = inputMatch[1];
        const data = node.data;
        const existingExpression = data.loopCondition || '';
        const variable = `\${${inputName}}`;
        if (!existingExpression.includes(variable)) {
          const newExpression = existingExpression.trim() === '' 
            ? variable 
            : `${existingExpression} ${variable}`;
          queueMicrotask(() => {
            onUpdate({ ...data, loopCondition: newExpression });
          });
        }
      }
    }
  }, [isValidConnection, connectionType, node, onUpdate]);

  // Update handle positions
  React.useEffect(() => {
    if (!panelRef.current) return;
    
    const updatePositions = () => {
      if (!panelRef.current) return;
      const panelRect = panelRef.current.getBoundingClientRect();
      const newPositions = new Map<string, HandlePosition>();
      
      handleRefs.current.forEach((element, id) => {
        const rect = element.getBoundingClientRect();
        newPositions.set(id, {
          id,
          x: rect.left + rect.width / 2 - panelRect.left,
          y: rect.top + rect.height / 2 - panelRect.top,
        });
      });
      
      setHandlePositions(newPositions);
    };
    
    updatePositions();
    const interval = setInterval(updatePositions, 100);
    window.addEventListener('resize', updatePositions);
    window.addEventListener('scroll', updatePositions, true);
    
    return () => {
      clearInterval(interval);
      window.removeEventListener('resize', updatePositions);
      window.removeEventListener('scroll', updatePositions, true);
    };
  }, [node, connections, panelRef]);

  // Track mouse position and detect hovered handles during drag
  React.useEffect(() => {
    if (!draggingFromHandle || !panelRef.current) {
      setMousePosition(null);
      setHoveredTargetHandle(null);
      if (!draggingFromHandle) {
        setDragStartPosition(null);
      }
      return;
    }
    
    const handleMouseMove = (e: MouseEvent) => {
      if (!panelRef.current) return;
      const panelRect = panelRef.current.getBoundingClientRect();
      const currentPos = {
        x: e.clientX - panelRect.left,
        y: e.clientY - panelRect.top,
      };
      setMousePosition(currentPos);
      
      const elementAtPoint = document.elementFromPoint(e.clientX, e.clientY);
      const handleElement = elementAtPoint?.closest('[data-handle-id]') as HTMLElement;
      
      if (handleElement) {
        const targetHandleId = handleElement.getAttribute('data-handle-id');
        if (targetHandleId && targetHandleId !== draggingFromHandle && isValidConnection(draggingFromHandle, targetHandleId)) {
          setHoveredTargetHandle(targetHandleId);
        } else {
          setHoveredTargetHandle(null);
        }
      } else {
        setHoveredTargetHandle(null);
      }
    };
    
    const handleMouseUp = (e: MouseEvent) => {
      const elementAtPoint = document.elementFromPoint(e.clientX, e.clientY);
      const handleElement = elementAtPoint?.closest('[data-handle-id]') as HTMLElement;
      
      if (handleElement && draggingFromHandle) {
        const targetHandleId = handleElement.getAttribute('data-handle-id');
        if (targetHandleId && targetHandleId !== draggingFromHandle && isValidConnection(draggingFromHandle, targetHandleId)) {
          handleCreateConnection(draggingFromHandle, targetHandleId);
        }
      }
      
      setDraggingFromHandle(null);
      setMousePosition(null);
      setHoveredTargetHandle(null);
      setDragStartPosition(null);
    };
    
    window.addEventListener('mousemove', handleMouseMove, { passive: true });
    window.addEventListener('mouseup', handleMouseUp);
    
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [draggingFromHandle, isValidConnection, handleCreateConnection, panelRef]);

  // Handle drag start on a handle
  const handleHandleMouseDown = React.useCallback((handleId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (e.button !== 0) return;
    
    // If Ctrl+click, delete the existing connection
    if (e.ctrlKey) {
      const existingConnection = connections.find(
        conn => (conn.source === handleId || conn.target === handleId)
      );
      if (existingConnection) {
        setConnections(prev => {
          const newConnections = prev.filter(conn => conn.id !== existingConnection.id);
          
          // Remove variable from expression
          if (node?.data) {
            const data = node.data;
            const isInputToParam = existingConnection.source.startsWith('input-') && existingConnection.target.startsWith('param-');
            const isParamToInput = existingConnection.target.startsWith('input-') && existingConnection.source.startsWith('param-');
            const isInputToCondition = existingConnection.source.startsWith('input-') && existingConnection.target.startsWith('condition-');
            const isConditionToInput = existingConnection.target.startsWith('input-') && existingConnection.source.startsWith('condition-');
            const isInputToLoopCondition = existingConnection.source.startsWith('input-') && existingConnection.target.startsWith('loop-condition-');
            const isLoopConditionToInput = existingConnection.target.startsWith('input-') && existingConnection.source.startsWith('loop-condition-');
            
            if (isInputToParam || isParamToInput) {
              const inputHandleId = isInputToParam ? existingConnection.source : existingConnection.target;
              const paramHandleId = isInputToParam ? existingConnection.target : existingConnection.source;
              const inputMatch = inputHandleId.match(/input-(.+?)-/);
              const paramMatch = paramHandleId.match(/param-(.+?)-/);
              if (inputMatch && paramMatch) {
                const inputName = inputMatch[1];
                const paramName = paramMatch[1];
                const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
                const existingExpression = expressions[paramName] || '';
                const variable = `\${${inputName}}`;
                const cleanedExpression = existingExpression
                  .split(variable)
                  .join('')
                  .replace(/\s+/g, ' ')
                  .trim();
                if (cleanedExpression !== existingExpression) {
                  const newExpressions = { ...expressions, [paramName]: cleanedExpression };
                  queueMicrotask(() => {
                    onUpdate({ ...data, paramExpressions: newExpressions });
                  });
                }
              }
            } else if (isInputToCondition || isConditionToInput) {
              const inputHandleId = isInputToCondition ? existingConnection.source : existingConnection.target;
              const conditionHandleId = isInputToCondition ? existingConnection.target : existingConnection.source;
              const inputMatch = inputHandleId.match(/input-(.+?)-/);
              const conditionMatch = conditionHandleId.match(/condition-(if|elseif-\d+|else)-(.+)/);
              if (inputMatch && conditionMatch) {
                const inputName = inputMatch[1];
                const conditionTypeStr = conditionMatch[1];
                const conditions = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
                let condition: ConditionRow | undefined;
                if (conditionTypeStr === 'if') {
                  condition = conditions.find(c => c.type === 'if');
                } else if (conditionTypeStr === 'else') {
                  condition = conditions.find(c => c.type === 'else');
                } else if (conditionTypeStr.startsWith('elseif-')) {
                  const elseifIndex = parseInt(conditionTypeStr.split('-')[1]);
                  const elseifConditions = conditions.filter(c => c.type === 'elseif');
                  condition = elseifConditions[elseifIndex];
                }
                if (condition && condition.type !== 'else') {
                  const existingExpression = condition.expression || '';
                  const variable = `\${${inputName}}`;
                  const cleanedExpression = existingExpression
                    .split(variable)
                    .join('')
                    .replace(/\s+/g, ' ')
                    .trim();
                  if (cleanedExpression !== existingExpression) {
                    const updatedConditions = conditions.map(c => 
                      c.id === condition.id ? { ...c, expression: cleanedExpression } : c
                    );
                    queueMicrotask(() => {
                      onUpdate({ ...data, decisionConditions: updatedConditions });
                    });
                  }
                }
              }
            } else if (isInputToLoopCondition || isLoopConditionToInput) {
              const inputHandleId = isInputToLoopCondition ? existingConnection.source : existingConnection.target;
              const inputMatch = inputHandleId.match(/input-(.+?)-/);
              if (inputMatch) {
                const inputName = inputMatch[1];
                const existingExpression = data.loopCondition || '';
                const variable = `\${${inputName}}`;
                const cleanedExpression = existingExpression
                  .split(variable)
                  .join('')
                  .replace(/\s+/g, ' ')
                  .trim();
                if (cleanedExpression !== existingExpression) {
                  queueMicrotask(() => {
                    onUpdate({ ...data, loopCondition: cleanedExpression });
                  });
                }
              }
            }
          }
          
          return newConnections;
        });
        return;
      }
    }
    
    setDraggingFromHandle(handleId);
    
    setTimeout(() => {
      if (handleRefs.current.has(handleId)) {
        const handleElement = handleRefs.current.get(handleId);
        if (handleElement && panelRef.current) {
          const panelRect = panelRef.current.getBoundingClientRect();
          const handleRect = handleElement.getBoundingClientRect();
          setDragStartPosition({
            x: handleRect.left + handleRect.width / 2 - panelRect.left,
            y: handleRect.top + handleRect.height / 2 - panelRect.top,
          });
        }
      }
    }, 0);
  }, [connections, node, onUpdate, panelRef]);

  // Handle drag end on a handle
  const handleHandleMouseUp = React.useCallback((handleId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    
    if (draggingFromHandle) {
      if (draggingFromHandle !== handleId && isValidConnection(draggingFromHandle, handleId)) {
        handleCreateConnection(draggingFromHandle, handleId);
      }
      setDraggingFromHandle(null);
      setMousePosition(null);
      setHoveredTargetHandle(null);
      setDragStartPosition(null);
    }
  }, [draggingFromHandle, isValidConnection, handleCreateConnection]);

  // Handle click on a handle
  const handleHandleClick = React.useCallback((handleId: string, e: React.MouseEvent) => {
    e.stopPropagation();
  }, []);

  // Helper to register handle refs
  const handleSetHandleRef = React.useCallback((id: string, el: HTMLDivElement | null) => {
    if (el) {
      handleRefs.current.set(id, el);
      el.setAttribute('data-handle-id', id);
    } else {
      handleRefs.current.delete(id);
    }
  }, []);

  // Helper to get connection from input to parameter
  const getParamConnection = React.useCallback((paramName: string): string | null => {
    if (!node) return null;
    const paramHandleId = `param-${paramName}-${node.id}`;
    const connection = connections.find(conn => 
      conn.source === paramHandleId || conn.target === paramHandleId
    );
    if (!connection) return null;
    const targetId = connection.source === paramHandleId ? connection.target : connection.source;
    const match = targetId.match(/input-(.+?)-/);
    return match ? match[1] : null;
  }, [connections, node]);

  // Function to connect input to parameter via selector
  const handleInputParamConnection = React.useCallback((inputName: string, paramName: string | null) => {
    if (!node?.data) return;
    const inputHandleId = `input-${inputName}-${node.id}`;
    const data = node.data;
    
    const oldConnection = connections.find(conn => 
      (conn.source === inputHandleId || conn.target === inputHandleId)
    );
    let oldInputName: string | null = null;
    let oldParamName: string | null = null;
    if (oldConnection) {
      const oldInputMatch = (oldConnection.source.startsWith('input-') ? oldConnection.source : oldConnection.target).match(/input-(.+?)-/);
      const oldParamMatch = (oldConnection.source.startsWith('param-') ? oldConnection.source : oldConnection.target).match(/param-(.+?)-/);
      if (oldInputMatch) {
        oldInputName = oldInputMatch[1];
      }
      if (oldParamMatch) {
        oldParamName = oldParamMatch[1];
      }
    }
    
    setConnections(prev => {
      const newConnections = prev.filter(conn => 
        !(conn.source === inputHandleId || conn.target === inputHandleId)
      );
      
      if (oldConnection && oldInputName && oldParamName) {
        const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
        const existingExpression = expressions[oldParamName] || '';
        
        const variable = `\${${oldInputName}}`;
        const cleanedExpression = existingExpression
          .split(variable)
          .join('')
          .replace(/\s+/g, ' ')
          .trim();
        
        if (cleanedExpression !== existingExpression) {
          const newExpressions = { ...expressions, [oldParamName]: cleanedExpression };
          queueMicrotask(() => {
            onUpdate({ ...data, paramExpressions: newExpressions });
          });
        }
      }
      
      return newConnections;
    });
    
    if (paramName) {
      const paramHandleId = `param-${paramName}-${node.id}`;
      
      const connectionExists = connections.some(conn => 
        (conn.source === inputHandleId && conn.target === paramHandleId) ||
        (conn.source === paramHandleId && conn.target === inputHandleId)
      );
      
      if (!connectionExists) {
        const newConnection: Connection = {
          id: `conn-${inputHandleId}-${paramHandleId}-${Date.now()}`,
          source: inputHandleId,
          target: paramHandleId,
          sourceHandle: inputHandleId,
          targetHandle: paramHandleId,
          type: connectionType,
        };
        setConnections(prev => [...prev, newConnection]);
      }
      
      const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
      const existingExpression = expressions[paramName] || '';
      const variable = `\${${inputName}}`;
      
      if (!existingExpression.includes(variable)) {
        const newExpression = existingExpression.trim() === '' 
          ? variable 
          : `${existingExpression} ${variable}`;
        const newExpressions = { ...expressions, [paramName]: newExpression };
        onUpdate({ ...data, paramExpressions: newExpressions });
      }
    }
  }, [node, connectionType, onUpdate, connections]);

  // Delete connection handler
  const handleDeleteConnection = React.useCallback((connId: string) => {
    setConnections(prev => {
      const conn = prev.find(c => c.id === connId);
      if (!conn) return prev;
      
      const newConnections = prev.filter(c => c.id !== connId);
      
      // Remove variable from expression
      if (node?.data) {
        const data = node.data;
        const isInputToParam = conn.source.startsWith('input-') && conn.target.startsWith('param-');
        const isParamToInput = conn.target.startsWith('input-') && conn.source.startsWith('param-');
        const isInputToCondition = conn.source.startsWith('input-') && conn.target.startsWith('condition-');
        const isConditionToInput = conn.target.startsWith('input-') && conn.source.startsWith('condition-');
        const isInputToLoopCondition = conn.source.startsWith('input-') && conn.target.startsWith('loop-condition-');
        const isLoopConditionToInput = conn.target.startsWith('input-') && conn.source.startsWith('loop-condition-');
        
        if (isInputToParam || isParamToInput) {
          const inputHandleId = isInputToParam ? conn.source : conn.target;
          const paramHandleId = isInputToParam ? conn.target : conn.source;
          const inputMatch = inputHandleId.match(/input-(.+?)-/);
          const paramMatch = paramHandleId.match(/param-(.+?)-/);
          if (inputMatch && paramMatch) {
            const inputName = inputMatch[1];
            const paramName = paramMatch[1];
            const expressions = (data.paramExpressions as Record<string, string> | undefined) || {};
            const existingExpression = expressions[paramName] || '';
            const variable = `\${${inputName}}`;
            const cleanedExpression = existingExpression
              .split(variable)
              .join('')
              .replace(/\s+/g, ' ')
              .trim();
            if (cleanedExpression !== existingExpression) {
              const newExpressions = { ...expressions, [paramName]: cleanedExpression };
              queueMicrotask(() => {
                onUpdate({ ...data, paramExpressions: newExpressions });
              });
            }
          }
        } else if (isInputToCondition || isConditionToInput) {
          const inputHandleId = isInputToCondition ? conn.source : conn.target;
          const conditionHandleId = isInputToCondition ? conn.target : conn.source;
          const inputMatch = inputHandleId.match(/input-(.+?)-/);
          const conditionMatch = conditionHandleId.match(/condition-(if|elseif-\d+|else)-(.+)/);
          if (inputMatch && conditionMatch) {
            const inputName = inputMatch[1];
            const conditionTypeStr = conditionMatch[1];
            const conditions = (data.decisionConditions as ConditionRow[] | undefined) ?? createDefaultDecisionConditions(data.id);
            let condition: ConditionRow | undefined;
            if (conditionTypeStr === 'if') {
              condition = conditions.find(c => c.type === 'if');
            } else if (conditionTypeStr === 'else') {
              condition = conditions.find(c => c.type === 'else');
            } else if (conditionTypeStr.startsWith('elseif-')) {
              const elseifIndex = parseInt(conditionTypeStr.split('-')[1]);
              const elseifConditions = conditions.filter(c => c.type === 'elseif');
              condition = elseifConditions[elseifIndex];
            }
            if (condition && condition.type !== 'else') {
              const existingExpression = condition.expression || '';
              const variable = `\${${inputName}}`;
              const cleanedExpression = existingExpression
                .split(variable)
                .join('')
                .replace(/\s+/g, ' ')
                .trim();
              if (cleanedExpression !== existingExpression) {
                const updatedConditions = conditions.map(c => 
                  c.id === condition.id ? { ...c, expression: cleanedExpression } : c
                );
                queueMicrotask(() => {
                  onUpdate({ ...data, decisionConditions: updatedConditions });
                });
              }
            }
          }
        } else if (isInputToLoopCondition || isLoopConditionToInput) {
          const inputHandleId = isInputToLoopCondition ? conn.source : conn.target;
          const inputMatch = inputHandleId.match(/input-(.+?)-/);
          if (inputMatch) {
            const inputName = inputMatch[1];
            const existingExpression = data.loopCondition || '';
            const variable = `\${${inputName}}`;
            const cleanedExpression = existingExpression
              .split(variable)
              .join('')
              .replace(/\s+/g, ' ')
              .trim();
            if (cleanedExpression !== existingExpression) {
              queueMicrotask(() => {
                onUpdate({ ...data, loopCondition: cleanedExpression });
              });
            }
          }
        }
      }
      
      return newConnections;
    });
  }, [node, onUpdate]);

  return {
    connections,
    setConnections,
    draggingFromHandle,
    mousePosition,
    hoveredTargetHandle,
    dragStartPosition,
    handlePositions,
    handleRefs,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleHandleClick,
    handleSetHandleRef,
    getParamConnection,
    handleInputParamConnection,
    handleDeleteConnection,
    isValidConnection,
    handleCreateConnection,
  };
}

