import { Code, Terminal, Database, Globe } from 'lucide-react';
import { LocalMcpTool } from '../../types';

export interface ToolTypeConfig {
  value: LocalMcpTool['toolType'];
  label: string;
  icon: typeof Code;
  description: string;
}

export const TOOL_TYPES: ToolTypeConfig[] = [
  { value: 'LOCAL_COMMAND', label: 'Shell Command', icon: Terminal, description: 'Execute system commands' },
  { value: 'LOCAL_PYTHON', label: 'Python Script', icon: Code, description: 'Execute Python scripts' },
  { value: 'LOCAL_NODEJS', label: 'Node.js Script', icon: Code, description: 'Execute JavaScript/Node.js scripts' },
  { value: 'LOCAL_DATABASE', label: 'Database', icon: Database, description: 'Database queries' },
  { value: 'LOCAL_API', label: 'External API', icon: Globe, description: 'HTTP API calls' }
];

export const getToolTypeIcon = (toolType: LocalMcpTool['toolType']) => {
  const type = TOOL_TYPES.find(t => t.value === toolType);
  return type ? type.icon : Code;
};

export const getToolTypeLabel = (toolType: LocalMcpTool['toolType']) => {
  const type = TOOL_TYPES.find(t => t.value === toolType);
  return type ? type.label : toolType;
};
