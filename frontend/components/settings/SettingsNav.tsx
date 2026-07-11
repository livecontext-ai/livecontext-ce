'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight } from 'lucide-react';
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
    const t = useTranslations('settings.nav');
    const [userRole, setUserRole] = React.useState<OrganizationRole | null>(null);

    // Mobile scroll affordance: the nav is a horizontal strip with a hidden
    // scrollbar, so without a cue users cannot tell more items exist off-screen.
    const scrollRef = React.useRef<HTMLElement>(null);
    const [scrollHint, setScrollHint] = React.useState({ left: false, right: false });

    const updateScrollHint = React.useCallback(() => {
        const el = scrollRef.current;
        if (!el) return;
        // 4px tolerance absorbs sub-pixel rounding on high-DPI screens.
        const left = el.scrollLeft > 4;
        const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
        setScrollHint(prev => (prev.left === left && prev.right === right) ? prev : { left, right });
    }, []);

    React.useEffect(() => {
        updateScrollHint();
        const el = scrollRef.current;
        if (!el || typeof ResizeObserver === 'undefined') return;
        const observer = new ResizeObserver(updateScrollHint);
        observer.observe(el);
        // The strip widens after the admin-role fetch reveals more items.
        if (el.firstElementChild) observer.observe(el.firstElementChild);
        return () => observer.disconnect();
    }, [updateScrollHint]);

    const nudge = React.useCallback((direction: 1 | -1) => {
        const el = scrollRef.current;
        if (!el) return;
        el.scrollBy({ left: direction * Math.round(el.clientWidth * 0.6), behavior: 'smooth' });
    }, []);

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
        <div className="relative w-full md:w-48 flex-shrink-0 md:self-start md:sticky md:top-8">
            <nav ref={scrollRef} onScroll={updateScrollHint} className="w-full overflow-x-auto md:overflow-visible pb-1 md:pb-0" style={{ scrollbarWidth: 'none' }}>
            <div className="flex md:block min-w-max md:min-w-0 gap-1 md:gap-0 md:space-y-1 px-1 md:px-0">
                {visibleItems.map((item) => {
                    const Icon = item.icon;
                    const isActive = activeHref === item.href;
                    return (
                        <button
                            key={item.href}
                            onClick={() => {
                                // Only show the navigation progress bar when actually
                                // going to a different settings page (not the current one).
                                if (!isActive) {
                                    triggerSidebarNavigation();
                                }
                                safeNavigate(item.href);
                            }}
                            className={cn(
                                // min-h-9 = the app standard control height; py-2 lets long
                                // labels wrap to two lines on desktop without clipping.
                                'group flex items-center gap-1.5 md:gap-2 min-h-9 px-2.5 md:px-3 py-2 rounded-lg text-sm transition-colors duration-150 md:w-full text-left whitespace-nowrap md:whitespace-normal flex-shrink-0',
                                // Same tokens as the app sidebar rows: bg-surface-hover for
                                // both the active state and the hover, so the two navs match.
                                isActive
                                    ? 'bg-surface-hover text-theme-primary font-medium'
                                    : 'text-theme-secondary hover:bg-surface-hover hover:text-theme-primary'
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
            {scrollHint.left && (
                <div className="md:hidden pointer-events-none absolute inset-y-0 left-0 flex items-center bg-gradient-to-r from-[var(--bg-primary)] to-transparent pr-5 pb-1">
                    <button
                        type="button"
                        onClick={() => nudge(-1)}
                        aria-label={t('scrollLeft')}
                        className="pointer-events-auto flex h-6 w-6 items-center justify-center rounded-full bg-theme-secondary text-theme-secondary shadow-sm"
                    >
                        <ChevronLeft className="h-4 w-4" />
                    </button>
                </div>
            )}
            {scrollHint.right && (
                <div className="md:hidden pointer-events-none absolute inset-y-0 right-0 flex items-center bg-gradient-to-l from-[var(--bg-primary)] to-transparent pl-5 pb-1">
                    <button
                        type="button"
                        onClick={() => nudge(1)}
                        aria-label={t('scrollRight')}
                        className="pointer-events-auto flex h-6 w-6 items-center justify-center rounded-full bg-theme-secondary text-theme-secondary shadow-sm"
                    >
                        <ChevronRight className="h-4 w-4" />
                    </button>
                </div>
            )}
        </div>
    );
}
