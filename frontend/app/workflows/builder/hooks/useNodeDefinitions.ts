/**
 * useNodeDefinitions - React Query hook for centralized node output schemas.
 *
 * Fetches NodeDefinition data from GET /api/node-definitions and provides
 * getOutputSchema(backendNodeType) to look up output fields for any node type.
 * Replaces hardcoded *_SCHEMA constants in UnifiedNodeOutput.tsx.
 */

import { useQuery } from '@tanstack/react-query';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import {
  nodeDefinitionService,
  type NodeDefinitionOutputField,
} from '@/lib/api/orchestrator/node-definitions.service';
import type { OutputSchema } from '../components/inspector/outputs/UnifiedNodeOutput';
import { useCallback, useMemo } from 'react';

export function toOutputSchema(fields: NodeDefinitionOutputField[]): OutputSchema[] {
  // Runtime-only fields (e.g. a Split's current_item / current_index) are KEPT and flagged so the
  // OutputColumn and the InputColumn ancestor variable picker surface them (with a "runtime" badge)
  // - they resolve inside the node body ({{item}} / {{core:<label>.output.current_item}}) even
  // though they are not part of the persisted output.
  return fields.map((f) => ({
    key: f.key,
    type: f.type,
    description: f.description,
    ...(f.runtimeOnly ? { runtimeOnly: true } : {}),
    ...(f.children && f.children.length > 0
      ? { children: toOutputSchema(f.children) }
      : {}),
  }));
}

export function useNodeDefinitions() {
  const { isLoading: authLoading } = useAuthGuard();

  const { data: definitions } = useQuery({
    queryKey: ['node-definitions'],
    queryFn: () => nodeDefinitionService.getAll(),
    enabled: !authLoading,
    staleTime: 30 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });

  const schemaMap = useMemo(() => {
    if (!definitions) return new Map<string, OutputSchema[]>();
    const map = new Map<string, OutputSchema[]>();
    for (const def of definitions) {
      map.set(def.nodeType, toOutputSchema(def.outputs));
    }
    return map;
  }, [definitions]);

  const getOutputSchema = useCallback(
    (nodeType: string): OutputSchema[] => {
      return schemaMap.get(nodeType) || [];
    },
    [schemaMap],
  );

  return { getOutputSchema, definitions, isLoaded: !!definitions };
}
