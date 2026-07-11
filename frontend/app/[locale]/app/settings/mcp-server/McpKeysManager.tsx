'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { Check, Copy, Info, KeyRound, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog';
import LoadingSpinner from '@/components/LoadingSpinner';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { cn } from '@/lib/utils';
import {
  mcpServerService,
  type ApiKeyEntry,
  type CreatedApiKey,
  type McpScopeOption,
} from '@/lib/api/services/mcp-server.service';

interface McpKeysManagerProps {
  /** Scope vocabulary from the connection endpoint (name + description). */
  availableScopes: McpScopeOption[];
  /** Notify the parent (toast + tool-count refresh). */
  onToast: (type: 'success' | 'error', title: string, message?: string) => void;
}

type AccessMode = 'full' | 'scoped';

/**
 * Multiple named lc_live_ keys, each optionally scoped to a set of MCP tools.
 * Create shows the plaintext exactly once; revoke is immediate. Styled on the
 * app's clean system (soft cards, h-9 controls, neutral chips).
 */
export function McpKeysManager({ availableScopes, onToast }: McpKeysManagerProps) {
  const t = useTranslations('mcpServer');
  const locale = useLocale();

  const [keys, setKeys] = useState<ApiKeyEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState('');
  const [mode, setMode] = useState<AccessMode>('full');
  const [selectedScopes, setSelectedScopes] = useState<Set<string>>(new Set());
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [confirmingRevokeId, setConfirmingRevokeId] = useState<string | null>(null);

  // Keep onToast/t out of refresh's identity so the mount fetch runs once, not on
  // every parent re-render (each toast add/dismiss re-creates the inline onToast).
  const onToastRef = useRef(onToast);
  const tRef = useRef(t);
  useEffect(() => { onToastRef.current = onToast; tRef.current = t; });

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setKeys(await mcpServerService.listKeys());
    } catch {
      onToastRef.current('error', tRef.current('errors.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const resetDialog = () => {
    setName('');
    setMode('full');
    setSelectedScopes(new Set());
    setCreated(null);
    setCopied(false);
  };

  const openDialog = () => {
    resetDialog();
    setDialogOpen(true);
  };

  // Close AND wipe the one-time plaintext from state, so it never lingers in
  // memory (or React devtools) after the user is done with the dialog.
  const closeDialog = () => {
    setDialogOpen(false);
    resetDialog();
  };

  const toggleScope = (scope: string) => {
    setSelectedScopes((prev) => {
      const next = new Set(prev);
      if (next.has(scope)) next.delete(scope);
      else next.add(scope);
      return next;
    });
  };

  const canSubmit =
    name.trim().length > 0 && (mode === 'full' || selectedScopes.size > 0) && !creating;

  const handleCreate = async () => {
    if (!canSubmit) return;
    setCreating(true);
    try {
      const result = await mcpServerService.createKey({
        name: name.trim(),
        scopes: mode === 'full' ? null : Array.from(selectedScopes),
      });
      setCreated(result);
      onToast('success', t('toasts.keyGenerated'), t('apiKey.shownOnce'));
      await refresh();
    } catch {
      onToast('error', t('toasts.keyGenerationFailed'), t('toasts.keyGenerationFailedMessage'));
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async (id: string) => {
    setConfirmingRevokeId(null);
    setRevokingId(id);
    try {
      await mcpServerService.revokeKey(id);
      onToast('success', t('keys.revokedTitle'));
      await refresh();
    } catch {
      onToast('error', t('keys.revokeFailed'));
    } finally {
      setRevokingId(null);
    }
  };

  const copyPlaintext = async () => {
    if (!created) return;
    await navigator.clipboard.writeText(created.apiKey);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <section className="rounded-xl border border-theme bg-theme-secondary/50 p-5 space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-theme-primary flex items-center gap-2">
            <KeyRound className="h-3.5 w-3.5" />
            {t('keys.title')}
          </h2>
          <p className="mt-1 text-sm text-theme-secondary">{t('keys.description')}</p>
        </div>
        <Button size="sm" onClick={openDialog} className="flex-shrink-0">
          <Plus className="h-4 w-4 mr-1.5" />
          {t('keys.create')}
        </Button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-8">
          <LoadingSpinner size="sm" />
        </div>
      ) : keys.length === 0 ? (
        <div className="rounded-lg border border-dashed border-theme px-4 py-8 text-center">
          <p className="text-sm text-theme-muted">{t('keys.empty')}</p>
        </div>
      ) : (
        <ul className="divide-y divide-theme rounded-lg border border-theme overflow-hidden">
          {keys.map((key) => (
            <li key={key.id} className="flex items-center gap-3 px-4 py-3 bg-theme-primary/40">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-sm font-medium text-theme-primary truncate">{key.name}</span>
                  <code className="text-xs font-mono text-theme-muted truncate">{key.maskedApiKey}</code>
                </div>
                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                  {key.scopes === null ? (
                    <span className="inline-flex items-center rounded-md bg-theme-tertiary px-1.5 py-0.5 text-xs text-theme-secondary">
                      {t('keys.fullAccess')}
                    </span>
                  ) : (
                    key.scopes.map((scope) => (
                      <span
                        key={scope}
                        className="inline-flex items-center rounded-md border border-theme px-1.5 py-0.5 text-xs text-theme-secondary"
                      >
                        {scope}
                      </span>
                    ))
                  )}
                  <span className="text-xs text-theme-muted">
                    {t('keys.createdAt', { date: formatUtcDate(key.createdAt ?? '', { locale }) })}
                  </span>
                </div>
              </div>
              {confirmingRevokeId === key.id ? (
                <div className="flex flex-shrink-0 items-center gap-1.5">
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => void handleRevoke(key.id)}
                    disabled={revokingId === key.id}
                  >
                    {revokingId === key.id ? <LoadingSpinner size="xs" className="mr-1.5" /> : null}
                    {t('keys.revokeConfirm')}
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setConfirmingRevokeId(null)}>
                    {t('keys.cancel')}
                  </Button>
                </div>
              ) : (
                <Button
                  variant="ghost"
                  size="icon"
                  className="flex-shrink-0 text-theme-muted hover:text-red-500"
                  onClick={() => setConfirmingRevokeId(key.id)}
                  title={t('keys.revoke')}
                  aria-label={t('keys.revoke')}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      <Dialog open={dialogOpen} onOpenChange={(open) => { if (!creating && !open) closeDialog(); else if (open) setDialogOpen(true); }}>
        <DialogContent className="max-w-lg">
          {created ? (
            <>
              <DialogHeader>
                <DialogTitle>{t('keys.createdTitle')}</DialogTitle>
                <DialogDescription>{t('apiKey.shownOnce')}</DialogDescription>
              </DialogHeader>
              <div className="flex items-start gap-2 min-w-0">
                <code className="flex-1 min-w-0 break-all rounded-lg bg-theme-tertiary px-3 py-2 text-sm font-mono text-theme-primary">
                  {created.apiKey}
                </code>
                <Button variant="outline" size="sm" onClick={() => void copyPlaintext()} aria-label={t('copy')} className="flex-shrink-0">
                  {copied ? <Check className="h-3.5 w-3.5 text-green-600" /> : <Copy className="h-3.5 w-3.5" />}
                </Button>
              </div>
              <DialogFooter>
                <Button onClick={closeDialog}>{t('keys.done')}</Button>
              </DialogFooter>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle>{t('keys.createTitle')}</DialogTitle>
                <DialogDescription>{t('keys.createSubtitle')}</DialogDescription>
              </DialogHeader>

              <div className="space-y-4">
                <div className="space-y-1.5">
                  <label className="text-sm font-medium text-theme-primary">{t('keys.nameLabel')}</label>
                  <Input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder={t('keys.namePlaceholder')}
                    maxLength={100}
                    autoFocus
                  />
                </div>

                {/* Access: one clean toggle. Off = full access, on = limit to the
                    tools switched on below. */}
                <div className="flex items-center justify-between gap-3 rounded-lg border border-theme px-3 py-2.5">
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-theme-primary">{t('keys.limitLabel')}</div>
                    <div className="mt-0.5 text-xs text-theme-muted">
                      {mode === 'scoped' ? t('keys.scopedAccessHint') : t('keys.fullAccessHint')}
                    </div>
                  </div>
                  <Switch
                    checked={mode === 'scoped'}
                    onCheckedChange={(on) => setMode(on ? 'scoped' : 'full')}
                    aria-label={t('keys.limitLabel')}
                  />
                </div>

                {mode === 'scoped' && (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <label className="text-sm font-medium text-theme-primary">{t('keys.scopesLabel')}</label>
                      {selectedScopes.size === 0 && (
                        <span className="text-xs text-theme-muted">{t('keys.scopesRequired')}</span>
                      )}
                    </div>
                    <TooltipProvider delayDuration={200}>
                      <ul className="max-h-64 space-y-0.5 overflow-y-auto pr-1">
                        {availableScopes.map((scope) => {
                          const granted = selectedScopes.has(scope.name);
                          return (
                            <li key={scope.name} className="flex items-center gap-2 rounded-lg px-3 py-2 transition-colors hover:bg-theme-secondary">
                              <span className="text-sm font-medium text-theme-primary">{scope.name}</span>
                              {scope.description && (
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <button
                                      type="button"
                                      className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded text-theme-muted transition-colors hover:text-theme-primary"
                                      aria-label={scope.name}
                                    >
                                      <Info className="h-3.5 w-3.5" />
                                    </button>
                                  </TooltipTrigger>
                                  <TooltipContent side="top" className="max-w-xs text-xs">
                                    {scope.description}
                                  </TooltipContent>
                                </Tooltip>
                              )}
                              <div className="ml-auto flex-shrink-0">
                                <Switch
                                  checked={granted}
                                  onCheckedChange={() => toggleScope(scope.name)}
                                  aria-label={scope.name}
                                />
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    </TooltipProvider>
                  </div>
                )}
              </div>

              <DialogFooter>
                <Button variant="ghost" onClick={closeDialog} disabled={creating}>
                  {t('keys.cancel')}
                </Button>
                <Button onClick={() => void handleCreate()} disabled={!canSubmit}>
                  {creating ? <LoadingSpinner size="xs" className="mr-1.5" /> : <Plus className="h-4 w-4 mr-1.5" />}
                  {t('keys.create')}
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>
    </section>
  );
}
