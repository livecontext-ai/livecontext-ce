'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useMcpToolDetails } from '../../hooks/useMcpData';

interface UseInspectorToolDetailsProps {
  node: Node<BuilderNodeData> | null;
  isToolNode: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

export function useInspectorToolDetails({
  node,
  isToolNode,
  onUpdate,
}: UseInspectorToolDetailsProps) {
  // Derive toolSlug from node for details fetching
  const toolSlug = React.useMemo(() => {
    if (isToolNode && node?.data) {
      return (node.data as any)?.toolData?.toolSlug || null;
    }
    return null;
  }, [isToolNode, node?.data]);

  // Use hook for tool details
  const { 
    data: fetchedToolDetails, 
    isLoading: isToolDetailsLoading 
  } = useMcpToolDetails(toolSlug);
  
  const toolDetails = fetchedToolDetails || null;
  const loadingToolDetails = isToolDetailsLoading;

  // Get selected tool parameters from toolData
  const toolParameters = React.useMemo(() => {
    if ((node?.data as any)?.toolData?.parameters) {
      return (node.data as any).toolData.parameters;
    }
    return [];
  }, [node?.data]);
  
  // Get tool responses from toolData
  const toolResponses = React.useMemo(() => {
    if ((node?.data as any)?.toolData?.responses) {
      return (node.data as any).toolData.responses;
    }
    return [];
  }, [node?.data]);
  
  // Get tool credentials from toolData
  const toolCredentials = React.useMemo(() => {
    if ((node?.data as any)?.toolData?.credentials) {
      return (node.data as any).toolData.credentials;
    }
    return [];
  }, [node?.data]);

  // Sync tool details to node data
  React.useEffect(() => {
    if (isToolNode && node?.data && toolDetails) {
      const toolData = (node.data as any)?.toolData;

      const currentParams = toolData?.parameters || [];
      const currentResponses = toolData?.responses || [];
      const currentCredentials = toolData?.credentials || [];

      const newParams = toolDetails.parameters || [];
      const newResponses = toolDetails.responses || [];
      const newCredentials = toolDetails.credentials || [];

      // Detect when the param shape gained metadata fields (defaultValue / allowedValues /
      // extras.picker) that weren't in the saved snapshot. Without this, workflows saved before
      // those fields were introduced would never surface the inspector dropdown or the Drive
      // file picker until the user re-bound the tool.
      //
      // Per-param diff (rather than "any param has metadata"): catches the edge
      // case where the snapshot already had defaultValue on param A but param B
      // gained allowedValues (or a picker hint) server-side - the boolean check would miss it.
      const metadataKey = (p: any) =>
        `${p?.name || ''}|${p?.defaultValue ?? ''}|${
          Array.isArray(p?.allowedValues) ? p.allowedValues.join(',') : ''
        }|${p?.extras?.picker?.mimeType ?? ''}`;
      const currentMetadataSet = new Set(currentParams.map(metadataKey));
      const paramShapeUpgraded = newParams.some(
        (p: any) =>
          (p?.defaultValue != null ||
            (Array.isArray(p?.allowedValues) && p.allowedValues.length > 0) ||
            p?.extras?.picker != null) &&
          !currentMetadataSet.has(metadataKey(p))
      );

      // Only update if we have new data and it differs from current data
      const missingToolName = !toolData?.toolName && toolDetails.name;
      const hasChanges =
        missingToolName ||
        paramShapeUpgraded ||
        currentParams.length !== newParams.length ||
        currentResponses.length !== newResponses.length ||
        currentCredentials.length !== newCredentials.length ||
        (newParams.length > 0 && currentParams.length === 0) ||
        (newResponses.length > 0 && currentResponses.length === 0) ||
        (newCredentials.length > 0 && currentCredentials.length === 0);

      if (hasChanges) {
        const updatedData = {
          ...node.data,
          toolData: {
            ...toolData,
            ...(toolDetails.name && !toolData?.toolName ? { toolName: toolDetails.name } : {}),
            parameters: newParams,
            responses: newResponses,
            credentials: toolDetails.credentials || [],
          },
        };
        onUpdate(updatedData);
      }
    }
  }, [isToolNode, node?.id, node?.data, toolDetails, onUpdate]);

  return {
    toolSlug,
    toolDetails,
    loadingToolDetails,
    toolParameters,
    toolResponses,
    toolCredentials,
  };
}

