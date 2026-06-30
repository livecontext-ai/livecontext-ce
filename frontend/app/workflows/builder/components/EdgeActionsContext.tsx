'use client';

import React from 'react';

interface EdgeActionsContextValue {
  hoveredEdgeId: string | null;
  onDeleteEdge: (edgeId: string) => void;
  onUpdateEdgeData: (edgeId: string, data: Record<string, any>) => void;
}

const EdgeActionsContext = React.createContext<EdgeActionsContextValue>({
  hoveredEdgeId: null,
  onDeleteEdge: () => {},
  onUpdateEdgeData: () => {},
});

export function EdgeActionsProvider({
  value,
  children,
}: {
  value: EdgeActionsContextValue;
  children: React.ReactNode;
}) {
  return <EdgeActionsContext.Provider value={value}>{children}</EdgeActionsContext.Provider>;
}

export function useEdgeActions() {
  return React.useContext(EdgeActionsContext);
}
