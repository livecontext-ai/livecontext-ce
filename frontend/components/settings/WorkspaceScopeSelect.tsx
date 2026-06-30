'use client';

import React from 'react';
import { Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useQuery } from '@tanstack/react-query';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import { WorkspaceAvatar } from '@/components/organization/WorkspaceAvatar';
import { organizationApi } from '@/lib/api/organization-api';
import { useAuth } from '@/lib/providers/smart-providers';
import { cn } from '@/lib/utils';

/**
 * Sentinel {@link Props.value} meaning "aggregate across every workspace" rather
 * than a single workspace. The Quota page maps it to {@code allWorkspaces=true}
 * on the usage reads (which then return the full payer aggregate, including
 * unattributed/legacy rows). Only offered when {@link Props.includeAllOption} is
 * set - Storage has no cross-workspace aggregate, so it never shows this option.
 */
export const ALL_WORKSPACES_SCOPE = '__all_workspaces__';

/**
 * Page-local workspace filter for the Quota & Storage settings pages.
 *
 * <p>Lists the workspaces the current user can actually enter and lets them
 * re-scope the page to ANY of them without switching their global active
 * workspace (the sidebar switcher). The selected id is handed back via
 * {@link Props.onChange}; the caller passes it as a per-request override
 * (`X-Active-Organization-ID`) to the quota / storage APIs, which the gateway
 * validates against the user's memberships.
 *
 * <p>Each option shows the workspace avatar (the same chip the sidebar switcher
 * renders) so the list is scannable at a glance. When {@link Props.includeAllOption}
 * is set, a leading "All workspaces" option (globe icon) is offered whose value is
 * {@link ALL_WORKSPACES_SCOPE}.
 *
 * <p>Renders nothing when the user has fewer than two enterable workspaces:
 * a single-workspace user has nothing to filter, so the control would be pure
 * clutter (the "All workspaces" option is meaningless with one workspace too).
 * "Enterable" excludes paused (owner downgraded below TEAM) and soft-deleted
 * (pending purge) orgs - the gateway rejects entering both, so selecting one
 * would only paint an empty/blocked view (mirrors the sidebar's own filter and
 * {@code reconcileCurrentOrgFromMemberships}).
 *
 * <p>Cloud and CE both expose workspaces. The query is shared with the sidebar
 * workspace switcher so the page-local filter follows the same enterable list.
 */
interface Props {
  /** Currently-selected value: a workspace id, {@link ALL_WORKSPACES_SCOPE}, or null. */
  value: string | null;
  /** Fired with the chosen value when the user picks a different one. */
  onChange: (value: string) => void;
  /** Show the leading "All workspaces" aggregate option (Quota only). */
  includeAllOption?: boolean;
  className?: string;
}

export function WorkspaceScopeSelect({ value, onChange, includeAllOption = false, className }: Props) {
  const t = useTranslations('workspaceScope');
  const { isAuthenticated } = useAuth();

  // Shared cache key with the sidebar switcher + storage page so the membership
  // list is fetched once and reused.
  const { data: workspaces } = useQuery({
    queryKey: ['organizations', 'memberships'],
    queryFn: () => organizationApi.getOrganizations(),
    enabled: isAuthenticated,
    staleTime: 5 * 60 * 1000,
  });

  const enterable = (workspaces ?? []).filter((w) => !w.paused && !w.pendingDeletion);

  // Nothing to filter with a single (or no) workspace - render nothing.
  if (enterable.length < 2) return null;

  const isAll = value === ALL_WORKSPACES_SCOPE;
  const selected = isAll ? null : enterable.find((w) => w.id === value) ?? null;

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <Select value={value ?? undefined} onValueChange={onChange}>
        {/* A <div> (not <span>) wrapper: SelectTrigger applies `[&>span]:line-clamp-1`
            to a direct <span> child, which sets display:-webkit-box (vertical) and would
            stack the avatar ABOVE the name. The div keeps the flex row; the name span
            truncates with an ellipsis like the sidebar user menu. */}
        <SelectTrigger className="w-full sm:w-[240px] h-9 min-h-0 py-0 text-sm" aria-label={t('label')}>
          <div className="flex items-center gap-2 min-w-0 flex-1">
            {isAll ? (
              <>
                <Globe className="h-4 w-4 shrink-0 text-theme-secondary" aria-hidden="true" />
                <span className="flex-1 text-left truncate">{t('allWorkspaces')}</span>
              </>
            ) : selected ? (
              <>
                <WorkspaceAvatar name={selected.name} avatarUrl={selected.avatarUrl} size="xs" className="border border-theme" />
                <span className="flex-1 text-left truncate">{selected.name}</span>
              </>
            ) : (
              <span className="flex-1 text-left truncate text-theme-secondary">{t('label')}</span>
            )}
          </div>
        </SelectTrigger>
        <SelectContent>
          {includeAllOption && (
            <SelectItem key={ALL_WORKSPACES_SCOPE} value={ALL_WORKSPACES_SCOPE}>
              <div className="flex items-center gap-2 min-w-0">
                <Globe className="h-4 w-4 shrink-0 text-theme-secondary" aria-hidden="true" />
                <span className="truncate">{t('allWorkspaces')}</span>
              </div>
            </SelectItem>
          )}
          {enterable.map((w) => (
            <SelectItem key={w.id} value={w.id}>
              <div className="flex items-center gap-2 min-w-0">
                <WorkspaceAvatar name={w.name} avatarUrl={w.avatarUrl} size="xs" className="border border-theme" />
                <span className="truncate">{w.name}</span>
              </div>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

export default WorkspaceScopeSelect;
