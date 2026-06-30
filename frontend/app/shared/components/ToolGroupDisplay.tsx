'use client';

import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Check } from 'lucide-react';

interface Tool {
  id: string;
  name: string;
  description: string;
  category: string;
  icon: any;
  isSelected: boolean;
  isRemote?: boolean;
}

interface ToolGroup {
  serverName: string;
  serverType: 'mcp-local' | 'mcp-remote' | 'api-gateway';
  serverStatus: 'connected' | 'disconnected' | 'error';
  icon: any;
  tools: Tool[];
  description?: string;
  url?: string;
}

interface ToolGroupDisplayProps {
  groups: ToolGroup[];
  selectedTools: string[];
  onToolSelect: (toolId: string) => void;
  mode: 'intelligent' | 'manual';
}

export default function ToolGroupDisplay({ groups, selectedTools, onToolSelect, mode }: ToolGroupDisplayProps) {
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const toggleGroup = (serverName: string) => {
    const newExpanded = new Set(expandedGroups);
    if (newExpanded.has(serverName)) {
      newExpanded.delete(serverName);
    } else {
      newExpanded.add(serverName);
    }
    setExpandedGroups(newExpanded);
  };

  return (
    <div className="space-y-3">
      {groups.map((group) => {
        const isExpanded = expandedGroups.has(group.serverName);
        const selectedInGroup = group.tools.filter(tool => selectedTools.includes(tool.id)).length;
        
        return (
          <div key={group.serverName} className="border border-theme rounded-lg overflow-hidden bg-theme-tertiary">
            {/* Server Header */}
            <button
              onClick={() => toggleGroup(group.serverName)}
              className="w-full p-3 flex items-center justify-between hover:bg-theme-primary/5 transition-colors duration-200"
            >
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-2">
                  {isExpanded ? (
                    <ChevronDown className="w-4 h-4 text-theme-secondary" />
                  ) : (
                    <ChevronRight className="w-4 h-4 text-theme-secondary" />
                  )}
                  <group.icon className="w-5 h-5 text-theme-primary" />
                </div>
                
                <div className="flex-1 text-left">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-theme-primary">{group.serverName}</span>
                    <span className="text-xs px-2 py-1 rounded-full bg-theme-primary/10 text-theme-secondary border border-theme-primary/20">
                      {selectedInGroup}/{group.tools.length}
                    </span>
                  </div>
                  {group.description && (
                    <p className="text-xs text-theme-secondary mt-1">{group.description}</p>
                  )}
                </div>
              </div>
            </button>

            {/* Tools List */}
            {isExpanded && (
              <div className="border-t border-theme/20 bg-theme-secondary/30">
                <div className="p-3 space-y-2">
                  {group.tools.map((tool) => (
                    <button
                      key={tool.id}
                      onClick={() => onToolSelect(tool.id)}
                      disabled={mode === 'intelligent'}
                      className={`w-full p-2 rounded-lg border transition-all duration-200 text-left ${
                        selectedTools.includes(tool.id) || (mode === 'intelligent' && !tool.isRemote)
                          ? 'border-theme-primary bg-theme-primary/10'
                          : 'border-theme bg-theme-tertiary hover:bg-theme-primary/5 hover:border-theme-primary/30'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                          selectedTools.includes(tool.id) || (mode === 'intelligent' && !tool.isRemote)
                            ? 'bg-theme-primary text-theme-secondary'
                            : 'bg-theme-primary/10 text-theme-primary'
                        }`}>
                          <tool.icon className="w-4 h-4" />
                        </div>
                        
                        <div className="flex-1">
                          <h5 className="font-medium text-sm text-theme-primary">{tool.name}</h5>
                          <p className="text-xs text-theme-secondary mt-1">{tool.description}</p>
                          <span className="text-xs px-2 py-1 rounded-full mt-1 inline-block text-theme-secondary bg-theme-primary/10 border border-theme-primary/20">
                            {tool.category}
                          </span>
                        </div>
                        
                        {(selectedTools.includes(tool.id) || (mode === 'intelligent' && !tool.isRemote)) && (
                          <Check className="w-5 h-5 text-theme-primary" />
                        )}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

