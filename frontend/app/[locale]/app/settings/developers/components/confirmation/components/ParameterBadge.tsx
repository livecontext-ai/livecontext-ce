'use client';

import React from 'react';
import { PARAMETER_TYPE_COLORS } from '../utils/textHelpers';

interface ParameterBadgeProps {
  type: string;
}

export function ParameterBadge({ type }: ParameterBadgeProps) {
  const colorClass = PARAMETER_TYPE_COLORS[type] || 'bg-gray-100 text-gray-800';

  return (
    <span className={`px-2 py-1 rounded-full text-xs font-medium ${colorClass}`}>
      {type}
    </span>
  );
}
