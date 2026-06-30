'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { useRouter } from '@/i18n/navigation';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Search, Wrench, Plus, Trash2, FileText } from 'lucide-react';
import { useUserApis } from '@/hooks/useUserApis';
import { useTranslations } from 'next-intl';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { customApiService } from '@/lib/api/orchestrator';

export interface MCPRow {
    id: string;
    name: string;
    description?: string;
    category?: string;
    toolCount: number;
    isActive: boolean;
    createdAt?: string;
    updatedAt?: string;
}

interface MCPTableProps {
    className?: string;
}

export function MCPTable({ className = '' }: MCPTableProps) {
    const router = useRouter();
    const t = useTranslations();
    // Audit 2026-05-17 round-6 - VIEWER gate on destructive actions.
    const canMutate = useCanMutateInCurrentOrg();
    const locale = getClientLocale();
    const { apis, isLoading: loading, error, fetchUserApis } = useUserApis();
    const [searchQuery, setSearchQuery] = useState('');
    const [selectedMCPs, setSelectedMCPs] = useState<Set<string>>(new Set());
    const [showDeleteModal, setShowDeleteModal] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [deleteError, setDeleteError] = useState<string | null>(null);

    // Convert APIs to MCP rows
    const mcps: MCPRow[] = useMemo(() => {
        if (!apis || !Array.isArray(apis)) return [];
        return apis.map((api: any) => ({
            id: api.id,
            name: api.apiName || 'Unnamed MCP',
            description: api.description || '',
            category: api.categoryName || 'Uncategorized',
            toolCount: api.tools?.length || 0,
            isActive: api.isActive !== false,
            createdAt: api.createdAt?.toString(),
            updatedAt: api.updatedAt?.toString(),
        }));
    }, [apis]);

    const filteredMCPs = useMemo(() => {
        const term = searchQuery.trim().toLowerCase();
        return mcps.filter((m) => {
            if (term.length === 0) return true;
            return (
                [m.name, m.description || '', m.category || '']
                    .join(' ')
                    .toLowerCase()
                    .includes(term)
            );
        });
    }, [mcps, searchQuery]);

    // Selection handlers
    const toggleMCPSelection = (id: string) => {
        setSelectedMCPs(prev => {
            const newSet = new Set(prev);
            if (newSet.has(id)) {
                newSet.delete(id);
            } else {
                newSet.add(id);
            }
            return newSet;
        });
    };

    const selectAllMCPs = () => {
        setSelectedMCPs(new Set(filteredMCPs.map(m => m.id)));
    };

    const clearMCPSelection = () => {
        setSelectedMCPs(new Set());
    };

    const deleteSelectedMCPs = () => {
        if (selectedMCPs.size === 0) return;
        setDeleteError(null);
        setShowDeleteModal(true);
    };

    const confirmDeleteMCPs = async () => {
        const idsToDelete = Array.from(selectedMCPs);
        if (idsToDelete.length === 0) return;

        setIsDeleting(true);
        setDeleteError(null);

        try {
            await Promise.all(idsToDelete.map((id) => customApiService.remove(id)));
            setSelectedMCPs(new Set());
            setShowDeleteModal(false);
            await fetchUserApis();
        } catch (err) {
            console.error('Error deleting MCPs:', err);
            setDeleteError(t('emptyState.mcp.deleteFailed'));
        } finally {
            setIsDeleting(false);
        }
    };

    return (
        <div className={`space-y-4 w-full overflow-visible ${className}`}>
            {/* Header with title and create button */}
            {mcps.length > 0 && (
                <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                            <Wrench className="w-5 h-5 text-theme-primary" />
                        </div>
                        <div>
                            <h2 className="text-lg font-semibold text-theme-primary">{t('emptyState.mcp.title')}</h2>
                            <p className="text-sm text-theme-secondary">{mcps.length} MCP{mcps.length !== 1 ? 's' : ''}</p>
                        </div>
                    </div>
                    <Button
                        onClick={() => router.push('/app/settings/developers')}
                        className="inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full border border-transparent font-medium tracking-wide transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-primary)] disabled:pointer-events-none disabled:opacity-60 disabled:cursor-not-allowed bg-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-[0_10px_28px_var(--shadow-color)] hover:bg-[var(--accent-hover)] hover:text-[var(--accent-foreground)] hover:shadow-[0_12px_32px_var(--shadow-color)] h-11 px-6 text-sm"
                    >
                        <Plus className="w-4 h-4" />
                        {t('emptyState.mcp.addButton')}
                    </Button>
                </div>
            )}

            {/* Search bar */}
            {mcps.length > 0 && (
                <div className="flex flex-col gap-4 md:flex-row md:items-center">
                    <div className="relative flex-1 overflow-visible">
                        <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
                        <Input
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            placeholder={t('emptyState.mcp.searchPlaceholder')}
                            className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 disabled:cursor-not-allowed disabled:opacity-50 pl-11"
                        />
                    </div>
                </div>
            )}

            {/* Contextual actions */}
            {selectedMCPs.size > 0 && (
                <div className="flex items-center gap-2">
                    {canMutate && (
                        <Button
                            variant="destructive"
                            size="sm"
                            onClick={deleteSelectedMCPs}
                        >
                            <Trash2 className="h-4 w-4 mr-1.5" />
                            {t('emptyState.mcp.deleteCount', { count: selectedMCPs.size })}
                        </Button>
                    )}
                    <Button
                        variant="ghost"
                        size="sm"
                        onClick={clearMCPSelection}
                    >
                        {t('common.clearSelection')}
                    </Button>
                </div>
            )}

            {/* Delete confirmation modal */}
            {showDeleteModal && (
                <div className="fixed inset-0 bg-black/50 z-[9999] flex items-center justify-center p-4" onClick={() => setShowDeleteModal(false)}>
                    <div
                        role="dialog"
                        aria-modal="true"
                        aria-label={t('emptyState.mcp.deleteTitle')}
                        className="bg-theme-primary rounded-lg p-6 shadow-lg max-w-md w-full"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <h3 className="text-lg font-semibold text-theme-primary mb-4">{t('emptyState.mcp.deleteTitle')}</h3>
                        <p className="text-theme-secondary mb-6">
                            {t('emptyState.mcp.deleteConfirmation', { count: selectedMCPs.size })}
                        </p>
                        {deleteError && (
                            <p className="text-sm text-red-600 mb-4">{deleteError}</p>
                        )}
                        <div className="flex justify-end gap-2">
                            <Button
                                variant="outline"
                                onClick={() => setShowDeleteModal(false)}
                                disabled={isDeleting}
                                className="inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full font-medium tracking-wide transition-all duration-200 border border-[var(--accent-primary)] bg-transparent text-[var(--accent-primary)] hover:bg-[var(--accent-primary)] hover:text-[var(--accent-foreground)] h-11 px-6 text-sm"
                            >
                                {t('common.cancel')}
                            </Button>
                            <Button
                                variant="destructive"
                                onClick={confirmDeleteMCPs}
                                disabled={isDeleting}
                                className="inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full border border-transparent font-medium tracking-wide transition-all duration-200 bg-red-600 text-white hover:bg-red-700 h-11 px-6 text-sm"
                            >
                                {t('common.delete')}
                            </Button>
                        </div>
                    </div>
                </div>
            )}

            {/* MCP Table */}
            <div className="space-y-4 w-full overflow-hidden">
                {loading ? (
                    <div className="w-full overflow-x-auto overflow-y-auto border border-theme rounded-xl max-h-[1200px] overflow-hidden">
                        <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
                            <thead className="bg-theme-secondary sticky top-0 z-20">
                                <tr>
                                    <th className="px-3 py-3 text-left font-medium text-theme-primary w-12 max-w-12 sticky left-0 z-30 bg-theme-secondary">
                                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4"></div>
                                    </th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary min-w-[200px]">
                                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24"></div>
                                    </th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary w-24">
                                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-16"></div>
                                    </th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary w-40 min-w-[160px]">
                                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-32"></div>
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-theme">
                                {Array.from({ length: 5 }).map((_, skeletonIndex) => (
                                    <tr key={`skeleton-${skeletonIndex}`} className="border border-transparent">
                                        <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10">
                                            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4" style={{
                                                animationDelay: `${skeletonIndex * 50}ms`
                                            }}></div>
                                        </td>
                                        <td className="px-3 py-2 min-w-[200px]">
                                            <div className="space-y-1">
                                                <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full max-w-[80%]" style={{
                                                    animationDelay: `${skeletonIndex * 50 + 20}ms`
                                                }}></div>
                                                <div className="h-3 bg-theme-tertiary rounded animate-pulse w-full max-w-[60%]" style={{
                                                    animationDelay: `${skeletonIndex * 50 + 30}ms`
                                                }}></div>
                                            </div>
                                        </td>
                                        <td className="px-3 py-2 w-24">
                                            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-12" style={{
                                                animationDelay: `${skeletonIndex * 50 + 35}ms`
                                            }}></div>
                                        </td>
                                        <td className="px-3 py-2 w-40 min-w-[160px]">
                                            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24" style={{
                                                animationDelay: `${skeletonIndex * 50 + 40}ms`
                                            }}></div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                ) : filteredMCPs.length === 0 ? (
                    <div className="text-center py-8 text-theme-secondary">
                        <Wrench className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
                        <p>{t('emptyState.mcp.noMCPsFound')}</p>
                        <p className="text-sm mt-2 text-theme-muted">
                            {mcps.length === 0
                                ? t('emptyState.mcp.addFirstMCP')
                                : t('emptyState.mcp.noMatchingMCPs')}
                        </p>
                        {mcps.length === 0 && (
                            <div className="mt-6 flex justify-center gap-2">
                                <Button
                                    variant="default"
                                    onClick={() => router.push('/app/settings/developers')}
                                    className="inline-flex items-center justify-center gap-2"
                                >
                                    <Plus className="w-4 h-4" />
                                    {t('emptyState.mcp.addButton')}
                                </Button>
                            </div>
                        )}
                    </div>
                ) : (
                    <div className="w-full overflow-x-auto overflow-y-auto border border-theme rounded-xl max-h-[1200px] overflow-hidden">
                        <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
                            <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-20">
                                <tr>
                                    <th className="px-3 py-3 text-center font-medium text-theme-primary w-12 max-w-12 sticky left-0 z-30 bg-theme-secondary">
                                        <div className="flex items-center justify-center">
                                            <input
                                                type="checkbox"
                                                checked={filteredMCPs.length > 0 && filteredMCPs.every(m => selectedMCPs.has(m.id))}
                                                onChange={filteredMCPs.every(m => selectedMCPs.has(m.id)) ? clearMCPSelection : selectAllMCPs}
                                                className="rounded border-theme"
                                                onClick={(e) => e.stopPropagation()}
                                            />
                                        </div>
                                    </th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary min-w-[200px]">{t('common.name')}</th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary w-24">{t('emptyState.mcp.tools')}</th>
                                    <th className="px-3 py-3 text-left font-medium text-theme-secondary w-40 min-w-[160px]">{t('emptyState.mcp.added')}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-theme">
                                {filteredMCPs.map((mcp) => (
                                    <tr
                                        key={mcp.id}
                                        className="border border-transparent cursor-pointer transition-colors hover-row-datasource group min-h-[62px] h-[62px]"
                                        onClick={() => router.push(`/app/settings/mcp/${mcp.id}`)}
                                    >
                                        <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10">
                                            <div className="w-full h-full flex items-center justify-center rounded relative">
                                                <input
                                                    type="checkbox"
                                                    checked={selectedMCPs.has(mcp.id)}
                                                    onChange={() => toggleMCPSelection(mcp.id)}
                                                    onClick={(e) => e.stopPropagation()}
                                                    className="rounded border-theme cursor-pointer"
                                                />
                                            </div>
                                        </td>
                                        <td className="px-3 py-2 w-[250px] max-w-[250px]">
                                            <div className="min-w-0">
                                                <div className="font-medium text-theme-primary truncate">{mcp.name}</div>
                                                <div className="text-sm text-theme-secondary truncate">{mcp.description || ''}</div>
                                            </div>
                                        </td>
                                        <td className="px-3 py-2 w-24">
                                            <span className="text-sm text-theme-primary">{mcp.toolCount}</span>
                                        </td>
                                        <td className="px-3 py-2 w-40 min-w-[160px]">
                                            {mcp.createdAt ? (
                                                <span className="text-sm text-theme-primary truncate block" title={formatUtcDateTime(mcp.createdAt, { locale })}>
                                                    {formatUtcDateTime(mcp.createdAt, { locale })}
                                                </span>
                                            ) : (
                                                <span className="text-sm text-theme-secondary">-</span>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
}
