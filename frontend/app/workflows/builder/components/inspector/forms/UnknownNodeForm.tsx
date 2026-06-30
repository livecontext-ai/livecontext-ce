'use client';

import React from 'react';
import { InspectorFormProps } from '../core/types';

/**
 * Fallback form for unknown node types.
 * Displays a message indicating the node type is not recognized.
 */
export function UnknownNodeForm({ node }: InspectorFormProps) {
  return (
    <div className="p-4 text-sm text-muted-foreground">
      <p>Unknown node type: {node.type}</p>
      <p className="mt-2">No form available for this node type.</p>
    </div>
  );
}
