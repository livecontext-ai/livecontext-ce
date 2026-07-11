'use client';

import React, { useState, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Flag, ShieldCheck, X, Sparkles, Check, Loader2, RotateCcw, Upload } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import type {
    FlaggedImage,
    ScreeningDecisionEntry,
} from '@/lib/api/orchestrator/screening.service';
import { screeningService } from '@/lib/api/orchestrator/screening.service';
import { ImagePreview } from '@/components/data-table/ImagePreview';
import { fileService } from '@/lib/api/orchestrator/file.service';

/**
 * Build a renderable preview src for a flagged image so the publisher can
 * recognize it before deciding. Returns null when the value is a template
 * placeholder ({@code {{var}}}) rather than a real resource.
 *   - http(s) URL (scraped CDN / external) → used directly
 *   - already a proxy URL (/api/…, incl. the opaque /api/proxy/files/by-id/{id}/raw a
 *     post-cutover showcase carries) → used as-is (ImagePreview fetches it with the
 *     auth header and renders from a blob: URL - no token in the URL)
 *   - a bare storage key has no opaque id and can no longer be served → null (no preview)
 */
function previewSrcFor(url: string): string | null {
    if (!url) return null;
    if (/^https?:\/\//i.test(url)) return url;
    if (url.startsWith('/api/')) return url;
    return null;
}

const ASPECT_RATIOS = ['1:1', '16:9', '9:16', '4:3', '3:2', '21:9'] as const;
const STYLE_PRESETS = ['photographic', 'digital-art', 'cinematic', 'anime', 'comic-book', 'fantasy-art', 'neon-punk', 'origami'] as const;

interface ImageReplacementState {
    status: 'idle' | 'prompting' | 'generating' | 'uploading' | 'done' | 'error';
    /** How the replacement was produced - drives the audit decision (REPLACED_AI vs REPLACED_UPLOAD). */
    method?: 'ai' | 'upload';
    prompt: string;
    negativePrompt: string;
    aspectRatio: string;
    stylePreset: string;
    storageKey?: string;
    /** Renderable URL of the produced replacement (opaque /api/... id URL), for the thumbnail. */
    replacementUrl?: string;
    error?: string;
}

interface ImageScreeningModalProps {
    open: boolean;
    flagged: FlaggedImage[];
    attestationTextVersion: string;
    interfaceId: string;
    aiReplacementCostPerImage?: number;
    onCancel: () => void;
    onConfirm: (decisions: ScreeningDecisionEntry[]) => Promise<void>;
}

export function ImageScreeningModal({
    open,
    flagged,
    attestationTextVersion,
    interfaceId,
    aiReplacementCostPerImage,
    onCancel,
    onConfirm,
}: ImageScreeningModalProps) {
    const t = useTranslations('marketplace.screening');
    const [attested, setAttested] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const [replacements, setReplacements] = useState<Record<string, ImageReplacementState>>({});

    const getKey = (img: FlaggedImage) => img.url + ':' + img.source;

    const getState = (img: FlaggedImage): ImageReplacementState =>
        replacements[getKey(img)] || { status: 'idle', prompt: '', negativePrompt: '', aspectRatio: '1:1', stylePreset: 'photographic' };

    const updateState = useCallback((img: FlaggedImage, patch: Partial<ImageReplacementState>) => {
        setReplacements(prev => ({
            ...prev,
            [getKey(img)]: { ...prev[getKey(img)] || { status: 'idle', prompt: '', negativePrompt: '', aspectRatio: '1:1', stylePreset: 'photographic' }, ...patch },
        }));
    }, []);

    // Busy = an AI generation OR an upload in flight. Blocks publish until settled.
    const anyGenerating = Object.values(replacements).some(r => r.status === 'generating' || r.status === 'uploading');

    const handleGenerate = useCallback(async (img: FlaggedImage) => {
        const state = getState(img);
        if (!state.prompt.trim()) return;
        updateState(img, { status: 'generating', error: undefined });

        try {
            const result = await screeningService.generateAiReplacement({
                interfaceId,
                originalImageUrl: img.url,
                prompt: state.prompt.trim(),
                negativePrompt: state.negativePrompt.trim() || undefined,
                aspectRatio: state.aspectRatio,
                stylePreset: state.stylePreset,
            });
            updateState(img, { status: 'done', method: 'ai', storageKey: result.storageKey });
        } catch (err: any) {
            const message = err?.message || 'Generation failed';
            updateState(img, { status: 'error', error: message });
        }
    }, [interfaceId, replacements, updateState]);

    // Upload a replacement file (image/video/…). Works with NO AI provider -
    // the file is stored under the caller's tenant and its storageKey flows
    // through the same imageReplacements pipeline as an AI replacement.
    const handleUpload = useCallback(async (img: FlaggedImage, file: File) => {
        updateState(img, { status: 'uploading', error: undefined });
        try {
            const res = await fileService.uploadGeneric(file, 'screening-replace');
            updateState(img, { status: 'done', method: 'upload', storageKey: res.storageKey, replacementUrl: res.url });
        } catch (err: any) {
            updateState(img, { status: 'error', error: err?.message || 'Upload failed' });
        }
    }, [updateState]);

    if (!open) return null;

    const handlePublishAnyway = async () => {
        if (submitting || anyGenerating) return;
        setSubmitting(true);
        try {
            const decisions: ScreeningDecisionEntry[] = flagged.map((img) => {
                const state = getState(img);
                if (state.status === 'done' && state.storageKey) {
                    return {
                        url: img.url,
                        source: img.source,
                        decision: state.method === 'upload' ? 'REPLACED_UPLOAD' : 'REPLACED_AI',
                        replacementRef: state.storageKey,
                    };
                }
                if (attested) {
                    return {
                        url: img.url,
                        source: img.source,
                        decision: 'KEPT_ATTESTED' as const,
                        attestationText: t('attestationVerbatim'),
                    };
                }
                return {
                    url: img.url,
                    source: img.source,
                    decision: 'SKIPPED' as const,
                };
            });
            await onConfirm(decisions);
        } finally {
            setSubmitting(false);
        }
    };

    const replacedCount = Object.values(replacements).filter(r => r.status === 'done').length;
    const costLabel = aiReplacementCostPerImage != null ? `(${aiReplacementCostPerImage} credits)` : '';

    return createPortal(
        <div
            className="fixed inset-0 bg-black/30 backdrop-blur-sm z-[10000] flex items-center justify-center p-4"
            onClick={onCancel}
        >
            <div
                className="max-w-2xl w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] border border-theme animate-in fade-in-0 zoom-in-95 duration-200 max-h-[90vh] flex flex-col overflow-hidden"
                onClick={(e) => e.stopPropagation()}
            >
                {/* Header */}
                <div className="px-6 py-4 border-b border-theme flex items-start gap-3">
                    <div className="h-9 w-9 rounded-full bg-amber-100 dark:bg-amber-900/40 flex items-center justify-center shrink-0">
                        <AlertTriangle className="h-5 w-5 text-amber-600 dark:text-amber-400" />
                    </div>
                    <div className="flex-1 min-w-0">
                        <h2 className="text-base font-semibold text-theme-primary">{t('title', { count: flagged.length })}</h2>
                        <p className="text-xs text-theme-secondary mt-0.5">{t('subtitle')}</p>
                    </div>
                    <button type="button" aria-label={t('close')} onClick={onCancel} className="text-theme-secondary hover:text-theme-primary">
                        <X className="h-4 w-4" />
                    </button>
                </div>

                {/* Flagged list */}
                <div className="px-6 py-4 flex-1 overflow-y-auto space-y-2">
                    {flagged.map((img) => {
                        const state = getState(img);
                        const isReplaced = state.status === 'done';
                        const isPrompting = state.status === 'prompting' || state.status === 'error';
                        const isGenerating = state.status === 'generating';
                        const isUploading = state.status === 'uploading';
                        // When replaced, prefer a thumbnail of the produced replacement; else the original.
                        const previewSrc = isReplaced && state.replacementUrl
                            ? previewSrcFor(state.replacementUrl)
                            : previewSrcFor(img.url);

                        return (
                            <div key={getKey(img)} className="rounded-xl border border-theme bg-theme-secondary/40 overflow-hidden">
                                {/* Image row */}
                                <div className="flex items-start gap-2.5 p-2.5">
                                    {previewSrc ? (
                                        <div className={`h-9 w-9 shrink-0 rounded-md overflow-hidden border bg-theme-secondary/50 ${isReplaced ? 'border-green-500' : 'border-theme'}`}>
                                            <ImagePreview src={previewSrc} alt={img.url} />
                                        </div>
                                    ) : isReplaced ? (
                                        <div className="h-3.5 w-3.5 mt-0.5 shrink-0 rounded-full bg-green-500 flex items-center justify-center">
                                            <Check className="h-2.5 w-2.5 text-white" />
                                        </div>
                                    ) : (
                                        <Flag className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400 mt-0.5 shrink-0" />
                                    )}
                                    <div className="min-w-0 flex-1">
                                        <div className={`text-xs font-medium truncate ${isReplaced ? 'text-green-600 dark:text-green-400 line-through' : 'text-theme-primary'}`} title={img.url}>
                                            {isReplaced ? t('replaced') : img.url}
                                        </div>
                                        <div className="text-[11px] text-theme-secondary mt-0.5">
                                            {t('sourceHost', { source: img.source, host: img.host })}
                                        </div>
                                    </div>
                                    {/* Upload a replacement (image/video) - works with NO AI provider configured */}
                                    {!isReplaced && !isGenerating && !isUploading && (
                                        <label
                                            title={t('uploadHint')}
                                            className="shrink-0 inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] font-medium bg-theme-tertiary text-theme-primary hover:bg-theme-secondary transition-colors cursor-pointer"
                                        >
                                            <Upload className="h-3 w-3" />
                                            {t('upload')}
                                            <input
                                                type="file"
                                                accept="image/*,video/*"
                                                className="hidden"
                                                onChange={(e) => {
                                                    const f = e.target.files?.[0];
                                                    if (f) handleUpload(img, f);
                                                    e.target.value = '';
                                                }}
                                            />
                                        </label>
                                    )}
                                    {/* Replace with AI button */}
                                    {!isReplaced && !isGenerating && !isUploading && aiReplacementCostPerImage != null && (
                                        <button
                                            type="button"
                                            onClick={() => updateState(img, { status: isPrompting ? 'idle' : 'prompting' })}
                                            className="shrink-0 inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] font-medium bg-violet-100 dark:bg-violet-900/40 text-violet-700 dark:text-violet-300 hover:bg-violet-200 dark:hover:bg-violet-800/60 transition-colors"
                                        >
                                            <Sparkles className="h-3 w-3" />
                                            {isPrompting ? t('cancel') : t('replaceWithAi')}
                                        </button>
                                    )}
                                    {(isGenerating || isUploading) && (
                                        <span className="shrink-0 inline-flex items-center gap-1 px-2 py-1 text-[11px] text-theme-secondary">
                                            <Loader2 className="h-3 w-3 animate-spin" />
                                            {isUploading ? t('uploading') : t('generating')}
                                        </span>
                                    )}
                                </div>

                                {/* Inline AI replacement form */}
                                {isPrompting && (
                                    <div className="px-3 pb-3 pt-1 border-t border-theme/50 space-y-2">
                                        <textarea
                                            value={state.prompt}
                                            onChange={(e) => updateState(img, { prompt: e.target.value })}
                                            placeholder={t('promptPlaceholder')}
                                            maxLength={500}
                                            rows={2}
                                            className="w-full text-xs bg-theme-primary border border-theme rounded-lg px-2.5 py-1.5 resize-none focus:outline-none focus:ring-1 focus:ring-violet-500"
                                        />
                                        <div className="flex gap-2">
                                            <select
                                                value={state.aspectRatio}
                                                onChange={(e) => updateState(img, { aspectRatio: e.target.value })}
                                                className="text-[11px] bg-theme-primary border border-theme rounded-lg px-2 py-1"
                                            >
                                                {ASPECT_RATIOS.map(r => <option key={r} value={r}>{r}</option>)}
                                            </select>
                                            <select
                                                value={state.stylePreset}
                                                onChange={(e) => updateState(img, { stylePreset: e.target.value })}
                                                className="text-[11px] bg-theme-primary border border-theme rounded-lg px-2 py-1 flex-1"
                                            >
                                                {STYLE_PRESETS.map(s => <option key={s} value={s}>{s}</option>)}
                                            </select>
                                        </div>
                                        {state.error && (
                                            <p className="text-[11px] text-red-500">{state.error}</p>
                                        )}
                                        <Button
                                            size="sm"
                                            onClick={() => handleGenerate(img)}
                                            disabled={!state.prompt.trim() || isGenerating}
                                            className="w-full text-xs inline-flex items-center justify-center gap-1.5"
                                        >
                                            {state.status === 'error' ? (
                                                <><RotateCcw className="h-3 w-3" /> {t('retryGeneration')} {costLabel}</>
                                            ) : (
                                                <><Sparkles className="h-3 w-3" /> {t('generateButton', { cost: aiReplacementCostPerImage })} </>
                                            )}
                                        </Button>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>

                {/* Attestation + actions */}
                <div className="px-6 py-4 border-t border-theme space-y-3">
                    {replacedCount > 0 && (
                        <p className="text-xs text-green-600 dark:text-green-400 font-medium">
                            {t('replacedCount', { count: replacedCount })}
                        </p>
                    )}
                    <label className="flex items-start gap-2 cursor-pointer">
                        <input
                            type="checkbox"
                            checked={attested}
                            onChange={(e) => setAttested(e.target.checked)}
                            className="mt-0.5 h-4 w-4 cursor-pointer"
                        />
                        <span className="text-xs text-theme-primary leading-snug">
                            {t('attestationLabel')}
                        </span>
                    </label>
                    <p className="text-[10px] text-theme-secondary leading-relaxed">
                        {t('attestationFooter', { version: attestationTextVersion })}
                    </p>
                    <div className="flex gap-2 pt-1">
                        <Button variant="outline" onClick={onCancel} className="flex-1">
                            {t('cancel')}
                        </Button>
                        <Button
                            onClick={handlePublishAnyway}
                            disabled={submitting || anyGenerating}
                            className="flex-1 inline-flex items-center justify-center gap-1.5"
                        >
                            <ShieldCheck className="h-3.5 w-3.5" />
                            {submitting ? t('publishing') : (attested ? t('publishWithAttestation') : t('publishAnyway'))}
                        </Button>
                    </div>
                </div>
            </div>
        </div>,
        document.body,
    );
}
