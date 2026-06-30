import React from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { LocalMcpTool } from '../../types';

interface ToolSelectorProps {
  tools: LocalMcpTool[];
  selectedIndex: number;
  onSelect: (index: number) => void;
}

export const ToolSelector: React.FC<ToolSelectorProps> = ({ tools, selectedIndex, onSelect }) => {
  const selectedTool = tools[selectedIndex];

  return (
    <Card className="bg-theme-secondary border-theme">
      <CardHeader>
        <CardTitle className="text-theme-primary">Parameter Configuration</CardTitle>
        <CardDescription className="text-theme-secondary">
          Define input and output parameters for each MCP tool
        </CardDescription>
      </CardHeader>

      <CardContent>
        <div className="mb-6">
          <Label className="text-theme-primary">Tool to configure</Label>
          <Select value={selectedIndex.toString()} onValueChange={(value) => onSelect(parseInt(value, 10))}>
            <SelectTrigger className="bg-theme-tertiary border-theme text-theme-primary">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {tools.map((tool, index) => (
                <SelectItem key={tool.id} value={index.toString()}>
                  {tool.name} ({tool.toolType.replace('LOCAL_', '')})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {selectedTool && (
          <div className="bg-theme-tertiary p-4 rounded-lg border border-theme">
            <h4 className="font-medium text-theme-primary mb-2">{selectedTool.name}</h4>
            <p className="text-sm text-theme-secondary mb-2">{selectedTool.description}</p>
            <code className="text-xs bg-theme-primary/10 px-2 py-1 rounded text-theme-primary">
              {selectedTool.command}
            </code>
          </div>
        )}
      </CardContent>
    </Card>
  );
};

export default ToolSelector;
