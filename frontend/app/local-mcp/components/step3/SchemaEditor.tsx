import React from 'react';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

interface SchemaEditorProps {
  inputSchema: string;
  outputSchema: string;
  onInputChange: (value: string) => void;
  onOutputChange: (value: string) => void;
}

export const SchemaEditor: React.FC<SchemaEditorProps> = ({
  inputSchema,
  outputSchema,
  onInputChange,
  onOutputChange
}) => (
  <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
    <div>
      <Label className="text-theme-primary text-base font-medium">Input Schema (JSON Schema)</Label>
      <p className="text-sm text-theme-secondary mb-3">Defines the structure of input data</p>
      <Textarea
        value={inputSchema}
        onChange={(e) => onInputChange(e.target.value)}
        rows={12}
        className="bg-theme-tertiary border-theme text-theme-primary font-mono text-sm"
        placeholder='{"type": "object", "properties": {...}}'
      />
    </div>

    <div>
      <Label className="text-theme-primary text-base font-medium">Output Schema (JSON Schema)</Label>
      <p className="text-sm text-theme-secondary mb-3">Defines the structure of output data</p>
      <Textarea
        value={outputSchema}
        onChange={(e) => onOutputChange(e.target.value)}
        rows={12}
        className="bg-theme-tertiary border-theme text-theme-primary font-mono text-sm"
        placeholder='{"type": "object", "properties": {...}}'
      />
    </div>
  </div>
);

export default SchemaEditor;
