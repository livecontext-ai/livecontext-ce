import React from 'react';
import { Plus, Trash2, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { McpParameter } from '../../types';

interface ParameterListProps {
  parameters: McpParameter[];
  parameterTypes: { value: string; label: string }[];
  onAdd: () => void;
  onUpdate: (index: number, field: keyof McpParameter, value: string | boolean) => void;
  onRemove: (index: number) => void;
}

export const ParameterList: React.FC<ParameterListProps> = ({
  parameters,
  parameterTypes,
  onAdd,
  onUpdate,
  onRemove
}) => (
  <div className="space-y-6">
    <div className="flex justify-between items-center">
      <div>
        <h3 className="text-lg font-medium text-theme-primary">Input Parameters</h3>
        <p className="text-sm text-theme-secondary">Define the parameters that your tool accepts</p>
      </div>
      <Button onClick={onAdd} size="sm" className="bg-blue-600 hover:bg-blue-700 text-white">
        <Plus className="w-4 h-4 mr-2" />
        Add
      </Button>
    </div>

    {parameters.length === 0 ? (
      <div className="text-center py-8 text-theme-secondary">
        <FileText className="w-12 h-12 mx-auto mb-4 opacity-50" />
        <p>No parameters defined. Click "Add" to start.</p>
      </div>
    ) : (
      <div className="space-y-4">
        {parameters.map((param, index) => (
          <div key={index} className="bg-theme-tertiary p-4 rounded-lg border border-theme">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              <div>
                <Label className="text-theme-primary text-sm">Name</Label>
                <Input
                  value={param.name}
                  onChange={(e) => onUpdate(index, 'name', e.target.value)}
                  placeholder="parameter_name"
                  className="bg-theme-secondary border-theme text-theme-primary mt-1"
                />
              </div>

              <div>
                <Label className="text-theme-primary text-sm">Type</Label>
                <Select
                  value={param.type}
                  onValueChange={(value) => onUpdate(index, 'type', value)}
                >
                  <SelectTrigger className="bg-theme-secondary border-theme text-theme-primary mt-1">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {parameterTypes.map(type => (
                      <SelectItem key={type.value} value={type.value}>
                        {type.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex items-center space-x-2">
                <input
                  type="checkbox"
                  checked={param.required || false}
                  onChange={(e) => onUpdate(index, 'required', e.target.checked)}
                  className="rounded border-theme"
                />
                <span className="text-sm text-theme-primary">Required</span>
              </div>

              <div className="flex justify-end">
                <Button
                  onClick={() => onRemove(index)}
                  variant="outline"
                  size="sm"
                  className="text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-900/30"
                >
                  <Trash2 className="w-4 h-4" />
                </Button>
              </div>
            </div>

            <div className="mt-4">
              <Label className="text-theme-primary text-sm">Description</Label>
              <Input
                value={param.description || ''}
                onChange={(e) => onUpdate(index, 'description', e.target.value)}
                placeholder="Parameter description"
                className="bg-theme-secondary border-theme text-theme-primary mt-1"
              />
            </div>

            <div className="mt-4">
              <Label className="text-theme-primary text-sm">Default value</Label>
              <Input
                value={typeof param.defaultValue === 'boolean' ? String(param.defaultValue) : (param.defaultValue || '')}
                onChange={(e) => onUpdate(index, 'defaultValue', e.target.value)}
                placeholder="Default value"
                className="bg-theme-secondary border-theme text-theme-primary mt-1"
              />
            </div>
          </div>
        ))}
      </div>
    )}
  </div>
);

export default ParameterList;
