import React from 'react';
import { Trash2 } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { LocalMcpTool, TOOL_CATEGORIES } from '../../types';
import { getToolTypeIcon, getToolTypeLabel } from './constants';

interface ConfiguredToolsListProps {
  tools: LocalMcpTool[];
  onUpdate: (index: number, field: keyof LocalMcpTool, value: string) => void;
  onRemove: (index: number) => void;
}

export const ConfiguredToolsList: React.FC<ConfiguredToolsListProps> = ({ tools, onUpdate, onRemove }) => {
  if (tools.length === 0) {
    return null;
  }

  return (
    <Card className="bg-theme-secondary border-theme">
      <CardHeader>
        <CardTitle className="text-theme-primary">Configured Tools</CardTitle>
        <CardDescription className="text-theme-secondary">
          Manage your local MCP tools
        </CardDescription>
      </CardHeader>

      <CardContent>
        <div className="space-y-4">
          {tools.map((tool, index) => {
            const Icon = getToolTypeIcon(tool.toolType);
            return (
              <div key={tool.id || index} className="bg-theme-tertiary p-4 rounded-lg border border-theme">
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-3 flex-1">
                    <div className="w-10 h-10 bg-blue-600 rounded-lg flex items-center justify-center">
                      <Icon className="w-5 h-5 text-white" />
                    </div>

                    <div className="flex-1 min-w-0 space-y-4">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                          <Label className="text-theme-primary text-sm">Name</Label>
                          <Input
                            value={tool.name}
                            onChange={(e) => onUpdate(index, 'name', e.target.value)}
                            className="bg-theme-secondary border-theme text-theme-primary mt-1"
                          />
                        </div>

                        <div>
                          <Label className="text-theme-primary text-sm">Category</Label>
                          <Select value={tool.toolCategory} onValueChange={(value) => onUpdate(index, 'toolCategory', value)}>
                            <SelectTrigger className="bg-theme-secondary border-theme text-theme-primary mt-1">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {TOOL_CATEGORIES.map(category => (
                                <SelectItem key={category} value={category}>
                                  {category}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                      </div>

                      <div>
                        <Label className="text-theme-primary text-sm">Description</Label>
                        <Textarea
                          value={tool.description}
                          onChange={(e) => onUpdate(index, 'description', e.target.value)}
                          rows={2}
                          className="bg-theme-secondary border-theme text-theme-primary mt-1"
                        />
                      </div>

                      <div>
                        <Label className="text-theme-primary text-sm">Command</Label>
                        <Textarea
                          value={tool.command}
                          onChange={(e) => onUpdate(index, 'command', e.target.value)}
                          rows={2}
                          className="bg-theme-secondary border-theme text-theme-primary font-mono mt-1"
                        />
                      </div>

                      <div className="mt-3 flex items-center gap-2">
                        <span className="inline-block px-2 py-1 text-xs bg-blue-500/20 text-blue-600 dark:text-blue-400 rounded-full">
                          {getToolTypeLabel(tool.toolType)}
                        </span>
                        <span className="inline-block px-2 py-1 text-xs bg-gray-500/20 text-gray-600 dark:text-gray-400 rounded-full">
                          {tool.toolCategory}
                        </span>
                      </div>
                    </div>
                  </div>

                  <Button
                    onClick={() => onRemove(index)}
                    variant="outline"
                    size="sm"
                    className="text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-900/30 ml-4"
                  >
                    <Trash2 className="w-4 h-4" />
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
};

export default ConfiguredToolsList;
