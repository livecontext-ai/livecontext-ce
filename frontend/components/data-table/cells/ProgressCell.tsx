'use client';

import React from 'react';
import type { VisualCellProps } from './types';

export interface ProgressCellExtraProps {
  cellKey: string;
  tempValue?: number;
  onTempChange: (cellKey: string, value: number) => void;
  onProgressSave: (value: number) => void;
}

export function ProgressCell({
  value,
  displayConfig,
  cellKey,
  tempValue,
  onTempChange,
  onProgressSave,
}: VisualCellProps & ProgressCellExtraProps) {
  const progressMax = Number(displayConfig?.max) || 100;
  const progressValue = Math.max(0, Math.min(progressMax, Number(value) || 0));
  const currentTemp = tempValue ?? progressValue;
  const displayValue = tempValue !== undefined ? currentTemp : progressValue;

  return (
    <div className="w-full relative" onClick={(e) => e.stopPropagation()}>
      <div className="flex w-full flex-col items-center gap-2">
        <div className="h-2 w-full rounded-full bg-slate-200 relative">
          <div
            className="h-full rounded-full bg-lime-500 transition-all"
            style={{ width: `${(displayValue / progressMax) * 100}%` }}
          />
          <input
            type="range"
            min={0}
            max={progressMax}
            step={1}
            value={currentTemp}
            onChange={(event) => {
              onTempChange(cellKey, Number(event.currentTarget.value));
            }}
            onPointerUp={(event) => {
              const newValue = Number((event.currentTarget as HTMLInputElement).value);
              if (newValue >= 0) {
                onProgressSave(newValue);
              }
            }}
            onClick={(e) => e.stopPropagation()}
            className="absolute inset-0 w-full h-full cursor-pointer [&::-webkit-slider-track]:bg-transparent [&::-webkit-slider-track]:h-2 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-4 [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-lime-600 [&::-webkit-slider-thumb]:cursor-pointer [&::-webkit-slider-thumb]:shadow-sm [&::-webkit-slider-thumb]:-mt-1 [&::-webkit-slider-thumb]:opacity-0 [&::-webkit-slider-thumb]:group-hover/cell:opacity-100 [&::-moz-range-track]:bg-transparent [&::-moz-range-track]:h-2 [&::-moz-range-thumb]:w-4 [&::-moz-range-thumb]:h-4 [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:bg-lime-600 [&::-moz-range-thumb]:cursor-pointer [&::-moz-range-thumb]:border-0 [&::-moz-range-thumb]:opacity-0 [&::-moz-range-thumb]:group-hover/cell:opacity-100"
            style={{
              WebkitAppearance: 'none',
              background: 'transparent',
              pointerEvents: 'auto',
            }}
          />
        </div>
        <span className="text-[10px] font-semibold text-theme-secondary">{displayValue}%</span>
      </div>
    </div>
  );
}

ProgressCell.editable = false;
