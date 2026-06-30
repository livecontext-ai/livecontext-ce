'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';

interface BackEdgeConfigPanelProps {
  edgeId: string;
  condition: string;
  maxIterations: number;
  onConditionChange: (condition: string) => void;
  onMaxIterationsChange: (maxIterations: number) => void;
}

/**
 * Small floating panel for configuring back-edge (loop edge) properties.
 * Rendered inline in the EdgeLabelRenderer when a back-edge is selected.
 */
export function BackEdgeConfigPanel({
  edgeId,
  condition,
  maxIterations,
  onConditionChange,
  onMaxIterationsChange,
}: BackEdgeConfigPanelProps) {
  const t = useTranslations('backEdge');

  return (
    <div
      className="bg-white dark:bg-slate-800 border border-amber-300 dark:border-amber-600 rounded-lg shadow-lg p-3"
      style={{
        pointerEvents: 'all',
        minWidth: '240px',
      }}
      onClick={(e) => e.stopPropagation()}
    >
      <div className="text-xs font-medium text-amber-600 dark:text-amber-400 mb-2">
        {t('title')}
      </div>

      {/* Condition */}
      <div className="mb-2">
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
          {t('condition')}
        </label>
        <input
          type="text"
          value={condition}
          onChange={(e) => onConditionChange(e.target.value)}
          placeholder={t('conditionPlaceholder')}
          className="w-full text-xs px-2 py-1.5 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-1 focus:ring-amber-500"
        />
      </div>

      {/* Max Iterations */}
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
          {t('maxIterations')}
        </label>
        <input
          type="number"
          value={maxIterations}
          onChange={(e) => onMaxIterationsChange(Math.max(1, parseInt(e.target.value) || 1))}
          min={1}
          max={1000}
          className="w-full text-xs px-2 py-1.5 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-1 focus:ring-amber-500"
        />
      </div>
    </div>
  );
}
