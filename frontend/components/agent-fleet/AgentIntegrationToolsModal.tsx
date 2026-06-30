'use client';

import React, { useMemo, useState, useCallback } from 'react';
import Image from 'next/image';
import { Check, Loader2, Wrench } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { Agent } from '@/lib/api/orchestrator/types';
import { useMcpApiTools } from '@/app/workflows/builder/hooks/useMcpData';
import { setAgentIntegrationTools } from '@/lib/agents/agentResourceMutations';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

function ToolIcon({ iconSlug }: { iconSlug?: string }) {
  const [error, setError] = useState(false);
  if (!iconSlug || error) return <Wrench className="h-4 w-4 flex-shrink-0 text-theme-secondary" />;
  return (
    <Image
      src={`/icons/services/${iconSlug}.svg`}
      alt={iconSlug}
      width={16}
      height={16}
      className="h-4 w-4 flex-shrink-0"
      onError={() => setError(true)}
    />
  );
}

interface AgentIntegrationToolsModalProps {
  agent: Agent;
  apiSlug: string;
  integrationName: string;
  iconSlug?: string;
  onClose: () => void;
  /** Called after a successful save so the fleet can refetch. */
  onSaved: () => void;
}

/**
 * "Manage integration tools" - toggle which tools of ONE integration an agent can
 * use. This is the only genuinely per-agent tool config (agents store tools as a flat
 * `apiSlug:toolSlug` list; no per-tool credential/param storage exists). Save replaces
 * only this integration's subset of `toolsConfig.tools`.
 */
export function AgentIntegrationToolsModal({
  agent,
  apiSlug,
  integrationName,
  iconSlug,
  onClose,
  onSaved,
}: AgentIntegrationToolsModalProps) {
  const t = useTranslations('fleetInspector');
  const tCommon = useTranslations('common');
  const { data: tools, isLoading } = useMcpApiTools(apiSlug);
  const [saving, setSaving] = useState(false);

  // Tool id the agent stores. Pre-select from the agent's current tools - match the
  // canonical `apiSlug:slug` form OR the legacy UUID toolId.
  const currentToolIds = useMemo(() => {
    const raw = (agent.toolsConfig as any)?.tools;
    return new Set(Array.isArray(raw) ? raw.map((x: any) => String(x)) : []);
  }, [agent.toolsConfig]);

  const idOf = useCallback((slug: string) => `${apiSlug}:${slug}`, [apiSlug]);

  const [selected, setSelected] = useState<Set<string>>(new Set());
  // Seed selection once the tool list resolves.
  const seededRef = React.useRef(false);
  React.useEffect(() => {
    if (seededRef.current || !tools) return;
    seededRef.current = true;
    const init = new Set<string>();
    for (const tool of tools) {
      const canonical = idOf(tool.slug);
      if (currentToolIds.has(canonical) || (tool.toolId && currentToolIds.has(String(tool.toolId)))) {
        init.add(canonical);
      }
    }
    setSelected(init);
  }, [tools, currentToolIds, idOf]);

  const toggle = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const allIds = useMemo(() => (tools ?? []).map(tool => idOf(tool.slug)), [tools, idOf]);
  const allSelected = allIds.length > 0 && allIds.every(id => selected.has(id));

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await setAgentIntegrationTools(agent.id, apiSlug, [...selected]);
      onSaved();
      onClose();
    } catch (err) {
      console.error('[AgentIntegrationToolsModal] save failed:', err);
    } finally {
      setSaving(false);
    }
  }, [agent.id, apiSlug, selected, onSaved, onClose]);

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 pr-6">
            <ToolIcon iconSlug={iconSlug} />
            <span>{t('manageToolsTitle', { integration: integrationName })}</span>
          </DialogTitle>
          <DialogDescription className="text-theme-secondary">
            {t('manageToolsSubtitle', { integration: integrationName, agent: agent.name })}
          </DialogDescription>
        </DialogHeader>

        {tools && tools.length > 0 && (
          <div className="flex items-center justify-end">
            <button
              type="button"
              onClick={() => setSelected(allSelected ? new Set() : new Set(allIds))}
              className="text-xs font-medium text-theme-secondary hover:text-theme-primary transition-colors"
            >
              {allSelected ? t('manageToolsClear') : t('manageToolsSelectAll')}
            </button>
          </div>
        )}

        <div className="max-h-[50vh] overflow-y-auto -mx-1 px-1 space-y-1">
          {isLoading && (
            <div className="flex items-center justify-center py-10">
              <Loader2 className="h-5 w-5 animate-spin text-theme-secondary" />
            </div>
          )}
          {!isLoading && (!tools || tools.length === 0) && (
            <p className="text-sm text-theme-muted italic py-8 text-center">{t('manageToolsEmpty')}</p>
          )}
          {!isLoading && tools?.map(tool => {
            const id = idOf(tool.slug);
            const checked = selected.has(id);
            return (
              <button
                key={id}
                type="button"
                onClick={() => toggle(id)}
                className="w-full flex items-start gap-2.5 rounded-lg px-2.5 py-2 text-left hover:bg-theme-secondary/60 transition-colors"
              >
                <span
                  className={`mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border transition-colors ${
                    checked
                      ? 'bg-theme-accent border-theme-accent text-white'
                      : 'border-theme bg-transparent'
                  }`}
                >
                  {checked && <Check className="h-3 w-3" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-theme-primary truncate">{tool.name}</span>
                  {tool.description && (
                    <span className="block text-xs text-theme-muted line-clamp-2">{tool.description}</span>
                  )}
                </span>
              </button>
            );
          })}
        </div>

        <DialogFooter className="mt-1 gap-2">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            {tCommon('cancel')}
          </Button>
          <Button onClick={handleSave} disabled={saving || isLoading}>
            {saving ? t('manageToolsSaving') : tCommon('save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
