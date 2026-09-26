'use client';

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { clampMenuLeft } from '@/lib/utils/menuPlacement';
import Link from 'next/link';
import { X, User, PanelLeft, LogOut, Moon, Sun, Monitor, Globe, Coins, ChevronRight, Check, Building2, UserPlus, Plus, Gift } from 'lucide-react';
import { getDisplayName } from '@/lib/utils/userUtils';
import { ConversationSidebar } from '@/components/chat/ConversationSidebar';
import { SearchConversationModal } from '@/components/chat/SearchConversationModal';
import LogoAnimate from '@/components/LogoAnimate';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { BalanceBreakdownTooltip } from '@/components/billing/BalanceBreakdown';
import { SidebarCreditRing, SidebarCreditMenuSection } from '@/components/billing/SidebarCreditBalance';
import { useTheme, type ThemePreference } from '@/components/ThemeProvider';
import { useSidebarSafe } from '@/contexts/SidebarContext';
import { useCurrentView } from '@/hooks/useCurrentView';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { useSwipeToDismiss } from '@/hooks/useSwipeToDismiss';
import { useUserProfile } from '@/hooks/useUserProfile';
import { useSubscription, useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { organizationApi, type Organization, type OrganizationRole } from '@/lib/api/organization-api';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { WorkspaceUpgradeModal } from '@/components/organization/WorkspaceUpgradeModal';
import { OwnerOnlyGateModal } from '@/components/organization/OwnerOnlyGateModal';
import { WorkspaceAvatar } from '@/components/organization/WorkspaceAvatar';
import { SetupChecklist } from '@/components/app/SetupChecklist';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import CreateWorkspaceModal from '@/components/organization/CreateWorkspaceModal';
import { Conversation, conversationRoute } from '@/lib/api/conversationApi';
import { useRouter, usePathname } from '@/i18n/navigation';
import { useTranslations, useLocale } from 'next-intl';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { triggerSidebarNavigation } from '@/components/NavigationLoader';
import { memo } from 'react';
import { LucideIcon } from 'lucide-react';
import { IS_CE } from '@/lib/edition';
import { reportExplicitLocaleChoice } from '@/lib/lifecycle/localeChoice';
import { cloudLinkService, CLOUD_NO_SUBSCRIPTION } from '@/lib/api/cloud-link.service';
import { CLOUD_PRICING_URL } from '@/lib/edition/cloudWebUrl';
import { NavIconButton } from '@/components/app/NavIconButton';
import { SidebarNavigation } from '@/components/app/SidebarNavigation';

// Re-exported so the surfaces (and tests) that have always imported it from
// here keep working now that it lives in its own module - SidebarNavigation
// needs it too, and importing it back out of this file would make the two
// modules cyclic.
export { NavIconButton };

/**
 * The fallback used when the sidebar context is absent (a surface that renders
 * this shell outside the provider). Module-level, because an inline `() => {}`
 * is a NEW function on every render, and these setters are dependencies of the
 * callbacks below - which are what the memoized navigation and conversation
 * components compare against.
 */
const NOOP = () => {};

interface AppSidebarProps {
  onConversationCreated?: (conversationId: string, title: string | null, isTemporary: boolean) => void;
  onTitleUpdated?: (conversationId: string, title: string, isTemporary: boolean) => void;
}

// Sidebar container classes.
// Mobile: an off-canvas drawer that is ALWAYS rendered and slides on its transform,
// like the right side panel animates its width. It used to be `hidden` while closed,
// and `display` cannot transition, so the drawer popped in and out with no motion.
// Tailwind v4 writes `translate-x-*` to the CSS `translate` property while the swipe drag
// writes `transform` inline, so both are transitioned. `invisible` rides the same
// transition (visibility flips at its END when closing), so the off-screen drawer stays
// out of the tab order and the accessibility tree.
// Desktop (md:) is unchanged: static rail/expanded column animated on its width.
// `touch-pan-y` hands horizontal moves to the swipe-to-close gesture; pinch-zoom stays allowed.
// Note: `translate-x-0` still computes to a non-`none` translate, which makes this column the
// containing block of any `position: fixed` child. Overlays opened from it must stay portalled.
export function appSidebarClasses(sidebarOpen: boolean, sidebarCollapsed: boolean): string {
  const baseClasses = 'bg-theme-secondary flex-shrink-0 overflow-hidden w-64 transition-[translate,transform,visibility] duration-300 ease-out md:transition-all md:duration-700 md:ease-in-out md:translate-x-0 md:visible touch-pan-y touch-pinch-zoom md:touch-auto';
  const collapsedWidthClasses = sidebarCollapsed ? 'md:w-16' : 'md:w-64';
  const positionClasses = sidebarOpen
    ? 'absolute inset-y-0 left-0 translate-x-0 z-[60] md:relative md:inset-auto md:h-full'
    : 'absolute inset-y-0 left-0 -translate-x-full invisible z-[60] md:static md:inset-auto md:z-auto';
  return `${collapsedWidthClasses} ${baseClasses} ${positionClasses}`;
}

export const AppSidebar = memo(function AppSidebar({
  onConversationCreated,
  onTitleUpdated,
}: AppSidebarProps = {}) {
  const t = useTranslations('sidebar');
  // Use native Next.js routing with navigation guard
  const router = useRouter();
  const safeNavigate = useSafeNavigate();
  const pathname = usePathname();
  // Only the conversation id is read here now: which entry is active is resolved
  // inside SidebarNavigation, for both shapes at once.
  const { conversationId: urlConversationId } = useCurrentView();

  // Use unified context for all app state
  const { state: appState, setCurrentConversationId, setIsNavigatingToNewChat } = useUnifiedApp();
  const currentConversationId = appState.currentConversationId || urlConversationId;

  // Sync context with URL when navigating (URL is source of truth for navigation)
  // NOTE: We only sync FROM URL TO context, never reset context here.
  // The reset is handled by UnifiedAppContext when isNavigatingToNewChat flag is set.
  useEffect(() => {
    const isNavigatingToNewChat = appState.isNavigatingToNewChat;

    // Skip sync if user just clicked "new chat" - UnifiedAppContext will handle the reset
    if (isNavigatingToNewChat) {
      console.log('[AppSidebar] Skipping URL sync - navigating to new chat (flag is set)');
      return;
    }

    if (urlConversationId && urlConversationId !== appState.currentConversationId) {
      // Navigating to a conversation - sync context with URL
      console.log('[AppSidebar] Syncing context to URL:', urlConversationId);
      setCurrentConversationId(urlConversationId);
    }
  }, [urlConversationId, appState.currentConversationId, appState.isNavigatingToNewChat, setCurrentConversationId]);

  // Use minimal sidebar context
  const sidebarContext = useSidebarSafe();
  const sidebarOpen = sidebarContext?.isOpen ?? false;
  const sidebarCollapsed = sidebarContext?.isCollapsed ?? true;
  const setSidebarOpen = sidebarContext?.setOpen ?? NOOP;
  const setSidebarCollapsed = sidebarContext?.setCollapsed ?? NOOP;

  const { user, isAuthenticated, isAuthChecking, avatarUrl, numericUserId } = useAuthGuard();
  const { profile: userProfile, isLoading: isProfileLoading } = useUserProfile();
  const { subscription, isLoading: isSubscriptionLoading } = useSubscription();
  const {
    balance: creditBalance,
    subBalance: creditSubBalance,
    paygBalance: creditPaygBalance,
    isLoading: isCreditBalanceLoading,
  } = useCreditBalance();
  // Owner-pays (ADR-009): /credits/balance resolves the payer server-side, so
  // the value returned IS the wallet that will be debited (owner's for guests,
  // executor's for solo). The frontend just renders it as-is - no role-based
  // branching that risks lying when the role hydration races.
  const { loginWithRedirect, logout } = useAuth();
  const { themePreference, setTheme } = useTheme();
  const [isSearchModalOpen, setIsSearchModalOpen] = useState(false);

  // Track if we've ever been authenticated (to avoid showing loading spinner on token refresh)
  const hasEverBeenAuthenticatedRef = useRef(false);
  if (isAuthenticated) {
    hasEverBeenAuthenticatedRef.current = true;
  }
  // Only show loading on first load, not on token refresh
  // Use isAuthChecking (OIDC native loading) for faster UI rendering
  const showAuthLoading = isAuthChecking && !hasEverBeenAuthenticatedRef.current;


  // PR7 audit B fix: prefer the active-workspace tier (activeOrgPlanCode,
  // set by auth-service from the gateway-resolved X-User-Plan header) over
  // the per-user subscription.planCode. After PR7 cutover the sidebar
  // badge follows the active workspace (Q1=b plan-follows-workspace);
  // pre-cutover OR if the field is missing, fall back to the legacy
  // per-user planCode unchanged.
  const activeOrgPlanCode = (subscription as any)?.activeOrgPlanCode || null;
  const personalPlanCode = (subscription as any)?.subscription?.planCode || null;
  const planCode = activeOrgPlanCode || personalPlanCode;

  const handleNavigate = useCallback((path: string) => {
    // Only show the navigation progress bar for a REAL page change. Resolve the
    // /chat alias to /app/chat and compare to the current path (locale stripped)
    // so clicking the page you are already on never starts the bar.
    const target = (path === '/chat' || path.startsWith('/chat')) ? '/app/chat' : path;
    const currentNoLocale = pathname?.replace(/^\/[a-z]{2}(?=\/|$)/, '') || '';
    if (target !== currentNoLocale) {
      triggerSidebarNavigation();
    }

    if (path.startsWith('/app/')) {
      safeNavigate(path);
    } else if (path === '/chat' || path.startsWith('/chat')) {
      safeNavigate('/app/chat');
    } else {
      safeNavigate(path);
    }

    if (sidebarOpen) {
      setSidebarOpen(false);
    }
  }, [safeNavigate, sidebarOpen, setSidebarOpen, pathname]);

  const handleConversationSelect = useCallback((conversation: Conversation | null) => {
    if (conversation) {
      // Routed by what the conversation IS, through the shared rule: a studio thread holds
      // generation envelopes and its composer submits to a generation model, so the chat surface
      // cannot serve it.
      safeNavigate(conversationRoute(conversation));
    } else {
      // Navigate to /app/chat for new chat
      console.log('[AppSidebar] New chat selected - navigating to new chat');

      // With multi-stream architecture, we can navigate without stopping the current stream
      // The stream will continue in the background and can be accessed by returning to the conversation

      // Check if we're already on /app/chat (conversation created without URL change)
      const pathWithoutLocale = pathname?.replace(/^\/[a-z]{2}(?=\/|$)/, '') || '';
      const isAlreadyOnNewChatPage = pathWithoutLocale === '/app/chat' || pathWithoutLocale === '/app' || pathWithoutLocale === '';

      if (isAlreadyOnNewChatPage) {
        // Already on /app/chat - reset directly without navigation
        console.log('[AppSidebar] Already on new chat page - resetting state directly');
        setCurrentConversationId(null);
        setIsNavigatingToNewChat(false); // Ensure flag is cleared
      } else {
        // Coming from a conversation page - set flag and navigate
        console.log('[AppSidebar] Navigating from conversation to new chat');
        setIsNavigatingToNewChat(true);
        safeNavigate('/app/chat');
      }
    }
    if (sidebarOpen) {
      setSidebarOpen(false);
    }
    // Deps are the values this actually calls. It used to name `appContext` -
    // the WHOLE unified context value, a new object on every model choice, tool
    // selection or conversation update - and `streaming`, which it never reads.
    // Either one re-created this callback mid-stream, which re-created the
    // navigation and conversation components' props, which is how a memo()
    // that looks right never once short-circuits.
  }, [safeNavigate, sidebarOpen, setSidebarOpen, setCurrentConversationId, setIsNavigatingToNewChat, pathname]);

  const handleSignOut = useCallback(() => {
    logout({ logoutParams: { returnTo: `${window.location.origin}/app/` } });
  }, [logout]);


  const handleSidebarToggle = useCallback(() => {
    setSidebarOpen(!sidebarOpen);
  }, [sidebarOpen, setSidebarOpen]);

  // A checklist task is a plain link: on mobile the drawer has to close itself, as every other
  // navigation from the sidebar does, or it keeps covering the page the link just opened.
  const handleChecklistNavigate = useCallback(() => {
    if (sidebarOpen) setSidebarOpen(false);
  }, [sidebarOpen, setSidebarOpen]);

  // Memoized like every other callback handed to the conversation sidebar: that
  // component is memo()'d, and one inline arrow here would give it a new prop on
  // every render of this shell and redraw the whole list for nothing.
  const handleOpenSearch = useCallback(() => {
    setIsSearchModalOpen(true);
    if (sidebarOpen) setSidebarOpen(false);
  }, [sidebarOpen, setSidebarOpen]);

  const handleLogin = useCallback(() => {
    loginWithRedirect();
  }, [loginWithRedirect]);


  // Memoized because ConversationSidebar is memo()'d and this is one of its
  // props. This shell re-renders on auth, profile, subscription, credit-balance
  // and theme updates, none of which the conversation list draws; an inline
  // arrow here would hand it a new prop on each of those and redraw the whole
  // list anyway. Same for handleNavigate and handleOpenSearch.
  const handleNewChat = useCallback(() => handleConversationSelect(null), [handleConversationSelect]);

  const sidebarClasses = useMemo(() => appSidebarClasses(sidebarOpen, sidebarCollapsed), [sidebarOpen, sidebarCollapsed]);

  const isMobile = useMobileDetection();
  const drawerRef = useRef<HTMLDivElement>(null);
  const backdropRef = useRef<HTMLDivElement>(null);
  const closeSidebar = useCallback(() => setSidebarOpen(false), [setSidebarOpen]);
  const swipeHandlers = useSwipeToDismiss({
    enabled: isMobile && sidebarOpen,
    panelRef: drawerRef,
    backdropRef,
    onDismiss: closeSidebar,
  });

  return (
    <>
      <div ref={drawerRef} data-testid="app-sidebar" className={sidebarClasses} {...swipeHandlers}>
        <div className="h-full flex flex-col">
          {/* Fixed Header Section */}
          <div className="flex-shrink-0">
            <div className={`${sidebarCollapsed ? 'px-1 py-1' : 'p-1'} group/header relative`}>
              {/* Desktop Logo and Title */}
              <div className={`hidden md:flex items-center ${sidebarCollapsed ? 'justify-center' : 'justify-between'} relative`}>
                <button
                  onClick={() => safeNavigate('/app')}
                  className={`flex items-center ${sidebarCollapsed ? 'justify-center' : 'mr-1'} group/logo relative cursor-pointer`}
                >
                  <div className={`relative flex items-center justify-center transition-opacity duration-300 ${sidebarCollapsed ? 'group-hover/header:opacity-0' : ''
                    }`}>
                    <LogoAnimate size="md" className="text-theme-primary" />
                  </div>
                  <span className={`text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title ${sidebarCollapsed
                    ? 'opacity-0 scale-95 w-0 overflow-hidden'
                    : 'opacity-100 scale-100 w-auto'
                    }`}>
                    LiveContext
                  </span>
                </button>

                {/* Collapse/Expand button */}
                {sidebarCollapsed ? (
                  <Button
                    onClick={() => setSidebarCollapsed(false)}
                    variant="ghost"
                    size="icon"
                    className="w-8 h-8 hidden md:flex items-center justify-center text-black dark:text-white opacity-0 group-hover/header:opacity-100 transition-opacity duration-300 absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 z-10"
                    title={t('expandSidebar')}
                  >
                    <PanelLeft className="w-4 h-4" />
                  </Button>
                ) : (
                  <Button
                    onClick={() => setSidebarCollapsed(true)}
                    variant="ghost"
                    size="icon"
                    className="w-8 h-8 mr-1 hidden md:flex items-center justify-center text-black dark:text-white"
                    title={t('collapseSidebar')}
                  >
                    <PanelLeft className="w-4 h-4" />
                  </Button>
                )}
              </div>

              {/* Mobile header */}
              <div className="flex md:hidden items-center justify-between w-full">
                <button
                  onClick={() => safeNavigate('/app')}
                  className="flex items-center group/logo relative pl-1 cursor-pointer"
                >
                  <LogoAnimate size="md" className="text-theme-primary" />
                  <span className="text-xl font-light text-theme-primary transition-colors duration-300 livecontext-title">
                    LiveContext
                  </span>
                </button>
                <Button
                  onClick={handleSidebarToggle}
                  variant="ghostGray"
                  size="icon"
                  className="w-8 h-8"
                  title={t('closeSidebar')}
                >
                  <X className="w-5 h-5" />
                </Button>
              </div>

              {/* Collapsed Icons */}
              <div
                // Hidden from assistive tech too, not just from the eye. This
                // block is clipped to zero rather than `display:none`d, so it
                // stays in the accessibility tree - which is why the sr-only
                // technique works. It duplicates every navigation entry the
                // expanded panel below already renders, and now that both
                // announce `aria-current`, a screen reader on the live page
                // heard "current page" twice.
                aria-hidden={!sidebarCollapsed}
                className={`hidden md:flex flex-col justify-center items-center gap-2 transition-all duration-300 ease-in-out ${sidebarCollapsed
                ? 'opacity-100 scale-100 mt-4'
                : 'opacity-0 scale-95 h-0 overflow-hidden pointer-events-none'
                }`}>
                <SidebarNavigation
                  variant="rail"
                  // `sidebarCollapsed` alone is not the rail being LIVE: on mobile
                  // the drawer opens over a still-"collapsed" desktop rail, and the
                  // panel inside it draws the same navigation (see the
                  // sidebarCollapsed prop passed to ConversationSidebar below). Same
                  // guard the user and sign-in sections already use.
                  active={sidebarCollapsed && !sidebarOpen}
                  currentConversationId={currentConversationId}
                  onNewChat={handleNewChat}
                  onNavigate={handleNavigate}
                />
              </div>
            </div>
          </div>

          {/* Scrollable Conversation Section */}
          <div className="flex-1 min-h-0 overflow-y-auto">
            <ConversationSidebar
              onConversationSelect={handleConversationSelect}
              currentConversationId={currentConversationId}
              className="h-full"
              sidebarCollapsed={sidebarOpen ? false : sidebarCollapsed}
              onConversationCreated={onConversationCreated}
              onTitleUpdated={onTitleUpdated}
              onNewChat={handleNewChat}
              onSearchClick={handleOpenSearch}
              onNavigate={handleNavigate}
            />
          </div>

          {/* User Section */}
          <div className={`${sidebarCollapsed ? 'p-2' : ''} flex-shrink-0 transition-all duration-300 ease-in-out`}>
            {showAuthLoading ? (
              // Skeleton loading for user section
              sidebarCollapsed ? (
                <div className="flex flex-col items-center gap-2">
                  <div className="w-8 h-8 rounded-full bg-theme-tertiary animate-pulse" />
                </div>
              ) : (
                <div className="flex-shrink-0 px-3 space-y-2">
                  <div className="flex items-center min-w-0 py-1">
                    <div className="w-8 h-8 rounded-full bg-theme-tertiary animate-pulse flex-shrink-0 mr-2" />
                    <div className="w-24 h-4 rounded bg-theme-tertiary animate-pulse" />
                  </div>
                </div>
              )
            ) : isAuthenticated ? (
              <>
              {/* What a new account still has to do: always in sight, right above the user. Self-hides. */}
              <SetupChecklist variant={sidebarCollapsed && !sidebarOpen ? 'rail' : 'panel'} onNavigate={handleChecklistNavigate} />
              <UserSection
                sidebarCollapsed={sidebarCollapsed && !sidebarOpen}
                user={user}
                avatarUrl={avatarUrl}
                numericUserId={numericUserId}
                planCode={planCode}
                isSubscriptionLoading={IS_CE ? false : isSubscriptionLoading}
                themePreference={themePreference}
                onThemeChange={setTheme}
                onSignOut={handleSignOut}
                onNavigate={handleNavigate}
                displayName={userProfile?.displayName}
                isLoadingProfile={isProfileLoading}
                creditBalance={IS_CE ? null : creditBalance}
                creditSubBalance={IS_CE ? null : creditSubBalance}
                creditPaygBalance={IS_CE ? null : creditPaygBalance}
                isCreditBalanceLoading={IS_CE ? false : isCreditBalanceLoading}
              />
              </>
            ) : (
              <SignInSection
                sidebarCollapsed={sidebarCollapsed && !sidebarOpen}
                onLogin={handleLogin}
              />
            )}
          </div>
        </div>
      </div>

      {/* Mobile Sidebar Overlay: always mounted so it fades with the slide; swiping on it closes too. */}
      <div
        ref={backdropRef}
        data-testid="app-sidebar-backdrop"
        aria-hidden="true"
        className={`fixed inset-0 bg-black/60 z-40 md:hidden transition-opacity duration-300 ease-out ${sidebarOpen ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
        onClick={sidebarOpen ? closeSidebar : undefined}
        {...swipeHandlers}
      />

      {/* Search Conversation Modal */}
      <SearchConversationModal
        isOpen={isSearchModalOpen}
        onClose={() => setIsSearchModalOpen(false)}
        onConversationSelect={handleConversationSelect}
        currentConversationId={currentConversationId}
      />
    </>
  );
});

// User Section Component
interface UserSectionProps {
  sidebarCollapsed: boolean;
  user: any;
  avatarUrl: string | null;
  /** Internal numeric user id - drives the canonical user avatar (photo or
   *  server-generated initials SVG) when no photo blob / OAuth picture is set. */
  numericUserId: number | null;
  planCode: string | null;
  isSubscriptionLoading: boolean;
  themePreference: ThemePreference;
  onThemeChange: (theme: ThemePreference) => void;
  onSignOut: () => void;
  onNavigate: (path: string) => void;
  displayName?: string | null;
  isLoadingProfile?: boolean;
  creditBalance?: number | null;
  /** V250 - sub-bucket breakdown (renewal grants). Drives the tooltip detail. */
  creditSubBalance?: number | null;
  /** V250 - PAYG-bucket breakdown (top-ups). Drives the tooltip detail. */
  creditPaygBalance?: number | null;
  isCreditBalanceLoading?: boolean;
}

/** The usage page, for the readout's two halves: its href and its click handler
 *  must lead to the same place and are written two lines apart. */
const QUOTA_PATH = '/app/settings/quota';

export const UserSection = memo(function UserSection({
  sidebarCollapsed,
  user,
  avatarUrl,
  numericUserId,
  planCode,
  isSubscriptionLoading,
  themePreference,
  onThemeChange,
  onSignOut,
  onNavigate,
  displayName,
  isLoadingProfile,
  creditBalance,
  creditSubBalance,
  creditPaygBalance,
  isCreditBalanceLoading,
}: UserSectionProps) {
  const t = useTranslations('sidebar');
  const tCloudPlan = useTranslations('ceCloudLink.planRequired');
  const locale = useLocale();
  const router = useRouter();
  const pathname = usePathname();
  // Build version + update status for the About menu entry (shared ['app-version'] query).
  // Use displayName from profile, fallback to OIDC user data
  const finalDisplayName = displayName || getDisplayName(user);

  // Format plan name for display (FREE → Free, ENTERPRISE_BUSINESS → Enterprise Business)
  const formatPlanName = (code: string | null): string => {
    if (!code) return 'Free';
    return code
      .split('_')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
      .join(' ');
  };
  // displayPlanName is computed after the CE cloud-link status resolves (see below).

  // Format credit balance for compact display
  const formatCredits = (balance: number): string => {
    // Format the magnitude with K/M compaction, then put the sign BEFORE the "$"
    // (a delinquent balance can be negative) - "-$1.2K", never "$-1.2K".
    const abs = Math.abs(balance);
    let formatted: string;
    if (abs >= 1_000_000) formatted = `${(abs / 1_000_000).toFixed(1)}M`;
    else if (abs >= 1_000) formatted = `${(abs / 1_000).toFixed(1)}K`;
    else formatted = abs.toFixed(1);
    const sign = balance < 0 ? '-' : '';
    return IS_CE ? `${sign}$${formatted}` : `${sign}${formatted}`;
  };

  const [showMenu, setShowMenu] = useState(false);
  const [showLanguageSubmenu, setShowLanguageSubmenu] = useState(false);
  const [showWorkspaceSubmenu, setShowWorkspaceSubmenu] = useState(false);
  const [showThemeSubmenu, setShowThemeSubmenu] = useState(false);
  const [showWorkspaceUpgradeModal, setShowWorkspaceUpgradeModal] = useState(false);
  // The upgrade gate is shared by two distinct upsells: "Invite teammates" (needs TEAM) and
  // "Create workspace" (needs PRO - additional workspaces are a PRO+ entitlement, not TEAM-only).
  // The variant drives the modal copy + target plan so each upsell points at the right tier.
  const [workspaceUpgradeVariant, setWorkspaceUpgradeVariant] = useState<'teammates' | 'workspace'>('teammates');
  // CE-only: a non-owner member who clicks invite / create-workspace sees an informational
  // "owner-only" modal (the install belongs to the admin) instead of the plan upsell flow.
  const [ownerOnlyGateAction, setOwnerOnlyGateAction] = useState<'invite' | 'workspace' | null>(null);
  // useState would tear under React's batched updates: a synchronous
  // double-click in the same tick reads the pre-flip value and both fire
  // setDefaultOrganization, tripping the auth-service 429 rate-limit.
  // useRef flips synchronously and serves as a single-click latch.
  const isSwitchingWorkspaceRef = useRef(false);
  const [isSwitchingWorkspace, setIsSwitchingWorkspace] = useState(false);
  const [menuPosition, setMenuPosition] = useState({ top: 0, left: 0, width: 0 });
  const menuRef = React.useRef<HTMLDivElement>(null);
  const buttonRef = React.useRef<HTMLButtonElement>(null);
  const [mounted, setMounted] = useState(false);

  // Workspace switcher state - list of orgs + current active org from the
  // store. The dropdown mirrors the language picker pattern (chevron + inline
  // submenu). Only mount the query when the menu is open to avoid useless
  // network on every sidebar render.
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const currentOrgRole = useCurrentOrgStore((s) => s.currentOrgRole);
  const setStoreCurrentOrg = useCurrentOrgStore((s) => s.setCurrentOrg);
  // CE-only owner gate: only OWNER/ADMIN of the active workspace can invite members /
  // create workspaces - everything else runs on the admin's plan. A non-manager MEMBER/VIEWER
  // gets an informational modal instead of the plan-gated flow (cloud build keeps existing behavior).
  const isWorkspaceManager = currentOrgRole === 'OWNER' || currentOrgRole === 'ADMIN';
  const { data: workspaces } = useQuery({
    queryKey: ['organizations', 'memberships'],
    queryFn: () => organizationApi.getOrganizations(),
    enabled: showMenu,
    staleTime: 5 * 60 * 1000,
  });
  // CE entitlements: the backend gates workspace creation / teammate invites primarily on
  // the GOVERNING cloud plan (cloud-link, CLOUD LLM source), with a fallback to the LOCAL
  // plan when no cloud plan governs. The affordance here only reads the cloud half: a
  // TEAM/PRO-linked CE gets the real create/invite flows; an unlinked/BYOK CE (or a member
  // who is not the link owner) sees the upgrade modal even if a locally-granted plan would
  // let the backend accept - deliberately conservative, never more permissive than the server.
  const { data: ceLinkStatus } = useQuery({
    queryKey: ['cloud-link', 'status'],
    queryFn: () => cloudLinkService.getStatus(),
    // Always fetch in CE (not only when the menu is open): the governing cloud plan also drives the
    // always-visible plan badge below, not just the in-menu invite / create-workspace gates.
    enabled: IS_CE,
    staleTime: 5 * 60 * 1000,
  });
  // PLAN TIER for THIS user (drives BOTH the badge label AND the create/invite entitlements): the
  // PER-USER cloud plan ONLY - NO install-link fallback. A member who merely inherits the admin's
  // install link has no per-user cloudPlanCode, so they resolve to FREE: they read "CE Free" (their
  // real tier) and get no create-workspace / invite affordance in their own workspace, NOT "CE Team"
  // (the install owner's plan they never paid for - which the backend also 403's). The cloud
  // (!IS_CE) branch is unchanged.
  const capabilityPlanCode = IS_CE ? (ceLinkStatus?.cloudPlanCode ?? null) : planCode;
  // "Is this install on cloud" DISPLAY flag - INSTALL-global so a non-owner member of an
  // admin-linked install still shows "CE <plan>" (not "Community") and drops the upsell: the member
  // IS cloud-connected (catalog/highlights visible), only their PLAN TIER stays their own.
  const isInstallCloudLinked = IS_CE && ceLinkStatus?.installLinked === true;
  // While the cloud-link status is still loading on a linked install, hold the badge to avoid
  // flashing the unlinked "Community" state. A resolved status (installLinked false) is NOT pending.
  const ceLinkPending = IS_CE && ceLinkStatus === undefined;
  // Plan badge label: a cloud-linked CE shows "CE <plan>" by the USER'S OWN cloud plan. The cloud's
  // '__NONE__' no-subscription sentinel and an inheriting member (no per-user plan) BOTH resolve to
  // null -> formatPlanName(null) = "Free", so each reads "CE Free" (never "CE None"/"CE Team"). We do
  // NOT fall back to the local/active-org planCode here: a member's active org may BE the owner's
  // TEAM org, which would wrongly relabel them "CE Team". An UNLINKED CE shows "Community".
  const ownCloudPlan = capabilityPlanCode && capabilityPlanCode !== CLOUD_NO_SUBSCRIPTION
    ? capabilityPlanCode
    : null;
  const displayPlanName = !IS_CE
    ? formatPlanName(planCode)
    : isInstallCloudLinked
      ? `CE ${formatPlanName(ownCloudPlan)}`
      : 'Community';
  // Upsell: Cloud → when no active subscription; CE → only when the install is NOT linked (a linked
  // install's billing is on the admin's cloud account, so neither owner nor member sees the upsell).
  // CE ONLY. The cloud upsell lives in the credits section at the top of the user
  // menu, next to the number that motivates it, and exists once instead of twice.
  // CE has no such section - it bills in dollars against no monthly grant - and
  // its upsell is "link this install to the cloud", so this badge stays the only
  // home for it there.
  const showUpgrade = IS_CE && !isInstallCloudLinked;
  // CE ONLY: the linked cloud account is not on a paid plan, so the cloud refuses every
  // link-gated call (the link is kept and comes back by itself once the account pays). The
  // badge takes the Upgrade CTA's place (a linked install never shows that one) and opens the
  // CLOUD pricing page, in a new tab, since that is where the plan is chosen.
  const showCloudPlanRequired = IS_CE && ceLinkStatus?.planRequired === true;
  const openCloudPricing = useCallback(() => {
    window.open(CLOUD_PRICING_URL, '_blank', 'noopener,noreferrer');
  }, []);
  // Never surface a "paused" (dormant) or soft-deleted org as the active workspace - the
  // owner downgraded below TEAM (paused) or it's pending purge (gateway rejects entering
  // both). Prefer current → default → any active, then anything.
  const activeWorkspace = workspaces?.find((w) => w.id === currentOrgId && !w.paused && !w.pendingDeletion)
    ?? workspaces?.find((w) => w.isDefault && !w.paused && !w.pendingDeletion)
    ?? workspaces?.find((w) => !w.paused && !w.pendingDeletion)
    ?? workspaces?.[0];
  const activeWorkspaceName = activeWorkspace?.name ?? '';
  // Team collaboration (invite members, assign roles, share a credit pool)
  // is the real value of TEAM / ENTERPRISE_*. FREE / STARTER / PRO see the
  // upgrade modal - they have no member-management surface today.
  const canInviteTeammates = capabilityPlanCode === 'TEAM' || (capabilityPlanCode?.startsWith('ENTERPRISE') ?? false);
  // Multiple workspaces are a PRO+ entitlement (shared-wallet model). FREE/STARTER own only
  // their personal workspace. The backend enforces the exact per-plan cap; this just decides
  // whether to surface the "Create workspace" affordance. Uses the CAPABILITY plan (no install
  // fallback) so an inherited-link member stays FREE here, not the install's display plan.
  const canCreateWorkspace = capabilityPlanCode === 'PRO' || capabilityPlanCode === 'TEAM' || (capabilityPlanCode?.startsWith('ENTERPRISE') ?? false);

  // Restore a soft-deleted workspace from the switcher (within its grace window).
  const queryClient = useQueryClient();
  const [restoringWorkspaceId, setRestoringWorkspaceId] = useState<string | null>(null);
  const [showCreateWorkspaceModal, setShowCreateWorkspaceModal] = useState(false);
  const handleWorkspaceCreated = useCallback(async (created: { id: string; currentUserRole?: string }) => {
    setShowCreateWorkspaceModal(false);
    await queryClient.invalidateQueries({ queryKey: ['organizations', 'memberships'] });
    // Switch into the freshly created workspace.
    try {
      await organizationApi.setDefaultOrganization(created.id);
      setStoreCurrentOrg(created.id, (created.currentUserRole as 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER') ?? 'OWNER');
      router.refresh();
    } catch (err) {
      console.error('Failed to switch into the new workspace', err);
    }
  }, [queryClient, setStoreCurrentOrg, router]);
  const handleRestoreWorkspace = useCallback(async (ws: { id: string }) => {
    setRestoringWorkspaceId(ws.id);
    try {
      await organizationApi.restoreOrganization(ws.id);
      await queryClient.invalidateQueries({ queryKey: ['organizations', 'memberships'] });
    } catch (err) {
      console.error('Failed to restore workspace', err);
    } finally {
      setRestoringWorkspaceId(null);
    }
  }, [queryClient]);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Update menu position when showing
  useEffect(() => {
    if (showMenu && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setMenuPosition({
        top: rect.top - 8, // Position above the button
        left: rect.left,
        width: rect.width,
      });
    }
  }, [showMenu]);

  // Close menu when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node) &&
        buttonRef.current && !buttonRef.current.contains(event.target as Node)) {
        setShowMenu(false);
        setShowLanguageSubmenu(false);
        setShowWorkspaceSubmenu(false);
        setShowThemeSubmenu(false);
      }
    };

    if (showMenu) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [showMenu]);

  const LANGUAGES = [
    { code: 'en', label: 'English' },
    { code: 'fr', label: 'Français' },
    { code: 'es', label: 'Español' },
    { code: 'de', label: 'Deutsch' },
    { code: 'pt', label: 'Português' },
    { code: 'zh', label: '中文' },
  ] as const;

  const currentLanguageLabel = LANGUAGES.find(l => l.code === locale)?.label ?? 'English';
  const themeOptions: Array<{ value: ThemePreference; label: string; icon: LucideIcon }> = [
    { value: 'auto', label: t('autoMode'), icon: Monitor },
    { value: 'light', label: t('lightMode'), icon: Sun },
    { value: 'dark', label: t('darkMode'), icon: Moon },
  ];
  const selectedThemeOption = themeOptions.find((option) => option.value === themePreference) ?? themeOptions[0];

  const handleLanguageChange = (langCode: string) => {
    document.cookie = `NEXT_LOCALE=${langCode}; path=/; max-age=31536000; SameSite=Lax`;
    // Best-effort, never awaited: the switch must not wait on or fail because of it.
    reportExplicitLocaleChoice(langCode);
    router.push(pathname, { locale: langCode });
    setShowMenu(false);
    setShowLanguageSubmenu(false);
  };

  const handleThemeChange = (newTheme: ThemePreference) => {
    onThemeChange(newTheme);
    setShowMenu(false);
    setShowThemeSubmenu(false);
  };

  // Workspace switch: setDefault server-side, flip the local store, refresh
  // server components. React-Query cache invalidation is handled centrally
  // by the F20 subscription in `smart-providers.tsx` - the previous explicit
  // Promise.all of 8 scoped invalidates here was a duplicate trigger that
  // doubled network round-trips on every switch.
  const handleWorkspaceSwitch = useCallback(async (target: Organization) => {
    // Synchronous double-click latch: useState would batch and let a second
    // click in the same tick slip through.
    if (isSwitchingWorkspaceRef.current) return;
    // Dormant org: the owner is no longer on a team plan, so this member cannot
    // enter it (the gateway rejects the claim and falls back to personal). The row
    // is rendered disabled; this is the defensive guard against a stray call.
    if (target.paused) return;
    if (target.id === currentOrgId) {
      setShowMenu(false);
      setShowWorkspaceSubmenu(false);
      return;
    }
    isSwitchingWorkspaceRef.current = true;
    setIsSwitchingWorkspace(true);
    try {
      await organizationApi.setDefaultOrganization(target.id);
      // Skip the store flip when the server response lacks a role for the
      // target workspace - avoids landing a MEMBER/VIEWER with a stale
      // OWNER role in the local store, which would unlock admin-only UI
      // gates until react-query refetches the membership row. Settings
      // page mirrors this guard at page.tsx:220-223.
      const role = target.currentUserRole as OrganizationRole | undefined;
      if (role) {
        setStoreCurrentOrg(target.id, role);
      }
      router.refresh();
    } catch (err) {
      // The sidebar has no inline error surface - at least log so prod
      // failures are visible in DevTools / sentry. Settings page handles
      // 429 vs generic; users who hit this can retry there.
      console.error('Workspace switch failed', err);
    } finally {
      isSwitchingWorkspaceRef.current = false;
      setIsSwitchingWorkspace(false);
      setShowMenu(false);
      setShowWorkspaceSubmenu(false);
    }
  }, [currentOrgId, router, setStoreCurrentOrg]);

  // Single entry to the shared upgrade gate so it can NEVER open without an explicit variant
  // (a stale variant would show the wrong copy). Both upsells route through here.
  const openWorkspaceUpgrade = useCallback((upgradeVariant: 'teammates' | 'workspace') => {
    setWorkspaceUpgradeVariant(upgradeVariant);
    setShowWorkspaceUpgradeModal(true);
    setShowMenu(false);
    setShowWorkspaceSubmenu(false);
  }, []);

  // The actual value of TEAM is collaboration (invite members, assign roles,
  // shared credit pool) - NOT "create a 2nd workspace" (that would require
  // a 2nd Stripe subscription, same as every competitor). The action sends
  // TEAM/ENTERPRISE users to the invite page and gates everyone else behind
  // the upgrade modal.
  const handleInviteTeammates = useCallback(() => {
    // CE-only: a non-manager member can't invite (the install belongs to the admin) - show the
    // informational owner-only modal instead of the plan-gated invite/upsell. Owners/admins and
    // the cloud build fall through to the existing behavior unchanged.
    if (IS_CE && !isWorkspaceManager) {
      setOwnerOnlyGateAction('invite');
      setShowMenu(false);
      setShowWorkspaceSubmenu(false);
      return;
    }
    if (canInviteTeammates) {
      // ?invite=1 - the organization page reads this and auto-opens the
      // InviteMemberModal once currentOrg has loaded, then strips the param
      // so a refresh doesn't reopen the modal.
      onNavigate('/app/settings/organization?invite=1');
      setShowMenu(false);
      setShowWorkspaceSubmenu(false);
      return;
    }
    openWorkspaceUpgrade('teammates');
  }, [isWorkspaceManager, canInviteTeammates, onNavigate, openWorkspaceUpgrade]);

  // Create-workspace is shown to EVERYONE (an entry point). PRO+ opens the create modal;
  // FREE/STARTER are routed to the upgrade gate - but with the WORKSPACE variant (→ PRO),
  // not the teammates/TEAM copy, since additional workspaces unlock on PRO.
  const handleCreateWorkspace = useCallback(() => {
    setShowMenu(false);
    setShowWorkspaceSubmenu(false);
    // CE-only: a non-manager member can't create workspaces (the install belongs to the admin) -
    // show the informational owner-only modal instead of the create/upsell flow. Owners/admins and
    // the cloud build keep the existing behavior unchanged.
    if (IS_CE && !isWorkspaceManager) {
      setOwnerOnlyGateAction('workspace');
      return;
    }
    if (canCreateWorkspace) {
      setShowCreateWorkspaceModal(true);
    } else {
      openWorkspaceUpgrade('workspace');
    }
  }, [isWorkspaceManager, canCreateWorkspace, openWorkspaceUpgrade]);

  const menuGroups = [
    // Group 1: Settings & Usage. No Pricing row: in cloud the credits section at the
    // top of this menu carries the Upgrade CTA, and in an UNLINKED CE install the
    // user block's own Upgrade badge does - so the row was a second copy of one
    // action wherever a CTA is on screen. Where neither is (a cloud-linked CE
    // install, or a wallet read that failed) pricing is still one hop away, in the
    // settings nav, which lists it in both editions.
    [
      { icon: User, label: t('settings'), onClick: () => { onNavigate('/app/settings/overview'); setShowMenu(false); } },
      // CE only. In cloud the credits section at the top of this menu shows the
      // figures and IS the link to that page, so a row that only navigates there
      // would say the same thing twice.
      ...(IS_CE
        ? [{ icon: Coins, label: t('cost'), onClick: () => { onNavigate('/app/settings/quota'); setShowMenu(false); } }]
        : []),
      // Refer & earn: a second entry point (alongside the settings nav) to the rewards page,
      // where the user shares their referral code/link and both parties earn credits.
      { icon: Gift, label: t('referAndEarn'), onClick: () => { onNavigate('/app/settings/rewards'); setShowMenu(false); } },
    ],
    // Group 2: Workspace switcher - both editions. CE supports organizations/workspaces
    // (invites, roles, plan-governed caps via cloud-link); the switcher used to be
    // cloud-only from the single-tenant CE era and that gate is now stale.
    // Multi-workspace users get the chevron + submenu (switch + invite);
    // solo users get a single "Invite teammates" row gated on plan.
    [
        // Workspace focus: ALWAYS show the active workspace (avatar + name) for every tier.
        // FREE/STARTER own exactly one (their personal) workspace and should still see it - same
        // as PRO+ - instead of a plan-gated "Invite teammates" row hiding it. The switch submenu
        // lists every workspace (just the personal one on solo tiers) plus the invite-teammates
        // action, which itself gates on plan.
        {
          icon: Building2,
          label: activeWorkspaceName || t('workspace'),
          isWorkspace: true,
          onClick: () => { setShowWorkspaceSubmenu(!showWorkspaceSubmenu); },
        },
        // Create-workspace sits at the SAME root level as the workspace focus, shown to EVERY tier:
        // PRO+ opens the create modal; FREE/STARTER are routed to payment (upgrade gate).
        {
          icon: Plus,
          label: t('createWorkspace'),
          onClick: () => { handleCreateWorkspace(); },
        },
      ],
    // Group 3: Language & Theme
    [
      // Platform status is NOT here. It was, above Language, and it put a
      // live-polling traffic light in the menu people open to sign out or switch
      // workspace. It now sits in Settings > Information, beside the other
      // answers to "what is this install" (PlatformStatusCard).
      { icon: Globe, label: currentLanguageLabel, isLanguage: true, onClick: () => { setShowLanguageSubmenu(!showLanguageSubmenu); } },
      { icon: selectedThemeOption.icon, label: selectedThemeOption.label, isTheme: true, onClick: () => { setShowThemeSubmenu(!showThemeSubmenu); } },
    ],
    // Group 4: Sign Out (at bottom)
    [
      { icon: LogOut, label: t('signOut'), onClick: () => { onSignOut(); setShowMenu(false); } },
    ],
  ];

  // Min 240px so the menu keeps the expanded width even when the sidebar is
  // collapsed (the collapsed avatar button is ~32px wide; without this the menu
  // would shrink to a narrow popup).
  const userMenuWidth = Math.max(menuPosition.width, 240);
  const menuContent = showMenu && mounted ? createPortal(
    <div
      ref={menuRef}
      data-testid="sidebar-user-menu"
      className="fixed z-[9999] bg-theme-primary rounded-2xl p-2 border border-gray-300/70 dark:border-gray-600/70"
      style={{
        top: `${menuPosition.top}px`,
        // Anchored to the trigger, then held inside the screen: the 240px floor
        // above can make the menu wider than the button it hangs from.
        left: `${clampMenuLeft(menuPosition.left, userMenuWidth)}px`,
        width: `${userMenuWidth}px`,
        maxWidth: 'calc(100vw - 1rem)',
        // The menu grows UPWARD from the trigger, which sits at the bottom of the
        // sidebar: on a phone its rows plus the credit readout plus an expanded
        // submenu are taller than the space above it, and the top of the menu
        // simply left the screen. Bounded by that space, with its own scroll.
        maxHeight: `${Math.max(200, menuPosition.top - 8)}px`,
        overflowY: 'auto',
        // Spelled out because CSS will not leave it alone: with one axis set to
        // `auto` the other's `visible` computes to `auto` too, which put a
        // horizontal scrollbar inside the menu on a screen narrower than it.
        overflowX: 'hidden',
        transform: 'translateY(-100%)'
      }}
    >
      {/* The wallet, first thing the menu says. Separated from the rows below
          because it is a readout, not a row of actions. */}
      <SidebarCreditMenuSection
        viewUsage={{
          // The href is what makes it a real link (new tab, copy link address);
          // the handler is the ordinary click, which routes client-side and
          // closes the menu behind it. Neither carries a locale prefix here:
          // the panel renders the app's locale-aware Link, and onNavigate goes
          // through the locale-aware router.
          href: QUOTA_PATH,
          onNavigate: () => { onNavigate(QUOTA_PATH); setShowMenu(false); },
        }}
        onUpgrade={() => { onNavigate('/app/settings/pricing'); setShowMenu(false); }}
      />
      <div className="space-y-1">
        {menuGroups.map((group, groupIndex) => (
          <div key={`menu-group-${groupIndex}`}>
            {groupIndex > 0 && <div className="border-t border-theme my-1" />}
            {group.map((item: any) => {
              const Icon = item.icon;
              const isSignOut = item.label === t('signOut');
              return (
                <React.Fragment key={item.label}>
                  <button
                    onClick={(e) => { e.stopPropagation(); item.onClick(); }}
                    className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors ${
                      isSignOut
                        ? 'text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30'
                        : 'text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800'
                    }`}
                  >
                    {item.isWorkspace
                      ? <WorkspaceAvatar name={activeWorkspaceName || item.label} avatarUrl={activeWorkspace?.avatarUrl} size="xs" className="border border-theme" />
                      : <Icon className="h-4 w-4" />}
                    <span className="text-sm flex-1 text-left truncate">{item.label}</span>
                    {item.isLanguage && <ChevronRight className={`h-3.5 w-3.5 text-theme-muted transition-transform ${showLanguageSubmenu ? 'rotate-90' : ''}`} />}
                    {item.isWorkspace && <ChevronRight className={`h-3.5 w-3.5 text-theme-muted transition-transform ${showWorkspaceSubmenu ? 'rotate-90' : ''}`} />}
                    {item.isTheme && <ChevronRight className={`h-3.5 w-3.5 text-theme-muted transition-transform ${showThemeSubmenu ? 'rotate-90' : ''}`} />}
                  </button>
                  {item.isLanguage && showLanguageSubmenu && (
                    <div className="ml-4 space-y-0.5">
                      {LANGUAGES.map((lang) => (
                        <button
                          key={lang.code}
                          onClick={(e) => { e.stopPropagation(); handleLanguageChange(lang.code); }}
                          className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors text-sm ${
                            locale === lang.code
                              ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary font-medium'
                              : 'text-theme-secondary hover:bg-gray-100 dark:hover:bg-gray-800'
                          }`}
                        >
                          {locale === lang.code ? <Check className="h-3.5 w-3.5" /> : <span className="w-3.5" />}
                          {lang.label}
                        </button>
                      ))}
                    </div>
                  )}
                  {item.isWorkspace && showWorkspaceSubmenu && (
                    <div className="ml-4 space-y-0.5">
                      {(workspaces ?? []).map((ws) => {
                        if (ws.pendingDeletion) {
                          const isRestoring = restoringWorkspaceId === ws.id;
                          return (
                            <div
                              key={ws.id}
                              title={t('workspaceDeletedHint')}
                              className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-theme-secondary opacity-80"
                            >
                              <WorkspaceAvatar name={ws.name} avatarUrl={ws.avatarUrl} size="xs" className="border border-theme grayscale" />
                              <span className="flex-1 text-left truncate line-through">{ws.name}</span>
                              <span className="text-xs px-1.5 py-0.5 rounded-full bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300">
                                {t('workspaceDeleted')}
                              </span>
                              <button
                                onClick={(e) => { e.stopPropagation(); handleRestoreWorkspace(ws); }}
                                disabled={isRestoring}
                                className="text-xs px-2 py-0.5 rounded-md border border-theme hover:bg-gray-100 dark:hover:bg-gray-800 disabled:opacity-50 cursor-pointer"
                              >
                                {isRestoring ? t('restoring') : t('restore')}
                              </button>
                            </div>
                          );
                        }
                        const isActive = ws.id === (activeWorkspace?.id ?? null);
                        const paused = !!ws.paused;
                        return (
                          <button
                            key={ws.id}
                            disabled={isSwitchingWorkspace || paused}
                            title={paused ? t('workspacePausedHint') : undefined}
                            onClick={(e) => { e.stopPropagation(); handleWorkspaceSwitch(ws); }}
                            className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg transition-colors text-sm disabled:opacity-50 disabled:cursor-not-allowed ${paused ? 'cursor-not-allowed' : 'cursor-pointer'} ${
                              isActive
                                ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary font-medium'
                                : 'text-theme-secondary hover:bg-gray-100 dark:hover:bg-gray-800'
                            }`}
                          >
                            <WorkspaceAvatar name={ws.name} avatarUrl={ws.avatarUrl} size="xs" className="border border-theme" />
                            <span className="flex-1 text-left truncate">{ws.name}</span>
                            {paused && (
                              <span className="text-xs px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">
                                {t('workspacePaused')}
                              </span>
                            )}
                            {isActive && <Check className="h-3.5 w-3.5 flex-shrink-0" />}
                          </button>
                        );
                      })}
                      {/* The switch submenu lists the workspaces to focus + the member action.
                          Create-workspace is a top-level sibling of this focus item, not here. */}
                      <button
                        onClick={(e) => { e.stopPropagation(); handleInviteTeammates(); }}
                        className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors text-sm text-theme-secondary hover:bg-gray-100 dark:hover:bg-gray-800"
                      >
                        <UserPlus className="h-3.5 w-3.5" />
                        <span className="flex-1 text-left">{t('inviteTeammates')}</span>
                      </button>
                    </div>
                  )}
                  {item.isTheme && showThemeSubmenu && (
                    <div className="ml-4 space-y-0.5">
                      {themeOptions.map((option) => {
                        const isActive = themePreference === option.value;
                        return (
                          <button
                            key={option.value}
                            onClick={(e) => { e.stopPropagation(); handleThemeChange(option.value); }}
                            className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors text-sm ${
                              isActive
                                ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary font-medium'
                                : 'text-theme-secondary hover:bg-gray-100 dark:hover:bg-gray-800'
                            }`}
                          >
                            {isActive ? <Check className="h-3.5 w-3.5" /> : <span className="w-3.5" />}
                            <span className="flex-1 text-left">{option.label}</span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </React.Fragment>
              );
            })}
          </div>
        ))}
      </div>
    </div>,
    document.body
  ) : null;

  return (
    <div className="relative">

      {menuContent}
      <WorkspaceUpgradeModal
        open={showWorkspaceUpgradeModal}
        variant={workspaceUpgradeVariant}
        onClose={() => setShowWorkspaceUpgradeModal(false)}
      />
      {ownerOnlyGateAction && (
        <OwnerOnlyGateModal
          open={ownerOnlyGateAction !== null}
          action={ownerOnlyGateAction}
          onClose={() => setOwnerOnlyGateAction(null)}
        />
      )}
      <CreateWorkspaceModal
        open={showCreateWorkspaceModal}
        onClose={() => setShowCreateWorkspaceModal(false)}
        onCreated={handleWorkspaceCreated}
      />

      {/* Avatar button */}
      {sidebarCollapsed ? (
        <div className="flex flex-col items-center gap-2">
          {/* Credit balance - collapsed. Under owner-pays (ADR-009), the
              /credits/balance endpoint resolves the payer server-side so the
              number returned IS the owner's wallet for guests, executor's
              wallet for solo users. Display as-is. */}
          {/* CE only. Cloud draws its wallet as a ring AROUND the avatar below,
              not as a separate item in this column.
              CAVEAT, verified 2026-09-01: this CE arm is DEAD. This component's
              only caller passes `creditBalance={IS_CE ? null : ...}` (see the
              props above), so in CE the badge's own null-guard is never
              satisfied and a self-hosted sidebar shows no cost indicator at all.
              Pre-existing, from the CE-monolith commit; left as-is here because
              resurrecting a CE billing display is a product call, not a side
              effect of restyling the cloud one. Pinned by
              AppSidebar.creditBlockMount.test.tsx so it cannot change unnoticed. */}
          {IS_CE && (!isCreditBalanceLoading && creditBalance !== null && creditBalance !== undefined && (
            <BalanceBreakdownTooltip subBalance={creditSubBalance ?? null} paygBalance={creditPaygBalance ?? null}>
              <button
                onClick={(e) => { e.stopPropagation(); onNavigate('/app/settings/quota'); }}
                className="flex items-center gap-0.5 text-xs text-theme-muted hover:text-theme-primary transition-colors cursor-pointer"
                title={`${t('cost')}: ${formatCredits(creditBalance)}`}
              >
                <Coins className="h-3 w-3" />
                <span className="text-[10px]">{formatCredits(creditBalance)}</span>
              </button>
            </BalanceBreakdownTooltip>
          ))}
          <button
            ref={buttonRef}
            onClick={(e) => { e.stopPropagation(); setShowMenu(!showMenu); }}
            /* w-11 = 44px = the 32px avatar plus the ring's 6px gap on each
               side. The button reserves that box, so the ring sits INSIDE it
               rather than escaping a 32px one. The hover affordance is a
               background rather than `ring-2`, which would have drawn a second
               ring on top of the credit ring. */
            className="w-11 h-11 p-0 rounded-full flex items-center justify-center cursor-pointer hover:bg-surface-hover transition-all"
            title={finalDisplayName}
          >
            <SidebarCreditRing>
              {(avatarUrl || user?.picture) ? (
                <img
                  src={avatarUrl || user.picture}
                  alt={finalDisplayName}
                  className="w-8 h-8 rounded-full border border-theme object-cover flex-shrink-0"
                />
              ) : (
                // No photo: canonical user avatar (server-generated initials SVG),
                // not a generic person icon - same letters as the public profile.
                <PublisherAvatar userId={numericUserId} name={finalDisplayName} size={32} variant="overlay" />
              )}
            </SidebarCreditRing>
          </button>
        </div>
      ) : (
        <div className="flex-shrink-0 px-3 py-2">
          <div className="flex items-center gap-2">
            {/* User info button - opens menu */}
            <button
              ref={buttonRef}
              onClick={(e) => { e.stopPropagation(); setShowMenu(!showMenu); }}
              className="group relative rounded-lg cursor-pointer transition-all duration-200 flex-1 min-w-0 bg-transparent hover:bg-surface-hover p-1"
            >
              <div className="flex items-center min-w-0">
                {/* The wallet is a ring AROUND this avatar, not an item beside
                    it: one indicator, in the place the eye already goes. The
                    wrapper reserves the ring's box, so the row lays out around
                    the ring instead of the ring overflowing the row. */}
                <div className="mr-2 flex-shrink-0">
                  <SidebarCreditRing>
                    {(avatarUrl || user?.picture) ? (
                      <img
                        src={avatarUrl || user.picture}
                        alt={finalDisplayName}
                        className="w-8 h-8 rounded-full border border-theme object-cover flex-shrink-0"
                      />
                    ) : (
                      // No photo: canonical user avatar (server-generated initials SVG),
                      // not a generic person icon - same letters as the public profile.
                      <PublisherAvatar userId={numericUserId} name={finalDisplayName} size={32} variant="overlay" />
                    )}
                  </SidebarCreditRing>
                </div>
                <div className="flex-1 min-w-0">
                  {isLoadingProfile ? (
                    <div className="w-24 h-4 rounded bg-theme-tertiary animate-pulse" />
                  ) : (
                    <h3 className="text-sm font-normal text-theme-primary truncate transition-colors text-left">
                      {finalDisplayName}
                    </h3>
                  )}
                  {/* Plan badge + Upgrade button under user name */}
                  <div className="flex items-center gap-2 mt-0.5">
                    {isSubscriptionLoading || ceLinkPending ? (
                      <div className="w-12 h-3 rounded bg-theme-tertiary animate-pulse" />
                    ) : (
                      <>
                        {/* The plan name is a LABEL, not a control. It used to open
                            the plan comparison, which put that overlay on a surface
                            the reader passes through constantly; it now lives on the
                            pricing page, where choosing a plan is the task. */}
                        <span data-testid="sidebar-plan-name" className="text-xs text-theme-muted">
                          {displayPlanName}
                        </span>
                        {showUpgrade && (
                          <span
                            role="link"
                            // Focusable and operable from the keyboard. A role says
                            // "this is a control", so the keyboard has to agree with it.
                            tabIndex={0}
                            onClick={(e) => { e.stopPropagation(); onNavigate('/app/settings/pricing'); }}
                            onKeyDown={(e) => {
                              if (e.key !== 'Enter' && e.key !== ' ') return;
                              e.preventDefault();
                              e.stopPropagation();
                              onNavigate('/app/settings/pricing');
                            }}
                            className="text-xs px-2 py-0.5 rounded-md bg-[var(--accent-primary)] text-[var(--accent-foreground)] hover:bg-[var(--accent-hover)] font-medium transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]"
                          >
                            {t('upgrade')}
                          </span>
                        )}
                        {showCloudPlanRequired && (
                          <span
                            role="link"
                            tabIndex={0}
                            data-testid="sidebar-cloud-plan-required"
                            title={tCloudPlan('title')}
                            onClick={(e) => { e.stopPropagation(); openCloudPricing(); }}
                            onKeyDown={(e) => {
                              if (e.key !== 'Enter' && e.key !== ' ') return;
                              e.preventDefault();
                              e.stopPropagation();
                              openCloudPricing();
                            }}
                            className="text-xs px-2 py-0.5 rounded-md bg-amber-500/15 text-amber-700 dark:text-amber-300 hover:bg-amber-500/25 font-medium transition-colors cursor-pointer truncate focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]"
                          >
                            {tCloudPlan('sidebarBadge')}
                          </span>
                        )}
                      </>
                    )}
                    {/* CE only. Cloud's wallet is the ring around the avatar to
                        the left, so nothing goes on this row.
                        Owner-pays (ADR-009): the backend resolves the payer, so
                        the number returned IS the owner's wallet for guests and
                        the executor's for solo users. Display as-is. */}
                    {IS_CE && (!isCreditBalanceLoading && creditBalance !== null && creditBalance !== undefined && (
                      <BalanceBreakdownTooltip subBalance={creditSubBalance ?? null} paygBalance={creditPaygBalance ?? null}>
                        <span
                          role="link"
                          onClick={(e) => { e.stopPropagation(); onNavigate('/app/settings/quota'); }}
                          className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary transition-colors cursor-pointer ml-auto"
                          title={t('viewQuota')}
                        >
                          <Coins className="h-3 w-3" />
                          {formatCredits(creditBalance)}
                        </span>
                      </BalanceBreakdownTooltip>
                    ))}
                  </div>
                </div>
              </div>
            </button>
          </div>
        </div>
      )}
    </div>
  );
});

// Sign In Section Component
interface SignInSectionProps {
  sidebarCollapsed: boolean;
  onLogin: () => void;
}

const SignInSection = memo(function SignInSection({ sidebarCollapsed, onLogin }: SignInSectionProps) {
  const t = useTranslations('sidebar');
  if (sidebarCollapsed) {
    return (
      <div className="flex justify-center p-2">
        <Button
          onClick={onLogin}
          variant="default"
          className="w-8 h-8 p-0 rounded-xl flex items-center justify-center shadow-none hover:shadow-none"
          title={t('signIn')}
        >
          <User className="w-4 h-4" />
        </Button>
      </div>
    );
  }

  return (
    <div className="p-2">
      <Button
        onClick={onLogin}
        variant="default"
        className="w-full justify-start shadow-none hover:shadow-none"
      >
        <User className="w-4 h-4" />
        {t('signIn')}
      </Button>
    </div>
  );
});
