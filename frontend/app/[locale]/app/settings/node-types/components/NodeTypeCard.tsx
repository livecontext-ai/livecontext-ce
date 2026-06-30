"use client";

import React, { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { useTranslations } from "next-intl";
import { Switch } from "@/components/ui/switch";
import type { NodeTypeSetting } from "@/lib/api/orchestrator/node-type-settings.service";
import { NodeIcon } from "@/app/workflows/builder/components/nodes/shared";
import { nodeTypeCategory, NODE_CATEGORY_LABEL_KEY } from "../categories";

interface NodeTypeCardProps {
  nodeType: NodeTypeSetting;
  onToggle: (type: string, enabled: boolean) => void;
  toggling?: boolean;
}

// Keyed by clean category bucket (see ../categories), not the raw backend value.
const CATEGORY_BADGE_STYLES: Record<string, string> = {
  trigger: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300",
  action: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  control_flow: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
  ai: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300",
  data: "bg-cyan-100 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300",
  interface: "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300",
  utility: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
};

/**
 * Map a backend node type + category to the exact nodeId / isMcp combo used by
 * the workflow builder's NODE_ICON_REGISTRY. Without this, types like `webhook`
 * (category=trigger) or `insert_row` don't match any registry key and fall
 * back to the generic `Code` icon.
 */
function resolveIconIdentity(type: string, category: string): { nodeId: string; isMcp: boolean } {
  // MCP generic tool - render the MCP logo via fallback.
  if (type === 'mcp') return { nodeId: 'mcp', isMcp: true };

  // Trigger category → suffix with "-trigger" to hit the registry entries
  // (webhook-trigger, manual-trigger, chat-trigger, schedule-trigger, form-trigger, error-trigger).
  if (category === 'trigger') {
    if (type === 'table') return { nodeId: 'tables-trigger', isMcp: false };
    if (type === 'workflow') return { nodeId: 'workflows-trigger', isMcp: false };
    return { nodeId: `${type}-trigger`, isMcp: false };
  }

  // CRUD table operations - backend names (snake_case) → registry keys (kebab-case).
  const crudMap: Record<string, string> = {
    insert_row: 'create-row',
    create_row: 'create-row',
    create_column: 'create-column',
    update_row: 'update-row',
    delete_row: 'delete-row',
    get_rows: 'read-row',
    read_row: 'read-row',
    find_rows: 'find-row',
  };
  if (crudMap[type]) return { nodeId: crudMap[type], isMcp: false };

  // Default: let the registry try its own normalization on the raw type.
  return { nodeId: type, isMcp: false };
}

export function NodeTypeCard({ nodeType, onToggle, toggling }: NodeTypeCardProps) {
  const t = useTranslations("nodeTypeSettings");
  const [expanded, setExpanded] = useState(false);

  const hasParams = nodeType.parameters && Object.keys(nodeType.parameters).length > 0;
  const { nodeId: resolvedNodeId, isMcp } = resolveIconIdentity(nodeType.type, nodeType.category);
  // The visible badge shows the clean category bucket (same as the filter), not
  // the messy raw backend value. Icon resolution above still uses the raw category.
  const categoryBucket = nodeTypeCategory(nodeType);

  return (
    <div className={`rounded-xl border p-4 transition-colors ${
      nodeType.enabled
        ? "border-theme hover:border-[var(--accent-primary)]/50"
        : "border-theme-tertiary opacity-60"
    }`}>
      <div className="flex items-start justify-between gap-3">
        {/* Centralized node icon - same component used by the workflow builder canvas */}
        <NodeIcon
          nodeId={resolvedNodeId}
          nodeFamily={nodeType.category}
          isMcp={isMcp}
          size="lg"
          alt={nodeType.label}
          className="mt-0.5"
        />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-sm font-medium text-theme-primary truncate">
              {nodeType.label}
            </h3>
            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
              CATEGORY_BADGE_STYLES[categoryBucket] || "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300"
            }`}>
              {t(NODE_CATEGORY_LABEL_KEY[categoryBucket])}
            </span>
            {nodeType.variablePrefix && (
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-mono bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400">
                {nodeType.variablePrefix}:
              </span>
            )}
          </div>
          <p className="text-sm text-theme-secondary mt-1 line-clamp-2">
            {nodeType.description}
          </p>
        </div>

        <div className="flex items-center gap-3 flex-shrink-0">
          <span className={`text-xs font-medium ${nodeType.enabled ? "text-green-600 dark:text-green-400" : "text-theme-tertiary"}`}>
            {nodeType.enabled ? t("enabled") : t("disabled")}
          </span>
          <Switch
            checked={nodeType.enabled}
            onCheckedChange={(checked) => onToggle(nodeType.type, checked)}
            disabled={toggling}
          />
        </div>
      </div>

      {hasParams && (
        <div className="mt-2">
          <button
            onClick={() => setExpanded(!expanded)}
            className="flex items-center gap-1 text-xs text-theme-secondary hover:text-theme-primary transition-colors"
          >
            {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
            {t("parameters")} ({Object.keys(nodeType.parameters!).length})
          </button>

          {expanded && nodeType.parameters && (
            <div className="mt-2 pl-2 border-l-2 border-theme-tertiary space-y-1">
              {Object.entries(nodeType.parameters).map(([key, value]) => {
                const param = value as Record<string, unknown>;
                return (
                  <div key={key} className="text-xs">
                    <span className="font-mono text-theme-primary">{key}</span>
                    {param.type && (
                      <span className="text-theme-tertiary ml-1">({String(param.type)})</span>
                    )}
                    {param.required && (
                      <span className="text-red-500 ml-1">*</span>
                    )}
                    {param.description && (
                      <span className="text-theme-secondary ml-1">- {String(param.description)}</span>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
