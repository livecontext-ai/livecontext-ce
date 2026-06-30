import React from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { LocalMcpTool } from '../../types';

interface AdvancedSettingsProps {
  tool: LocalMcpTool;
  onChange: (changes: Partial<LocalMcpTool>) => void;
}

export const AdvancedSettings: React.FC<AdvancedSettingsProps> = ({ tool, onChange }) => (
  <div className="space-y-6">
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      <div>
        <Label htmlFor="workingDirectory" className="text-theme-primary">
          Working directory
        </Label>
        <Input
          id="workingDirectory"
          value={tool.workingDirectory || ''}
          onChange={(e) => onChange({ workingDirectory: e.target.value })}
          placeholder="."
          className="bg-theme-tertiary border-theme text-theme-primary"
        />
        <p className="text-xs text-theme-secondary mt-1">
          Directory where to execute the command (default: current directory)
        </p>
      </div>

      <div>
        <Label htmlFor="version" className="text-theme-primary">
          Version
        </Label>
        <Input
          id="version"
          value={tool.version}
          onChange={(e) => onChange({ version: e.target.value })}
          placeholder="1.0.0"
          className="bg-theme-tertiary border-theme text-theme-primary"
        />
      </div>
    </div>

    <div>
      <Label htmlFor="environmentVariables" className="text-theme-primary">
        Environment variables
      </Label>
      <Textarea
        id="environmentVariables"
        value={tool.environmentVariables || ''}
        onChange={(e) => onChange({ environmentVariables: e.target.value })}
        placeholder="VAR1=value1\nVAR2=value2"
        rows={4}
        className="bg-theme-tertiary border-theme text-theme-primary font-mono"
      />
      <p className="text-xs text-theme-secondary mt-1">One variable per line in VAR=value format</p>
    </div>

    <div>
      <Label htmlFor="documentation" className="text-theme-primary">
        Documentation
      </Label>
      <Textarea
        id="documentation"
        value={tool.documentation || ''}
        onChange={(e) => onChange({ documentation: e.target.value })}
        placeholder="Detailed tool documentation..."
        rows={4}
        className="bg-theme-tertiary border-theme text-theme-primary"
      />
    </div>

    <div>
      <Label htmlFor="rateLimit" className="text-theme-primary">
        Rate limit
      </Label>
      <Input
        id="rateLimit"
        value={tool.rateLimit || ''}
        onChange={(e) => onChange({ rateLimit: e.target.value })}
        placeholder="100 requests/minute"
        className="bg-theme-tertiary border-theme text-theme-primary"
      />
    </div>
  </div>
);

export default AdvancedSettings;
