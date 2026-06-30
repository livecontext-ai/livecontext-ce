'use client';

import React, { useEffect, useState, useMemo, useRef } from 'react';
import Link from 'next/link';
import { HardDrive, Workflow, Database, FileText, Sparkles, Layers, Bot, BarChart3, RefreshCw, AlertTriangle, User, Play, Activity, Layout, MessageSquare, Settings, Table2, Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { storageApi, StorageQuota, TenantStats } from '@/lib/api';
import type { StorageBreakdown, StorageCategory } from '@/lib/api';
import { STORAGE_CATEGORY_COLORS } from '@/lib/api';
import { useSubscription, usePlans } from '@/lib/hooks/smart-hooks-complete';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { organizationApi } from '@/lib/api/organization-api';
import { useQuery } from '@tanstack/react-query';
import StorageBreakdownChart from './components/StorageBreakdownChart';
import { WorkspaceScopeSelect, ALL_WORKSPACES_SCOPE } from '@/components/settings/WorkspaceScopeSelect';
import { aggregateStorageQuotas, aggregateTenantStats, aggregateBreakdowns } from './storageAggregation';

// Plan storage limits in bytes - defense-in-depth fallback when the
// /billing/plans response is unavailable. Values must mirror the seed in
// migration V4__seed_auth_data.sql; the backend response is the source of truth.
const PLAN_STORAGE_LIMITS: Record<string, number> = {
    'FREE': 100 * 1024 * 1024,                // 100 MB
    'STARTER': 1 * 1024 * 1024 * 1024,        // 1 GB
    'PRO': 10 * 1024 * 1024 * 1024,           // 10 GB
    'TEAM': 100 * 1024 * 1024 * 1024,         // 100 GB
    'PAYG': 5 * 1024 * 1024 * 1024,           // 5 GB included
    'ENTERPRISE': 0,                           // Unlimited (0 = unlimited)
    'ENTERPRISE_BASIC': 0,
    'ENTERPRISE_STANDARD': 0,
    'ENTERPRISE_PREMIUM': 0,
    'ENTERPRISE_ULTIMATE': 0,
};

const CATEGORY_ICONS: Record<string, React.ElementType> = {
    STEP_OUTPUTS: Play,
    FILES: FileText,
    EXECUTION_DATA: Activity,
    AGENTS: Bot,
    INTERFACES: Layout,
    CONVERSATIONS: MessageSquare,
    CONFIGURATION: Settings,
    DATATABLES: Table2,
    PUBLICATIONS: Globe,
};

/**
 * Storage dashboard page
 * Shows storage usage with a visual gauge based on the user's plan
 */
export default function StoragePage() {
    const t = useTranslations('storage');
    const tSettings = useTranslations('settings');
    const tDashboard = useTranslations('dashboard');
    const { isLoading: authLoading, isAuthenticated, loginWithRedirect } = useAuth();
    const { subscription, isLoading: subscriptionLoading } = useSubscription();
    const { plans } = usePlans();
    // Strict-isolation scope: switching workspace MUST refetch quota + breakdown
    // so we never paint the previous workspace's numbers under the new label.
    const { currentOrgId } = useCurrentOrg();
    // Page-local workspace filter: defaults to the globally-active workspace and
    // resets to it on a sidebar switch, but lets the user re-scope JUST this page
    // to any of their workspaces (see WorkspaceScopeSelect). Every quota /
    // breakdown / stats / history fetch + the trend chart key off this id.
    const [scopeOrgId, setScopeOrgId] = useState<string | null>(currentOrgId);
    useEffect(() => {
        setScopeOrgId(currentOrgId);
    }, [currentOrgId]);
    const isOrgScope = !!scopeOrgId;

    // "Team workspace" must mean an actual TEAM org, NOT merely "an org id is
    // set". Every user owns a *personal* organization (is_personal=true) created
    // at onboarding, and the active-workspace store resolves currentOrgId to it
    // by default - so `!!currentOrgId` is true even in the personal workspace.
    // Reuse the same membership query as the sidebar switcher (shared cache key)
    // and label as a team only when the active org is non-personal.
    const { data: workspaces } = useQuery({
        queryKey: ['organizations', 'memberships'],
        queryFn: () => organizationApi.getOrganizations(),
        // CE supports organizations/workspaces too (cloud-link-governed caps) - the
        // membership lookup that labels a team workspace applies in both editions.
        enabled: isAuthenticated && !!currentOrgId,
        staleTime: 5 * 60 * 1000,
    });
    const isTeamWorkspace = !!workspaces?.some((w) => w.id === scopeOrgId && !w.isPersonal);

    // "All workspaces" view: aggregate every enterable workspace client-side.
    const isAll = scopeOrgId === ALL_WORKSPACES_SCOPE;
    const enterableOrgIds = useMemo(
        () => (workspaces ?? []).filter((w) => !w.paused && !w.pendingDeletion).map((w) => w.id),
        [workspaces],
    );
    // Stable primitive for the fetch effect's dep list (the array ref changes each render).
    const enterableOrgIdsKey = enterableOrgIds.join(',');

    const [quota, setQuota] = useState<StorageQuota | null>(null);
    const [stats, setStats] = useState<TenantStats | null>(null);
    const [breakdown, setBreakdown] = useState<StorageBreakdown[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [recalculating, setRecalculating] = useState(false);
    const requestSeqRef = useRef(0);

    // Get current plan code from subscription
    const currentPlanCode = useMemo(() => {
        const sub = subscription as any;
        return sub?.subscription?.planCode || 'FREE';
    }, [subscription]);

    // Get plan storage limit from plan data or fallback to constants.
    // In org scope the user's own plan doesn't drive the cap - the org's
    // backend-resolved maxBytes does. Trust quota.maxBytes directly.
    const planStorageLimit = useMemo(() => {
        if (isOrgScope) {
            return quota?.maxBytes ?? PLAN_STORAGE_LIMITS['FREE'];
        }
        // Personal scope: derive from the user's own subscription plan.
        if (plans && Array.isArray(plans)) {
            const plan = (plans as any[]).find((p: any) => p.code === currentPlanCode);
            if (plan?.includedStorageBytes) {
                return plan.includedStorageBytes;
            }
        }
        return PLAN_STORAGE_LIMITS[currentPlanCode] || PLAN_STORAGE_LIMITS['FREE'];
    }, [plans, currentPlanCode, isOrgScope, quota?.maxBytes]);

    const isUnlimited = quota?.unlimited === true
        || planStorageLimit === 0
        || (!isOrgScope && currentPlanCode.startsWith('ENTERPRISE'));

    const isCurrentRequest = (requestSeq: number) => requestSeq === requestSeqRef.current;

    const fetchQuota = async (requestSeq = requestSeqRef.current) => {
        try {
            if (isCurrentRequest(requestSeq)) {
                setError(null);
            }
            const data = await storageApi.getQuota(scopeOrgId);
            if (isCurrentRequest(requestSeq)) {
                setQuota(data);
            }
        } catch (err) {
            console.error('Failed to fetch storage quota:', err);
            if (isCurrentRequest(requestSeq)) {
                setError('Failed to load storage information');
            }
        }
    };

    const fetchStats = async (requestSeq = requestSeqRef.current) => {
        try {
            const data = await storageApi.getStats(scopeOrgId);
            if (isCurrentRequest(requestSeq)) {
                setStats(data);
            }
        } catch (err) {
            console.error('Failed to fetch tenant stats:', err);
        }
    };

    const fetchBreakdown = async (requestSeq = requestSeqRef.current) => {
        try {
            const data = await storageApi.getBreakdown(scopeOrgId);
            // Defensive: ensure state stays as an array even if the API
            // returns null / undefined / an error envelope (e.g. during a
            // transient proxy 500 that the apiClient swallows differently
            // across hot-reload cycles). Downstream useMemo + .map + .length
            // all assume Array shape.
            if (isCurrentRequest(requestSeq)) {
                setBreakdown(Array.isArray(data) ? data : []);
            }
        } catch (err) {
            console.error('Failed to fetch storage breakdown:', err);
        }
    };

    // "All workspaces": fetch each workspace's quota / stats / breakdown in
    // parallel and sum them (a per-workspace failure degrades to that workspace
    // contributing nothing rather than failing the whole view).
    const fetchAllWorkspaces = async (requestSeq: number, ids: string[]) => {
        if (ids.length === 0) {
            if (isCurrentRequest(requestSeq)) { setQuota(null); setStats(null); setBreakdown([]); }
            return;
        }
        try {
            if (isCurrentRequest(requestSeq)) setError(null);
            const [quotas, statsList, breakdowns] = await Promise.all([
                Promise.all(ids.map((id) => storageApi.getQuota(id).catch(() => null))),
                Promise.all(ids.map((id) => storageApi.getStats(id).catch(() => null))),
                Promise.all(ids.map((id) => storageApi.getBreakdown(id).catch(() => [] as StorageBreakdown[]))),
            ]);
            if (!isCurrentRequest(requestSeq)) return;
            const okQuotas = quotas.filter((q): q is StorageQuota => !!q);
            // Every workspace quota failed: surface an error rather than an empty
            // aggregate (maxBytes 0 would otherwise read as "unlimited" -> a misleading ∞).
            if (okQuotas.length === 0) {
                setError('Failed to load storage information');
                setQuota(null); setStats(null); setBreakdown([]);
                return;
            }
            setQuota(aggregateStorageQuotas(okQuotas));
            setStats(aggregateTenantStats(statsList.filter((s): s is TenantStats => !!s)));
            setBreakdown(aggregateBreakdowns(breakdowns.map((b) => (Array.isArray(b) ? b : []))));
        } catch (err) {
            console.error('Failed to aggregate storage across workspaces:', err);
            if (isCurrentRequest(requestSeq)) setError('Failed to load storage information');
        }
    };

    // A single fetch key: in single-workspace mode it tracks the scoped org (the
    // membership list loading must NOT retrigger a fetch); in "All" mode it tracks
    // the workspace-id set so the aggregate re-runs once the memberships arrive.
    const storageFetchKey = isAll ? `all:${enterableOrgIdsKey}` : `one:${scopeOrgId ?? ''}`;

    useEffect(() => {
        if (authLoading || subscriptionLoading) return;
        const requestSeq = ++requestSeqRef.current;
        if (isAuthenticated) {
            // Reset state on workspace switch so the UI doesn't render the
            // previous workspace's numbers under the new badge while the
            // next fetch is in flight.
            setLoading(true);
            setQuota(null);
            setBreakdown([]);
            const run = isAll
                ? fetchAllWorkspaces(requestSeq, enterableOrgIds)
                : Promise.all([fetchQuota(requestSeq), fetchStats(requestSeq), fetchBreakdown(requestSeq)]);
            run.finally(() => {
                if (isCurrentRequest(requestSeq)) {
                    setLoading(false);
                }
            });
        } else {
            setLoading(false);
        }
        // storageFetchKey changes on a sidebar switch, an in-page filter change, OR
        // (only in "All" mode) when the membership list loads.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [authLoading, subscriptionLoading, isAuthenticated, storageFetchKey]);

    const handleRecalculate = async () => {
        const requestSeq = ++requestSeqRef.current;
        setRecalculating(true);
        try {
            const data = await storageApi.recalculateUsage(scopeOrgId);
            if (isCurrentRequest(requestSeq)) {
                setQuota(data);
            }
            // Refresh breakdown after recalculation
            await fetchBreakdown(requestSeq);
        } catch (err) {
            console.error('Failed to recalculate storage:', err);
        } finally {
            if (isCurrentRequest(requestSeq)) {
                setRecalculating(false);
            }
        }
    };

    const formatBytes = (bytes: number) => {
        const safe = Math.max(0, bytes);
        if (safe === 0) return '0 B';
        if (safe < 1024) return `${safe} B`;
        if (safe < 1024 * 1024) return `${(safe / 1024).toFixed(1)} KB`;
        if (safe < 1024 * 1024 * 1024) return `${(safe / (1024 * 1024)).toFixed(1)} MB`;
        return `${(safe / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    };

    // Calculate usage percentage based on plan limit
    const usagePercentage = useMemo(() => {
        if (!quota || isUnlimited) return 0;
        const limit = planStorageLimit || quota.maxBytes;
        return Math.min(100, (quota.usedBytes / limit) * 100);
    }, [quota, planStorageLimit, isUnlimited]);

    // Determine status based on plan limit
    const effectiveStatus = useMemo(() => {
        if (!quota || isUnlimited) return 'OK';
        const limit = planStorageLimit || quota.maxBytes;
        const softLimit = limit * 0.8;
        if (quota.usedBytes >= limit) return 'HARD_LIMIT_REACHED';
        if (quota.usedBytes >= softLimit) return 'SOFT_LIMIT_REACHED';
        return 'OK';
    }, [quota, planStorageLimit, isUnlimited]);

    const getProgressColor = (status: string) => {
        switch (status) {
            case 'HARD_LIMIT_REACHED':
                return 'bg-red-500';
            case 'SOFT_LIMIT_REACHED':
                return 'bg-amber-500';
            default:
                return 'bg-black dark:bg-white';
        }
    };

    // Compute breakdown total for stacked bar percentages
    const breakdownTotal = useMemo(() => {
        // Belt-and-braces guard: in addition to the setBreakdown Array.isArray
        // check in fetchBreakdown above, this catches any future direct setState
        // path that bypasses fetchBreakdown.
        if (!Array.isArray(breakdown)) return 0;
        return breakdown.reduce((sum, b) => sum + b.usedBytes, 0);
    }, [breakdown]);

    const quickStats = [
        { icon: Workflow, labelKey: 'workflows' as const, value: stats?.workflowCount ?? '-' },
        { icon: Layers, labelKey: 'interfaces' as const, value: stats?.interfaceCount ?? '-' },
        { icon: Database, labelKey: 'tables' as const, value: stats?.tableCount ?? '-' },
        { icon: Bot, labelKey: 'agents' as const, value: stats?.agentCount ?? '-' },
    ];

    if (authLoading || subscriptionLoading || loading) {
        return (
            <div className="space-y-8">
                <div className="bg-theme-secondary rounded-xl p-6 animate-pulse">
                    <div className="h-6 bg-theme-tertiary rounded w-1/3 mb-4" />
                    <div className="h-3 bg-theme-tertiary rounded-full" />
                </div>
            </div>
        );
    }

    if (!isAuthenticated) {
        return (
            <div className="space-y-8">
                <div className="mx-auto max-w-4xl">
                    <div className="min-h-[300px] flex items-center justify-center">
                        <div className="text-center">
                            <h1 className="text-2xl font-bold text-theme-primary mb-4">
                                {tSettings('unauthorized')}
                            </h1>
                            <p className="text-theme-secondary mb-6">
                                {tSettings('mustBeLoggedIn')}
                            </p>
                            <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                                <User className="w-4 h-4 mr-1" />
                                {tSettings('signIn')}
                            </Button>
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    const displayLimit = isUnlimited ? t('unlimited') : formatBytes(planStorageLimit);

    return (
        <div className="space-y-8">
            {/* Page-local workspace filter (hidden unless the user has 2+ workspaces) */}
            <WorkspaceScopeSelect value={scopeOrgId} onChange={setScopeOrgId} includeAllOption className="justify-end" />

            {/* Storage Gauge Card */}
            <div className="rounded-xl border border-theme p-6">
                <div className="flex items-center justify-between mb-6">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                            <HardDrive className="w-5 h-5 text-theme-primary" />
                        </div>
                        <div>
                            <div className="flex items-center gap-2">
                                <h2 className="text-lg font-semibold text-theme-primary">{t('usage.title')}</h2>
                            </div>
                            <p className="text-sm text-theme-secondary">
                                {quota
                                    ? t('usage.usedOf', { used: formatBytes(quota.usedBytes), total: displayLimit })
                                    : error || '-'
                                }
                            </p>
                            <p className="text-xs text-theme-tertiary mt-0.5">
                                {isAll
                                    ? t('scope.allWorkspaces')
                                    : isTeamWorkspace ? t('scope.teamPlanLabel') : `${currentPlanCode} ${t('plan')}`}
                            </p>
                        </div>
                    </div>
                    <div className="flex items-center gap-3">
                        {effectiveStatus === 'SOFT_LIMIT_REACHED' && (
                            <div className="flex items-center gap-1.5 text-amber-500">
                                <AlertTriangle className="w-4 h-4" />
                                <span className="text-sm">{t('status.softLimit')}</span>
                            </div>
                        )}
                        {effectiveStatus === 'HARD_LIMIT_REACHED' && (
                            <div className="flex items-center gap-1.5 text-red-500">
                                <AlertTriangle className="w-4 h-4" />
                                <span className="text-sm">{t('status.hardLimit')}</span>
                            </div>
                        )}
                        <span className="text-2xl font-semibold text-theme-primary">
                            {isUnlimited ? '∞' : `${usagePercentage.toFixed(1)}%`}
                        </span>
                        {/* Recalculation is a single-workspace operation - no "recalc all". */}
                        {!isAll && (
                            <Button
                                variant="ghost"
                                size="icon"
                                onClick={handleRecalculate}
                                disabled={recalculating}
                                className="h-8 w-8"
                                title={t('actions.recalculate')}
                            >
                                <RefreshCw className={`w-4 h-4 ${recalculating ? 'animate-spin' : ''}`} />
                            </Button>
                        )}
                    </div>
                </div>

                {/* Progress Bar - same style as the wallet gauges in /quota:
                    h-1.5, bg-theme-tertiary track, neutral fill in the OK state.
                    Status colors (red/amber) take over once a soft/hard limit
                    is breached. */}
                <div className="space-y-2">
                    <div className="h-1.5 bg-theme-tertiary rounded-full overflow-hidden">
                        {!isUnlimited && (
                            <div
                                className={`h-full ${getProgressColor(effectiveStatus)} transition-all duration-500`}
                                style={{ width: `${usagePercentage}%` }}
                            />
                        )}
                    </div>

                    <div className="flex justify-between text-xs text-theme-muted">
                        <span>0 GB</span>
                        <span>{displayLimit}</span>
                    </div>
                </div>
            </div>

            {/* Storage Breakdown by Category */}
            <div>
                <div className="flex items-center gap-3 mb-4">
                    <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                        <Database className="w-5 h-5 text-theme-primary" />
                    </div>
                    <div>
                        <h2 className="text-lg font-semibold text-theme-primary">{t('breakdown.title')}</h2>
                        <p className="text-sm text-theme-secondary">{t('breakdown.subtitle')}</p>
                    </div>
                </div>

                {breakdown.length > 0 ? (
                    <>
                        {/* Stacked Bar - same h-1.5 / bg-theme-tertiary track
                            as the Storage Usage gauge above; per-category
                            color segments survive since the bar is the only
                            visualization that decomposes the total. */}
                        <div className="h-1.5 bg-theme-tertiary rounded-full overflow-hidden flex mb-4">
                            {breakdown
                                .filter(b => b.usedBytes > 0)
                                .map((b) => {
                                    const pct = breakdownTotal > 0 ? (b.usedBytes / breakdownTotal) * 100 : 0;
                                    const colorClass = STORAGE_CATEGORY_COLORS[b.category as StorageCategory] || 'bg-gray-400';
                                    return (
                                        <div
                                            key={b.category}
                                            className={`h-full ${colorClass} transition-all duration-500`}
                                            style={{ width: `${pct}%` }}
                                            title={`${t(`categories.${b.category}` as any)}: ${formatBytes(b.usedBytes)}`}
                                        />
                                    );
                                })}
                        </div>

                        {/* Category Cards */}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                            {breakdown.map((b) => {
                                const Icon = CATEGORY_ICONS[b.category] || Database;
                                const colorClass = STORAGE_CATEGORY_COLORS[b.category as StorageCategory] || 'bg-gray-400';
                                return (
                                    <div key={b.category} className="bg-theme-secondary rounded-xl p-4">
                                        <div className="flex items-center gap-3">
                                            <div className={`w-3 h-3 rounded-full ${colorClass} flex-shrink-0`} />
                                            <div className="min-w-0">
                                                <p className="text-sm font-semibold text-theme-primary truncate">
                                                    {formatBytes(b.usedBytes)}
                                                </p>
                                                <p className="text-xs text-theme-secondary truncate">
                                                    {t(`categories.${b.category}` as any)}
                                                </p>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </>
                ) : (
                    <div className="bg-theme-secondary rounded-xl p-6 text-center">
                        <p className="text-sm text-theme-secondary">{t('breakdown.noData')}</p>
                    </div>
                )}
            </div>

            {/* Usage Trends Chart */}
            <StorageBreakdownChart
                currentBreakdown={breakdown}
                orgId={isAll ? null : scopeOrgId}
                allWorkspaceIds={isAll ? enterableOrgIds : undefined}
            />

            {/* Quick Stats */}
            <div>
                <div className="flex items-center gap-3 mb-4">
                    <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                        <BarChart3 className="w-5 h-5 text-theme-primary" />
                    </div>
                    <div>
                        <h2 className="text-lg font-semibold text-theme-primary">{tDashboard('quickStats')}</h2>
                        <p className="text-sm text-theme-secondary">{tDashboard('activityAtGlance')}</p>
                    </div>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                    {quickStats.map((stat) => {
                        const Icon = stat.icon;
                        return (
                            <div
                                key={stat.labelKey}
                                className="bg-theme-secondary rounded-xl p-4 hover:bg-theme-tertiary transition-colors"
                            >
                                <div className="flex items-center gap-3">
                                    <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
                                        <Icon className="w-5 h-5 text-theme-primary" />
                                    </div>
                                    <div>
                                        <div className="text-xl font-semibold text-theme-primary">{stat.value}</div>
                                        <div className="text-sm text-theme-secondary">{tDashboard(stat.labelKey)}</div>
                                    </div>
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>

            {/* Upgrade CTA - hidden when storage is unlimited (CE / Enterprise):
                no point upselling more space when there is no cap. */}
            {!isUnlimited && !currentPlanCode.startsWith('ENTERPRISE') && (
                <div className="bg-theme-secondary rounded-xl p-6">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                            <div className="w-10 h-10 bg-black dark:bg-white rounded-full flex items-center justify-center">
                                <Sparkles className="w-5 h-5 text-white dark:text-black" />
                            </div>
                            <div>
                                <h3 className="text-lg font-semibold text-theme-primary">{t('upgrade.title')}</h3>
                                <p className="text-sm text-theme-secondary">{t('upgrade.description')}</p>
                            </div>
                        </div>
                        <Button asChild variant="default" size="sm" className="h-8 px-3">
                            <Link href="/app/settings/pricing">
                                {t('upgrade.button')}
                            </Link>
                        </Button>
                    </div>
                </div>
            )}
        </div>
    );
}
