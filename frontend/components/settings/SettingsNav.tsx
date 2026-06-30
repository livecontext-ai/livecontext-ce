'use client';

import React from 'react';
import { usePathname } from '@/i18n/navigation';
import { settingsNavItems } from './settingsNavItems';
import { cn } from '@/lib/utils';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { triggerSidebarNavigation } from '@/components/NavigationLoader';
import { organizationApi } from '@/lib/api';
import type { OrganizationRole } from '@/lib/api';
import { useAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { useAppVersion } from '@/hooks/useAppVersion';

/**
 * Vertical navigation menu for settings pages
 * Displays in the central content area, left side
 */
export function SettingsNav() {
    const pathname = usePathname();
    const safeNavigate = useSafeNavigate();
    const [userRole, setUserRole] = React.useState<OrganizationRole | null>(null);

    React.useEffect(() => {
        organizationApi.getOrganizations().then(orgs => {
            const defaultOrg = orgs?.find(o => o.isDefault) || orgs?.[0];
            if (defaultOrg) {
                setUserRole(defaultOrg.currentUserRole);
            }
        }).catch(() => {
            // Silently fail - non-admin items still visible
        });
    }, []);

    const { hasRole } = useAuth();
    const isPlatformAdmin = hasRole('ADMIN');

    // Discreet "you're behind" dot on the Information entry (self-hosted only).
    // Reuses the shared ['app-version'] query - no extra request.
    const { version } = useAppVersion();
    const showUpdateDot = !!version?.selfHosted && !!version?.updateAvailable;

    // Admin-only items (node-types, platform-credentials, ai-providers,
    // agent-debug, publication-review) require the PLATFORM ADMIN role in
    // BOTH modes. Org-OWNER is not enough - every regular user is OWNER of
    // their personal auto-org and would otherwise see these items.
    const isAdmin = isPlatformAdmin;

    // Filter items based on admin role and CE mode
    const visibleItems = React.useMemo(() => {
        return settingsNavItems.filter(item =>
            !item.hidden && (!item.adminOnly || isAdmin) && (!item.hiddenInCE || !IS_CE) && (!item.ceOnly || IS_CE)
        );
    }, [isAdmin]);

    // Determine active nav item
    const activeHref = React.useMemo(() => {
        if (!pathname) return '/app/settings/overview';

        // Check for exact match first
        const exactMatch = visibleItems.find(item => pathname === item.href);
        if (exactMatch) return exactMatch.href;

        // Check for prefix match
        const prefixMatch = visibleItems.find(item =>
            pathname.startsWith(item.href + '/')
        );
        if (prefixMatch) return prefixMatch.href;

        // Sub-routes: map to their parent nav item
        const parentMatch = visibleItems.find(item =>
            pathname.startsWith(item.href.replace(/\/$/, '') + '/')
        );
        if (parentMatch) return parentMatch.href;

        // Default to overview
        if (pathname === '/app/settings' || pathname === '/app/settings/') {
            return '/app/settings/overview';
        }

        return '/app/settings/overview';
    }, [pathname, visibleItems]);

    return (
        <nav className="w-full md:w-48 flex-shrink-0 overflow-x-auto md:overflow-visible md:self-start md:sticky md:top-8 pb-1 md:pb-0" style={{ scrollbarWidth: 'none' }}>
            <div className="flex md:block min-w-max md:min-w-0 gap-1 md:gap-0 md:space-y-1 px-1 md:px-0">
                {visibleItems.map((item) => {
                    const Icon = item.icon;
                    const isActive = activeHref === item.href;
                    return (
                        <button
                            key={item.href}
                            onClick={() => {
                                triggerSidebarNavigation();
                                safeNavigate(item.href);
                            }}
                            className={cn(
                                'group flex items-center gap-1.5 md:gap-2 px-2.5 md:px-3 py-2 rounded-lg text-sm transition-all duration-200 md:w-full text-left whitespace-nowrap md:whitespace-normal flex-shrink-0',
                                isActive
                                    ? 'bg-theme-secondary text-theme-primary'
                                    : 'text-theme-secondary hover:bg-gray-200 dark:hover:bg-gray-700 hover:text-theme-primary'
                            )}
                        >
                            <Icon className="w-4 h-4 flex-shrink-0" />
                            <span className="min-w-0 md:break-words">{item.label}</span>
                            {item.href === '/app/settings/information' && showUpdateDot && (
                                <span
                                    className={cn(
                                        'ml-1 h-1.5 w-1.5 shrink-0 rounded-full',
                                        version?.securityFix ? 'bg-red-500' : 'bg-amber-500',
                                    )}
                                    aria-hidden="true"
                                />
                            )}
                        </button>
                    );
                })}
            </div>
        </nav>
    );
}
