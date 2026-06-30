"use client";

import React, { useState, useEffect, useCallback } from "react";
import {
  X, CheckCircle2, XCircle, Bot, Workflow, Users, Coins, Globe,
  ChevronDown, ChevronRight, ArrowRight, ArrowLeft,
  FileText, GitCompare, ShieldCheck, Check, AlertTriangle,
  Table as TableIcon, Monitor, Sparkles,
} from "lucide-react";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { publicationService } from "@/lib/api/orchestrator/publication.service";
import { actionErrorMessage } from "./comparisonErrors";
import type { WorkflowPublication, PublicationComparisonData } from "@/lib/api/orchestrator/types";
import { useTranslations } from "next-intl";
import LoadingSpinner from '@/components/LoadingSpinner';
import { InterfacePreview } from "@/components/marketplace/InterfacePreview";

type PubType = 'AGENT' | 'WORKFLOW' | 'TABLE' | 'INTERFACE' | 'SKILL';

function getTypeIcon(type: PubType): React.ComponentType<{ className?: string }> {
  switch (type) {
    case 'AGENT': return Bot;
    case 'WORKFLOW': return Workflow;
    case 'TABLE': return TableIcon;
    case 'INTERFACE': return Monitor;
    case 'SKILL': return Sparkles;
  }
}

function getTypeLabel(type: PubType): string {
  switch (type) {
    case 'AGENT': return 'Agent';
    case 'WORKFLOW': return 'Workflow';
    case 'TABLE': return 'Table';
    case 'INTERFACE': return 'Interface';
    case 'SKILL': return 'Skill';
  }
}

interface PublicationComparisonViewProps {
  publication: WorkflowPublication;
  onClose: () => void;
  onApproved: () => void;
  onRejected: () => void;
}

// ─── Step Indicator (same pattern as ShareWorkflowModal) ───

function StepIndicator({ currentStep, onStepClick }: { currentStep: number; onStepClick: (s: number) => void }) {
  const steps = [
    { number: 1, icon: FileText, label: "Overview" },
    { number: 2, icon: GitCompare, label: "Comparison" },
    { number: 3, icon: ShieldCheck, label: "Decision" },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              onClick={() => (isCompleted || isActive) && onStepClick(step.number)}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full transition-all ${
                isActive
                  ? "bg-[var(--accent-primary)] text-[var(--accent-foreground)]"
                  : isCompleted
                  ? "bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-pointer hover:bg-emerald-500/30"
                  : "bg-theme-tertiary text-theme-secondary cursor-not-allowed"
              }`}
            >
              {isCompleted ? <Check className="h-4 w-4" /> : <Icon className="h-4 w-4" />}
              <span className="text-sm font-medium hidden sm:inline">{step.label}</span>
            </button>
            {index < steps.length - 1 && (
              <div className={`w-8 h-0.5 rounded-full ${step.number < currentStep ? "bg-emerald-500" : "bg-theme-tertiary"}`} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
}

// ─── Collapsible Section ───

function CollapsibleSection({ title, defaultOpen = false, children, count, badge }: {
  title: string; defaultOpen?: boolean; children: React.ReactNode; count?: number; badge?: string;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-xl border border-theme overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-theme-secondary hover:bg-theme-tertiary transition-colors text-sm font-medium text-theme-primary"
      >
        <span className="flex items-center gap-2">
          {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          {title}
          {count !== undefined && (
            <span className="text-xs bg-theme-tertiary text-theme-secondary px-1.5 py-0.5 rounded">{count}</span>
          )}
          {badge && (
            <span className="text-xs bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 px-1.5 py-0.5 rounded">{badge}</span>
          )}
        </span>
      </button>
      {open && <div className="p-4 border-t border-theme">{children}</div>}
    </div>
  );
}

// ─── JSON Block ───

function JsonBlock({ data, maxHeight = "300px" }: { data: unknown; maxHeight?: string }) {
  if (!data) return <span className="text-xs text-theme-tertiary italic">No data</span>;
  return (
    <pre
      className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 overflow-auto whitespace-pre-wrap break-all"
      style={{ maxHeight }}
    >
      {JSON.stringify(data, null, 2)}
    </pre>
  );
}

// ─── Deep equality (key-order agnostic) ───

/** Treat null, undefined, [], {} as "empty" */
function isEmpty(v: unknown): boolean {
  if (v == null) return true;
  if (Array.isArray(v) && v.length === 0) return true;
  if (typeof v === "object" && Object.keys(v as object).length === 0) return true;
  return false;
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  // Both empty → equal (null vs [] vs undefined vs {})
  if (isEmpty(a) && isEmpty(b)) return true;
  if (a == null || b == null) return false;
  if (typeof a !== typeof b) return false;
  if (typeof a !== "object") return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) {
    const arrB = b as unknown[];
    if (a.length !== arrB.length) return false;
    return a.every((v, i) => deepEqual(v, arrB[i]));
  }
  const objA = a as Record<string, unknown>;
  const objB = b as Record<string, unknown>;
  const keysA = Object.keys(objA);
  const keysB = Object.keys(objB);
  if (keysA.length !== keysB.length) return false;
  return keysA.every(k => k in objB && deepEqual(objA[k], objB[k]));
}

/** Returns "Differs" badge string if a and b are not deeply equal, undefined otherwise */
function diffBadgeStr(a: unknown, b: unknown): string | undefined {
  return deepEqual(a, b) ? undefined : "Differs";
}

// ─── Side-by-side JSON diff ───

/**
 * Align both objects so common keys come first (in a stable order),
 * then left-only extras, then right-only extras.
 * This ensures lines match up in the side-by-side diff.
 */
function alignBoth(a: unknown, b: unknown): [unknown, unknown] {
  if (a == null && b == null) return [a, b];
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return [a, b];
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b)) return [a, b];
    const maxLen = Math.max(a.length, b.length);
    const alignedA: unknown[] = [];
    const alignedB: unknown[] = [];
    for (let i = 0; i < maxLen; i++) {
      const [aa, bb] = alignBoth(a[i], b[i]);
      alignedA.push(aa);
      alignedB.push(bb);
    }
    return [alignedA, alignedB];
  }

  const objA = a as Record<string, unknown>;
  const objB = b as Record<string, unknown>;
  const keysA = Object.keys(objA);
  const keysB = Object.keys(objB);

  // Build unified key order: common keys first, then left-only, then right-only
  const commonKeys = keysA.filter(k => k in objB);
  const leftOnly = keysA.filter(k => !(k in objB));
  const rightOnly = keysB.filter(k => !(k in objA));

  const newA: Record<string, unknown> = {};
  const newB: Record<string, unknown> = {};

  // Common keys - recurse to align children
  for (const k of commonKeys) {
    const [aa, bb] = alignBoth(objA[k], objB[k]);
    newA[k] = aa;
    newB[k] = bb;
  }
  // Left-only keys (exist in snapshot but not in source)
  for (const k of leftOnly) {
    newA[k] = objA[k];
  }
  // Right-only keys (exist in source but not in snapshot)
  for (const k of rightOnly) {
    newB[k] = objB[k];
  }

  return [newA, newB];
}

function DiffJsonView({ left, right, leftLabel = "Snapshot", rightLabel = "Source", maxHeight = "500px" }: {
  left: unknown; right: unknown; leftLabel?: string; rightLabel?: string; maxHeight?: string;
}) {
  // Align both sides so common keys line up, extras at the end of each side
  const [alignedLeft, alignedRight] = alignBoth(left, right);
  const leftLines = JSON.stringify(alignedLeft, null, 2)?.split("\n") || [];
  const rightLines = JSON.stringify(alignedRight, null, 2)?.split("\n") || [];
  const maxLen = Math.max(leftLines.length, rightLines.length);

  const diffLines = new Set<number>();
  for (let i = 0; i < maxLen; i++) {
    if ((leftLines[i] ?? "") !== (rightLines[i] ?? "")) {
      diffLines.add(i);
    }
  }

  const totalDiffs = diffLines.size;
  const identical = totalDiffs === 0;

  const renderLine = (lines: string[], i: number) => {
    const isDiff = diffLines.has(i);
    const isMissing = i >= lines.length;
    const line = lines[i] ?? "";
    const bgClass = isMissing ? "bg-red-50/50 dark:bg-red-950/20" :
                    isDiff ? "bg-amber-50 dark:bg-amber-950/20" : "";
    const textClass = isMissing ? "text-red-400 italic" :
                      isDiff ? "text-amber-700 dark:text-amber-300" : "text-theme-secondary";
    return (
      <div key={i} className={`flex min-h-[20px] ${bgClass}`}>
        <span className="w-9 shrink-0 text-right pr-2 text-[10px] text-theme-tertiary select-none border-r border-theme/30 bg-theme-secondary/50 py-px leading-[18px]">
          {i + 1}
        </span>
        <span className={`px-2 py-px whitespace-nowrap text-xs leading-[18px] ${textClass}`}>
          {isMissing ? "" : line}
        </span>
      </div>
    );
  };

  return (
    <div className="space-y-2">
      {/* Summary */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-xs font-medium text-theme-secondary">{leftLabel} ({leftLines.length} lines)</span>
          <span className="text-xs text-theme-tertiary">vs</span>
          <span className="text-xs font-medium text-theme-secondary">{rightLabel} ({rightLines.length} lines)</span>
        </div>
        {identical ? (
          <span className="text-xs text-emerald-600 dark:text-emerald-400 flex items-center gap-1 font-medium">
            <Check className="h-3 w-3" /> Identical
          </span>
        ) : (
          <span className="text-xs text-amber-600 dark:text-amber-400 flex items-center gap-1 font-medium">
            <AlertTriangle className="h-3 w-3" /> {totalDiffs} line{totalDiffs > 1 ? "s" : ""} differ
          </span>
        )}
      </div>

      {/* Column headers (sticky outside scroll) */}
      <div className="rounded-xl border border-theme overflow-hidden flex flex-col" style={{ maxHeight }}>
        <div className="grid grid-cols-2 shrink-0 border-b border-theme bg-theme-secondary">
          <div className="px-3 py-1.5 border-r border-theme">
            <span className="text-xs font-semibold text-theme-secondary uppercase">{leftLabel}</span>
          </div>
          <div className="px-3 py-1.5">
            <span className="text-xs font-semibold text-theme-secondary uppercase">{rightLabel}</span>
          </div>
        </div>

        {/* Single vertical scroll, each column scrolls horizontally independently */}
        <div className="overflow-y-auto flex-1">
          <div className="font-mono">
            {Array.from({ length: maxLen }, (_, i) => (
              <div key={i} className="grid grid-cols-2">
                <div className="border-r border-theme overflow-x-auto">{renderLine(leftLines, i)}</div>
                <div className="overflow-x-auto">{renderLine(rightLines, i)}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Diff badge ───

function DiffBadge({ left, right }: { left: unknown; right: unknown }) {
  const match = deepEqual(left, right);
  return match ? (
    <span className="text-xs text-emerald-600 dark:text-emerald-400 flex items-center gap-1">
      <Check className="h-3 w-3" /> Match
    </span>
  ) : (
    <span className="text-xs text-amber-600 dark:text-amber-400 flex items-center gap-1">
      <AlertTriangle className="h-3 w-3" /> Differs
    </span>
  );
}

// ─── Main Component ───

export default function PublicationComparisonView({
  publication,
  onClose,
  onApproved,
  onRejected,
}: PublicationComparisonViewProps) {
  const t = useTranslations("publicationReview");
  const tc = useTranslations("publicationReview.comparison");

  const [currentStep, setCurrentStep] = useState(1);
  const [comparison, setComparison] = useState<PublicationComparisonData | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<"approve" | "reject" | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Distinct from `error` (which carries approve/reject failures shown on the decision
  // step): a failed comparison load must never look like a blocking failure - the admin
  // can still decide on the snapshot - so it surfaces as an informational note in step 2.
  const [comparisonError, setComparisonError] = useState<string | null>(null);

  const pubType: PubType = (publication.publicationType as PubType) || 'WORKFLOW';
  const isAgent = pubType === 'AGENT';
  const TypeIcon = getTypeIcon(pubType);
  const typeLabel = getTypeLabel(pubType);

  const fetchComparison = useCallback(async () => {
    try {
      setLoading(true);
      setComparisonError(null);
      const data = await publicationService.getComparisonData(publication.id);
      setComparison(data);
    } catch {
      setComparisonError(t("comparisonLoadFailed"));
    } finally {
      setLoading(false);
    }
  }, [publication.id, t]);

  useEffect(() => {
    fetchComparison();
  }, [fetchComparison]);

  const handleApprove = async () => {
    setActionLoading("approve");
    setError(null);
    try {
      await publicationService.approvePublication(publication.id);
      onApproved();
    } catch (e) {
      setError(actionErrorMessage(e, t("approveFailed")));
    } finally {
      setActionLoading(null);
    }
  };

  const handleReject = async () => {
    setActionLoading("reject");
    setError(null);
    try {
      await publicationService.rejectPublication(publication.id, rejectReason || undefined);
      onRejected();
    } catch (e) {
      setError(actionErrorMessage(e, t("rejectFailed")));
    } finally {
      setActionLoading(null);
    }
  };

  // ─── Landing preview extraction ───
  // Landing interface lives at different paths depending on publication type.
  // AGENT → snapshot.agent.landingInterface (when present)
  // TABLE/SKILL → snapshot.landingInterface
  // INTERFACE → the snapshot itself IS the interface (html/css/js at top level)
  // WORKFLOW → snapshot.landingInterface if workflow publish specified one
  const extractLanding = (snap: any): { htmlTemplate?: string; cssTemplate?: string; jsTemplate?: string } | null => {
    if (!snap) return null;
    if (pubType === 'INTERFACE') {
      return {
        htmlTemplate: snap.htmlTemplate,
        cssTemplate: snap.cssTemplate,
        jsTemplate: snap.jsTemplate,
      };
    }
    const landing = snap?.landingInterface ?? snap?.agent?.landingInterface;
    return landing ?? null;
  };
  const snapshotLanding = extractLanding(comparison?.snapshot);
  const sourceLanding = extractLanding(comparison?.currentSource);

  // ─── Step 1: Overview ───
  const renderStep1 = () => (
    <div className="space-y-4">
      <div className="rounded-xl border border-theme p-5">
        <div className="flex items-start gap-4">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
            <TypeIcon className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="flex-1 min-w-0 space-y-3">
            <div>
              <h3 className="text-sm font-semibold text-theme-primary">{publication.title}</h3>
              {publication.description && (
                <p className="text-xs text-theme-secondary mt-1">{publication.description}</p>
              )}
            </div>

            <div className="grid grid-cols-2 gap-3">
              <InfoItem icon={TypeIcon} label={t("type")} value={typeLabel} />
              <InfoItem icon={Globe} label={t("visibility")} value={publication.visibility} />
              <InfoItem icon={Users} label={t("publisher")} value={publication.publisherName || publication.publisherEmail || "-"} />
              <InfoItem icon={Coins} label={t("creditsPerUse")} value={String(publication.creditsPerUse ?? 0)} />
            </div>

            {/* Resource counts */}
            <div className="flex flex-wrap gap-2">
              {[
                { label: t("agents"), count: publication.agentCount },
                { label: "Skills", count: publication.skillCount },
                { label: t("workflows"), count: publication.workflowCount },
                { label: "Interfaces", count: publication.interfaceCount },
                { label: t("datasources"), count: publication.datasourceCount },
              ]
                .filter(r => (r.count ?? 0) > 0)
                .map(r => (
                  <span key={r.label} className="text-xs bg-theme-secondary text-theme-secondary rounded-full px-2.5 py-1">
                    {r.count} {r.label}
                  </span>
                ))}
            </div>
          </div>
        </div>
      </div>

      {/* Landing interface preview - snapshot vs current source */}
      {(snapshotLanding || sourceLanding) && (
        <div className="rounded-xl border border-theme p-4">
          <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-3">Landing Interface Preview</h4>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-xs text-theme-tertiary mb-1">Snapshot (at publish time)</p>
              <div className="rounded-lg border border-theme overflow-hidden" style={{ aspectRatio: '16 / 10' }}>
                <InterfacePreview snapshot={snapshotLanding} className="h-full w-full" emptyLabel="No landing in snapshot" />
              </div>
            </div>
            <div>
              <p className="text-xs text-theme-tertiary mb-1">Source (current live)</p>
              <div className="rounded-lg border border-theme overflow-hidden" style={{ aspectRatio: '16 / 10' }}>
                <InterfacePreview snapshot={sourceLanding} className="h-full w-full" emptyLabel="No landing in source" />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );

  // ─── Step 2: Source vs Snapshot Comparison ───
  const renderStep2 = () => {
    if (loading) {
      return (
        <div className="flex items-center justify-center py-12">
          <LoadingSpinner size="sm" />
        </div>
      );
    }

    if (!comparison) {
      return (
        <div className="py-8">
          {comparisonError ? (
            <div className="rounded-lg bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-800 p-4 flex items-start gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
              <p className="text-sm text-amber-800 dark:text-amber-300">{comparisonError}</p>
            </div>
          ) : (
            <p className="text-sm text-theme-secondary text-center">{tc("noData")}</p>
          )}
        </div>
      );
    }

    const snapshot = comparison.snapshot;
    const source = comparison.currentSource;

    switch (pubType) {
      case 'AGENT': return renderAgentComparison(snapshot, source);
      case 'WORKFLOW': return renderWorkflowComparison(snapshot, source);
      case 'TABLE': return renderTableComparison(snapshot, source);
      case 'INTERFACE': return renderInterfaceComparison(snapshot, source);
      case 'SKILL': return renderSkillComparison(snapshot, source);
      default: return renderWorkflowComparison(snapshot, source);
    }
  };

  // ─── TABLE / INTERFACE / SKILL renderers ───

  const renderTableComparison = (snapshot: any, source: any) => {
    const sourceError = source?.error;
    const s = snapshot || {};
    const src = source || {};
    const sItems: unknown[] = Array.isArray(s.items) ? s.items : [];
    const srcItems: unknown[] = Array.isArray(src.items) ? src.items : [];

    return (
      <div className="space-y-4">
        {sourceError && <SourceUnavailableNote detail={sourceError} t={t} />}

        <CollapsibleSection title="Table metadata" defaultOpen badge={diffBadgeStr(
          { name: s.name, description: s.description, sourceType: s.sourceType },
          { name: src.name, description: src.description, sourceType: src.sourceType },
        )}>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
              <div className="space-y-2">
                <CompField label="Name" value={s.name} />
                <CompField label="Description" value={s.description} />
                <CompField label="Source type" value={s.sourceType} />
                <CompField label="Rows" value={sItems.length} />
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source</h4>
              <div className="space-y-2">
                <CompField label="Name" value={src.name} />
                <CompField label="Description" value={src.description} />
                <CompField label="Source type" value={src.sourceType} />
                <CompField label="Rows" value={srcItems.length} />
              </div>
            </div>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="Columns & mapping" badge={diffBadgeStr(
          { columnOrder: s.columnOrder, mappingSpec: s.mappingSpec },
          { columnOrder: src.columnOrder, mappingSpec: src.mappingSpec },
        )}>
          <DiffJsonView
            left={{ columnOrder: s.columnOrder, mappingSpec: s.mappingSpec, sourceConfig: s.sourceConfig }}
            right={{ columnOrder: src.columnOrder, mappingSpec: src.mappingSpec, sourceConfig: src.sourceConfig }}
            leftLabel="Snapshot" rightLabel="Source" maxHeight="400px"
          />
        </CollapsibleSection>

        <CollapsibleSection title="Rows" badge={sItems.length !== srcItems.length ? `${sItems.length} vs ${srcItems.length}` : undefined} count={sItems.length}>
          <DiffJsonView left={sItems} right={srcItems} leftLabel="Snapshot" rightLabel="Source" maxHeight="400px" />
        </CollapsibleSection>

        {(s.landingInterface || src.landingInterface) && (
          <CollapsibleSection title="Landing interface" badge={diffBadgeStr(s.landingInterface, src.landingInterface)}>
            <DiffJsonView left={s.landingInterface} right={src.landingInterface} leftLabel="Snapshot" rightLabel="Source" maxHeight="400px" />
          </CollapsibleSection>
        )}
      </div>
    );
  };

  const renderInterfaceComparison = (snapshot: any, source: any) => {
    const sourceError = source?.error;
    const s = snapshot || {};
    const src = source || {};

    return (
      <div className="space-y-4">
        {sourceError && <SourceUnavailableNote detail={sourceError} t={t} />}

        <CollapsibleSection title="Interface metadata" defaultOpen badge={diffBadgeStr(
          { name: s.name, description: s.description, interfaceType: s.interfaceType },
          { name: src.name, description: src.description, interfaceType: src.interfaceType },
        )}>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
              <div className="space-y-2">
                <CompField label="Name" value={s.name} />
                <CompField label="Description" value={s.description} />
                <CompField label="Interface type" value={s.interfaceType} />
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source</h4>
              <div className="space-y-2">
                <CompField label="Name" value={src.name} />
                <CompField label="Description" value={src.description} />
                <CompField label="Interface type" value={src.interfaceType} />
              </div>
            </div>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="HTML template" defaultOpen badge={s.htmlTemplate !== src.htmlTemplate ? "Differs" : undefined}>
          <div className="grid grid-cols-2 gap-4">
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {s.htmlTemplate || "-"}
            </pre>
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {src.htmlTemplate || "-"}
            </pre>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="CSS template" badge={s.cssTemplate !== src.cssTemplate ? "Differs" : undefined}>
          <div className="grid grid-cols-2 gap-4">
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {s.cssTemplate || "-"}
            </pre>
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {src.cssTemplate || "-"}
            </pre>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="JS template" badge={s.jsTemplate !== src.jsTemplate ? "Differs" : undefined}>
          <div className="grid grid-cols-2 gap-4">
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {s.jsTemplate || "-"}
            </pre>
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
              {src.jsTemplate || "-"}
            </pre>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="Full interface JSON diff" badge={diffBadgeStr(s, src)}>
          <DiffJsonView left={s} right={src} leftLabel="Snapshot" rightLabel="Source" maxHeight="400px" />
        </CollapsibleSection>
      </div>
    );
  };

  const renderSkillComparison = (snapshot: any, source: any) => {
    const sourceError = source?.error;
    const s = snapshot || {};
    const src = source || {};

    return (
      <div className="space-y-4">
        {sourceError && <SourceUnavailableNote detail={sourceError} t={t} />}

        <CollapsibleSection title="Skill config" defaultOpen badge={diffBadgeStr(
          { name: s.name, description: s.description, icon: s.icon },
          { name: src.name, description: src.description, icon: src.icon },
        )}>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
              <div className="space-y-2">
                <CompField label="Name" value={s.name} />
                <CompField label="Description" value={s.description} />
                <CompField label="Icon" value={s.icon} />
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source</h4>
              <div className="space-y-2">
                <CompField label="Name" value={src.name} />
                <CompField label="Description" value={src.description} />
                <CompField label="Icon" value={src.icon} />
              </div>
            </div>
          </div>
        </CollapsibleSection>

        <CollapsibleSection title="Instructions" defaultOpen badge={s.instructions !== src.instructions ? "Differs" : undefined}>
          <div className="grid grid-cols-2 gap-4">
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-64 overflow-auto whitespace-pre-wrap">
              {s.instructions || "-"}
            </pre>
            <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-64 overflow-auto whitespace-pre-wrap">
              {src.instructions || "-"}
            </pre>
          </div>
        </CollapsibleSection>

        {(s.landingInterface || src.landingInterface) && (
          <CollapsibleSection title="Landing interface" badge={diffBadgeStr(s.landingInterface, src.landingInterface)}>
            <DiffJsonView left={s.landingInterface} right={src.landingInterface} leftLabel="Snapshot" rightLabel="Source" maxHeight="400px" />
          </CollapsibleSection>
        )}
      </div>
    );
  };

  const renderAgentComparison = (snapshot: any, source: any) => {
    const snapshotAgent = snapshot?.agent || {};
    const sourceAgent = source?.agent || {};
    const sourceError = source?.error;

    return (
      <div className="space-y-4">
        {sourceError && <SourceUnavailableNote detail={sourceError} t={t} />}

        {/* Agent config comparison */}
        <CollapsibleSection title={tc("agentConfig")} defaultOpen>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
              <div className="space-y-2">
                <CompField label="Name" value={snapshotAgent.name} />
                <CompField label={tc("model")} value={`${snapshotAgent.modelProvider || "-"} / ${snapshotAgent.modelName || "-"}`} />
                <CompField label="Temperature" value={snapshotAgent.temperature} />
                <CompField label="Max Tokens" value={snapshotAgent.maxTokens} />
                <CompField label="Max Iterations" value={snapshotAgent.maxIterations} />
              </div>
            </div>
            <div>
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-xs font-semibold text-theme-secondary uppercase">{tc("snapshotData").replace("Snapshot", "Source")}</h4>
                {!sourceError && <DiffBadge left={snapshotAgent.name} right={sourceAgent.name} />}
              </div>
              <div className="space-y-2">
                <CompField label="Name" value={sourceAgent.name} />
                <CompField label={tc("model")} value={`${sourceAgent.modelProvider || "-"} / ${sourceAgent.modelName || "-"}`} />
                <CompField label="Temperature" value={sourceAgent.temperature} />
                <CompField label="Max Tokens" value={sourceAgent.maxTokens} />
                <CompField label="Max Iterations" value={sourceAgent.maxIterations} />
              </div>
            </div>
          </div>
        </CollapsibleSection>

        {/* System prompt comparison */}
        <CollapsibleSection title={tc("systemPrompt")} badge={
          snapshotAgent.systemPrompt !== sourceAgent?.systemPrompt ? "Differs" : undefined
        }>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
              <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
                {snapshotAgent.systemPrompt || "-"}
              </pre>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source</h4>
              <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-lg p-3 max-h-48 overflow-auto whitespace-pre-wrap">
                {sourceAgent?.systemPrompt || "-"}
              </pre>
            </div>
          </div>
        </CollapsibleSection>

        {/* Agent config + skills JSON diff (skills are inside agent) */}
        <CollapsibleSection title="Agent JSON Diff" defaultOpen badge={diffBadgeStr(snapshot?.agent, source?.agent)}>
          <DiffJsonView left={snapshot?.agent} right={source?.agent} leftLabel="Snapshot" rightLabel="Source" maxHeight="400px" />
        </CollapsibleSection>

        {/* Per-workflow diffs */}
        {snapshot?.workflows && Object.keys(snapshot.workflows).map((wfId: string) => (
          <CollapsibleSection key={wfId} title={`Workflow: ${(snapshot.workflows[wfId] as any)?.name || wfId}`} badge={
            diffBadgeStr(snapshot.workflows[wfId], source?.workflows?.[wfId])
          }>
            <DiffJsonView
              left={snapshot.workflows[wfId]}
              right={source?.workflows?.[wfId] || { error: "Not in source" }}
              leftLabel="Snapshot" rightLabel="Source" maxHeight="400px"
            />
          </CollapsibleSection>
        ))}

        {/* Per-interface diffs */}
        {snapshot?.interfaces && Object.keys(snapshot.interfaces).map((ifId: string) => (
          <CollapsibleSection key={ifId} title={`Interface: ${(snapshot.interfaces[ifId] as any)?.name || ifId}`} badge={
            diffBadgeStr(snapshot.interfaces[ifId], source?.interfaces?.[ifId])
          }>
            <DiffJsonView
              left={snapshot.interfaces[ifId]}
              right={source?.interfaces?.[ifId] || { error: "Not in source" }}
              leftLabel="Snapshot" rightLabel="Source" maxHeight="400px"
            />
          </CollapsibleSection>
        ))}

        {/* Per-datasource diffs */}
        {snapshot?.datasources && Object.keys(snapshot.datasources).map((dsId: string) => (
          <CollapsibleSection key={dsId} title={`Datasource: ${(snapshot.datasources[dsId] as any)?.name || dsId}`} badge={
            diffBadgeStr(snapshot.datasources[dsId], source?.datasources?.[dsId])
          }>
            <DiffJsonView
              left={snapshot.datasources[dsId]}
              right={source?.datasources?.[dsId] || { error: "Not in source" }}
              leftLabel="Snapshot" rightLabel="Source" maxHeight="400px"
            />
          </CollapsibleSection>
        ))}

        {/* Per-sub-agent diffs */}
        {snapshot?.subAgents && Object.keys(snapshot.subAgents).map((saId: string) => (
          <CollapsibleSection key={saId} title={`Sub-agent: ${(snapshot.subAgents[saId] as any)?.agent?.name || saId}`} badge={
            diffBadgeStr(snapshot.subAgents[saId], source?.subAgents?.[saId])
          }>
            <DiffJsonView
              left={snapshot.subAgents[saId]}
              right={source?.subAgents?.[saId] || { error: "Not in source" }}
              leftLabel="Snapshot" rightLabel="Source" maxHeight="400px"
            />
          </CollapsibleSection>
        ))}
      </div>
    );
  };

  const renderWorkflowComparison = (snapshot: any, source: any) => {
    const snapshotPlan = snapshot || {};
    const sourcePlan = source?.plan || {};
    const sourceError = source?.error;

    const snapshotNodes = [
      ...(snapshotPlan.triggers || []),
      ...(snapshotPlan.mcps || []),
      ...(snapshotPlan.agents || []),
      ...(snapshotPlan.cores || []),
      ...(snapshotPlan.tables || []),
      ...(snapshotPlan.interfaces || []),
    ];
    const sourceNodes = [
      ...(sourcePlan.triggers || []),
      ...(sourcePlan.mcps || []),
      ...(sourcePlan.agents || []),
      ...(sourcePlan.cores || []),
      ...(sourcePlan.tables || []),
      ...(sourcePlan.interfaces || []),
    ];

    return (
      <div className="space-y-4">
        {sourceError && <SourceUnavailableNote detail={sourceError} t={t} />}

        {/* Nodes comparison */}
        <CollapsibleSection
          title={tc("nodes")}
          defaultOpen
          badge={snapshotNodes.length !== sourceNodes.length ? `${snapshotNodes.length} vs ${sourceNodes.length}` : undefined}
          count={snapshotNodes.length}
        >
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot ({snapshotNodes.length})</h4>
              <div className="space-y-1 max-h-48 overflow-auto">
                {snapshotNodes.map((n: any, i: number) => (
                  <div key={i} className="flex items-center gap-2 text-xs bg-theme-secondary rounded px-2 py-1">
                    <span className="font-mono text-theme-tertiary">{n.type || "-"}</span>
                    <span className="text-theme-primary">{n.label || n.name || ""}</span>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source ({sourceNodes.length})</h4>
              <div className="space-y-1 max-h-48 overflow-auto">
                {sourceNodes.map((n: any, i: number) => (
                  <div key={i} className="flex items-center gap-2 text-xs bg-theme-secondary rounded px-2 py-1">
                    <span className="font-mono text-theme-tertiary">{n.type || "-"}</span>
                    <span className="text-theme-primary">{n.label || n.name || ""}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </CollapsibleSection>

        {/* Edges comparison */}
        <CollapsibleSection
          title={tc("edges")}
          badge={(snapshotPlan.edges?.length || 0) !== (sourcePlan.edges?.length || 0) ? "Differs" : undefined}
          count={snapshotPlan.edges?.length || 0}
        >
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot ({(snapshotPlan.edges || []).length})</h4>
              <div className="space-y-1 max-h-40 overflow-auto">
                {(snapshotPlan.edges || []).map((e: any, i: number) => (
                  <div key={i} className="text-xs font-mono text-theme-secondary bg-theme-secondary rounded px-2 py-1">
                    {e.from || e.source || "?"} → {e.to || e.target || "?"}
                  </div>
                ))}
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source ({(sourcePlan.edges || []).length})</h4>
              <div className="space-y-1 max-h-40 overflow-auto">
                {(sourcePlan.edges || []).map((e: any, i: number) => (
                  <div key={i} className="text-xs font-mono text-theme-secondary bg-theme-secondary rounded px-2 py-1">
                    {e.from || e.source || "?"} → {e.to || e.target || "?"}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </CollapsibleSection>

        {/* Interfaces */}
        {((snapshotPlan.interfaces?.length || 0) > 0 || (sourcePlan.interfaces?.length || 0) > 0) && (
          <CollapsibleSection
            title={tc("interfaces")}
            count={snapshotPlan.interfaces?.length || 0}
            badge={(snapshotPlan.interfaces?.length || 0) !== (sourcePlan.interfaces?.length || 0) ? "Differs" : undefined}
          >
            <div className="grid grid-cols-2 gap-4">
              <div>
                <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Snapshot</h4>
                {(snapshotPlan.interfaces || []).map((iface: any, i: number) => (
                  <div key={i} className="text-xs bg-theme-secondary rounded px-2 py-1 mb-1">
                    {iface.label || iface._snapshot_name || iface.id || `Interface ${i + 1}`}
                  </div>
                ))}
              </div>
              <div>
                <h4 className="text-xs font-semibold text-theme-secondary uppercase mb-2">Source</h4>
                {(sourcePlan.interfaces || []).map((iface: any, i: number) => (
                  <div key={i} className="text-xs bg-theme-secondary rounded px-2 py-1 mb-1">
                    {iface.label || iface.id || `Interface ${i + 1}`}
                  </div>
                ))}
              </div>
            </div>
          </CollapsibleSection>
        )}

        {/* Full plan JSON diff */}
        <CollapsibleSection title="Full Plan JSON Diff" badge={
          diffBadgeStr(snapshot, sourcePlan)
        }>
          <DiffJsonView left={snapshot} right={sourcePlan} leftLabel="Snapshot" rightLabel="Source" maxHeight="500px" />
        </CollapsibleSection>
      </div>
    );
  };

  // ─── Step 3: Decision ───
  const renderStep3 = () => (
    <div className="space-y-6">
      <div className="rounded-xl border border-theme p-6 text-center">
        <div className="w-12 h-12 rounded-full bg-theme-secondary flex items-center justify-center mx-auto mb-4">
          <ShieldCheck className="h-6 w-6 text-theme-primary" />
        </div>
        <h3 className="text-sm font-semibold text-theme-primary mb-1">{publication.title}</h3>
        <p className="text-xs text-theme-secondary mb-4">
          {typeLabel} - {publication.visibility} - {publication.creditsPerUse ?? 0} credits
        </p>

        {error && (
          <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3 mb-4">
            <p className="text-sm text-red-800 dark:text-red-300">{error}</p>
          </div>
        )}

        {showRejectForm ? (
          <div className="space-y-3 text-left">
            <label className="text-sm font-medium text-theme-primary">{t("rejectionReason")}</label>
            <textarea
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              placeholder={t("rejectionReasonPlaceholder")}
              className="w-full h-24 text-sm border border-theme rounded-xl p-3 bg-theme-primary text-theme-primary resize-none focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
            />
            <div className="flex items-center gap-3 justify-end">
              <Button variant="outline" size="sm" onClick={() => setShowRejectForm(false)}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                size="sm"
                onClick={handleReject}
                disabled={actionLoading === "reject"}
              >
                {actionLoading === "reject" ? (
                  <LoadingSpinner size="xs" className="mr-1.5" />
                ) : (
                  <XCircle className="h-4 w-4 mr-1.5" />
                )}
                {t("reject")}
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex items-center justify-center gap-4">
            <Button
              variant="outline"
              onClick={() => setShowRejectForm(true)}
              className="text-red-600 border-red-200 hover:bg-red-50 dark:text-red-400 dark:border-red-800 dark:hover:bg-red-950"
            >
              <XCircle className="h-4 w-4 mr-1.5" />
              {t("reject")}
            </Button>
            <Button
              onClick={handleApprove}
              disabled={actionLoading === "approve"}
              className="bg-emerald-600 hover:bg-emerald-700 text-white"
            >
              {actionLoading === "approve" ? (
                <LoadingSpinner size="xs" className="mr-1.5" />
              ) : (
                <CheckCircle2 className="h-4 w-4 mr-1.5" />
              )}
              {t("approve")}
            </Button>
          </div>
        )}
      </div>
    </div>
  );

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-4xl max-h-[90vh] flex flex-col p-0">
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <TypeIcon className="h-5 w-5" />
              {tc("title")} - {publication.title}
            </DialogTitle>
          </DialogHeader>
          <div className="mt-4">
            <StepIndicator currentStep={currentStep} onStepClick={setCurrentStep} />
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4">
          {currentStep === 1 && renderStep1()}
          {currentStep === 2 && renderStep2()}
          {currentStep === 3 && renderStep3()}
        </div>

        {/* Footer navigation */}
        <div className="px-8 py-4 border-t border-theme flex justify-between">
          <Button
            variant="outline"
            onClick={() => setCurrentStep((s) => Math.max(1, s - 1))}
            disabled={currentStep === 1}
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back
          </Button>

          {currentStep < 3 ? (
            <Button onClick={() => setCurrentStep((s) => s + 1)}>
              Next
              <ArrowRight className="h-4 w-4 ml-2" />
            </Button>
          ) : (
            <Button variant="outline" onClick={onClose}>
              Close
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

// ─── Small sub-components ───

function InfoItem({ icon: Icon, label, value }: { icon: React.ComponentType<{ className?: string }>; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2">
      <Icon className="h-3.5 w-3.5 text-theme-tertiary shrink-0" />
      <span className="text-xs text-theme-secondary shrink-0">{label}:</span>
      <span className="text-sm text-theme-primary truncate">{value}</span>
    </div>
  );
}

function CompField({ label, value }: { label: string; value: any }) {
  return (
    <div>
      <span className="text-xs text-theme-tertiary">{label}</span>
      <p className="text-sm text-theme-primary">{value ?? "-"}</p>
    </div>
  );
}

/**
 * Informational (not blocking) note shown when the live source of a published resource
 * can't be rebuilt - typically because the publisher deleted or changed it since publishing.
 * The captured snapshot remains the source of truth for the approve/reject decision, so this
 * is amber-informational rather than a red failure. The raw backend detail is kept muted
 * underneath for the admin who wants to know exactly what drifted.
 */
function SourceUnavailableNote({ detail, t }: { detail?: string; t: ReturnType<typeof useTranslations> }) {
  if (!detail) return null;
  return (
    <div className="rounded-lg bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-800 p-3 flex items-start gap-2">
      <AlertTriangle className="h-4 w-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
      <div className="min-w-0">
        <p className="text-sm text-amber-800 dark:text-amber-300 font-medium">{t("sourceUnavailableTitle")}</p>
        <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">{t("sourceUnavailableNote")}</p>
        <p className="text-xs text-amber-600/70 dark:text-amber-500/70 mt-1 font-mono break-all">{detail}</p>
      </div>
    </div>
  );
}

