'use client';

import * as React from 'react';
import { Info } from 'lucide-react';
import type { Node } from 'reactflow';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';

// Maximum wait duration: 10 minutes in milliseconds
const MAX_DURATION_MS = 10 * 60 * 1000; // 600000ms

interface WaitParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Form component for Wait node parameters.
 * Simple duration input in milliseconds with a max of 10 minutes.
 */
export function WaitParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
}: WaitParametersFormProps) {
  // Get duration from node data (default: 1000ms = 1 second)
  const duration: number = (data as any).waitDuration ?? 1000;

  // Convert milliseconds to display values
  const durationInSeconds = Math.floor(duration / 1000);
  const durationInMinutes = Math.floor(duration / 60000);

  const handleDurationChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    if (value === '') {
      onUpdate({
        ...data,
        waitDuration: 0,
      } as BuilderNodeData);
      return;
    }

    const numValue = parseInt(value, 10);
    if (isNaN(numValue) || numValue < 0) {
      return;
    }

    // Clamp to max duration
    const clampedValue = Math.min(MAX_DURATION_MS, Math.max(0, numValue));
    onUpdate({
      ...data,
      waitDuration: clampedValue,
    } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  // Format display string
  const getDisplayDuration = () => {
    if (duration >= 60000) {
      const mins = Math.floor(duration / 60000);
      const secs = Math.floor((duration % 60000) / 1000);
      const ms = duration % 1000;
      if (ms > 0) {
        return `${mins}m ${secs}s ${ms}ms`;
      }
      if (secs > 0) {
        return `${mins}m ${secs}s`;
      }
      return `${mins} minute${mins > 1 ? 's' : ''}`;
    }
    if (duration >= 1000) {
      const secs = Math.floor(duration / 1000);
      const ms = duration % 1000;
      if (ms > 0) {
        return `${secs}s ${ms}ms`;
      }
      return `${secs} second${secs > 1 ? 's' : ''}`;
    }
    return `${duration}ms`;
  };

  return (
    <div className="space-y-4 pt-2">
      {/* Duration input */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Duration (milliseconds)</span>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                  <p className="font-semibold text-slate-900 dark:text-slate-100">Wait Node</p>
                  <p>Pauses workflow execution for a specified duration.</p>
                  <ul className="list-disc list-inside space-y-1 text-xs">
                    <li>Duration is specified in milliseconds</li>
                    <li>Maximum wait time: 10 minutes (600,000ms)</li>
                    <li>Use presets for common durations</li>
                  </ul>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">Required</span>
        </div>
        <Input
          type="number"
          min="0"
          max={MAX_DURATION_MS}
          step="100"
          value={duration}
          onChange={handleDurationChange}
          className="w-full"
          placeholder="1000"
          readOnly={isRunMode}
        />
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {getDisplayDuration()}
        </p>
      </div>

      {/* Quick presets */}
      {!isRunMode && (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">Quick Presets</span>
          <div className="flex flex-wrap gap-2">
            {[
              { label: '100ms', value: 100 },
              { label: '500ms', value: 500 },
              { label: '1s', value: 1000 },
              { label: '5s', value: 5000 },
              { label: '1m', value: 60000 },
              { label: '5m', value: 300000 },
              { label: '10m', value: 600000 },
            ].map((preset) => (
              <button
                key={preset.value}
                type="button"
                onClick={() => onUpdate({ ...data, waitDuration: preset.value } as BuilderNodeData)}
                className={`px-2 py-1 text-xs rounded-md transition-colors ${
                  duration === preset.value
                    ? 'bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900'
                    : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
