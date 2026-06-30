'use client';

import * as React from 'react';
import {
  Select,
  SelectTrigger,
  SelectContent,
  SelectItem,
  SelectValue,
} from '@/components/ui/select';

export type ConnectionType = 'straight' | 'bezier' | 'smoothstep' | 'step' | 'wave';

interface ConnectionTypeSelectorProps {
  value: ConnectionType;
  onChange: (type: ConnectionType) => void;
}

const connectionTypes: Array<{ value: ConnectionType; label: string }> = [
  { value: 'straight', label: 'Straight' },
  { value: 'bezier', label: 'Bezier' },
  { value: 'smoothstep', label: 'Smoothstep' },
  { value: 'step', label: 'Step' },
  { value: 'wave', label: 'Wave' },
];

export function ConnectionTypeSelector({ value, onChange }: ConnectionTypeSelectorProps) {
  return (
    <Select value={value} onValueChange={(v) => onChange(v as ConnectionType)}>
      <SelectTrigger
        className="h-9 min-h-[36px] py-0 rounded-xl text-sm"
        onClick={(e) => e.stopPropagation()}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {connectionTypes.map((type) => (
          <SelectItem key={type.value} value={type.value}>
            {type.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
