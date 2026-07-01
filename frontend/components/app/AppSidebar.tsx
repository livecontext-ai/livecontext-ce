'use client';

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import Link from 'next/link';
import { X, User, Workflow, Table, MessageCircle, PanelLeft, Store, LogOut, Moon, Sun, Monitor, Bot, CreditCard, Globe, AppWindow, Coins, Info, ChevronRight, Check, Home, Building2, UserPlus, Folder, Plus, Columns3, Gift } from 'lucide-react';
import { getDisplayName } from '@/lib/utils/userUtils';
import { ConversationSidebar } from '@/components/chat/ConversationSidebar';
import { SearchConversationModal } from '@/components/chat/SearchConversationModal';
import LogoAnimate from '@/components/LogoAnimate';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useAppVersion } from '@/hooks/useAppVersion';
import AboutMenuVersion from '@/components/app/AboutMenuVersion';
import { Button } from '@/components/ui/button';
import { BalanceBreakdownTooltip } from '@/components/billing/BalanceBreakdown';
import { useTheme, type ThemePreference } from '@/components/ThemeProvider';
import { useSidebarSafe } from '@/contexts/SidebarContext';
import { useCurrentView } from '@/hooks/useCurrentView';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useStreamingSafe } from '@/contexts/StreamingContext';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useUserProfile } from '@/hooks/useUserProfile';
import { useSubscription, useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { organizationApi, type Organization, type OrganizationRole } from '@/lib/api/organization-api';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { WorkspaceUpgradeModal } from '@/components/organization/WorkspaceUpgradeModal';
import { OwnerOnlyGateModal } from '@/components/organization/OwnerOnlyGateModal';
import { WorkspaceAvatar } from '@/components/organization/WorkspaceAvatar';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import CreateWorkspaceModal from '@/components/organization/CreateWorkspaceModal';
import { Conversation } from '@/lib/api/conversationApi';
import { useRouter, usePathname } from '@/i18n/navigation';
import { useTranslations, useLocale } from 'next-intl';
import { useSafeNavigate } from '@/contexts/NavigationGuardContext';
import { triggerSidebarNavigation } from '@/components/NavigationLoader';
import { memo } from 'react';
import { LucideIcon } from 'lucide-react';
import { IS_CE } from '@/lib/edition';
import { cloudLinkService, CLOUD_NO_SUBSCRIPTION } from '@/lib/api/cloud-link.service';

interface AppSidebarProps {
  onConversationCreated?: (conversationId: string, title: string | null, isTemporary: boolean) => void;
  onTitleUpdated?: (conversationId: string, title: string, isTemporary: boolean) => void;
  onMarketPlaceClick?: () => void;
}

interface NavIconButtonProps {
  icon: LucideIcon;
  title: string;
  onClick: (e: React.MouseEvent) => void;
  isActive?: boolean;
}

const NavIconButton = memo(function NavIconButton({ icon: Icon, title, onClick, isActive = false }: NavIconButtonProps) {
  return (
    <Button
      onClick={onClick}
      variant="ghostGray"
      size="icon"
      className={`w-8 h-8 ${isActive ? 'bg-surface-hover' : ''}`}
      title={title}
    >
      <Icon className="w-5 h-5" />
    </Button>
  );
});


// Chat navigation items with view mapping for active state
// Titles are translation keys resolved at render time via sidebar.nav.*
const chatNavItems = [
  { icon: Store, titleKey: 'marketplace' as const, path: '/app/marketplace', view: 'marketplace' as const },
  { icon: Columns3, titleKey: 'board' as const, path: '/app/board', view: 'board' as const },
  { icon: Bot, titleKey: 'agents' as const, path: '/app/agent', view: 'agent' as const },
  { icon: AppWindow, titleKey: 'applications' as const, path: '/app/applications', view: 'applications' as const },
  { icon: Workflow, titleKey: 'workflows' as const, path: '/app/workflow', view: 'workflow' as const },
  { icon: Monitor, titleKey: 'interfaces' as const, path: '/app/interface', view: 'interface' as const },
  { icon: Table, titleKey: 'tables' as const, path: '/app/tables', view: 'data' as const },
  { icon: Folder, titleKey: 'files' as const, path: '/app/files', view: 'files' as const },
];

export const AppSidebar = memo(function AppSidebar({
  onConversationCreated,
  onTitleUpdated,
  onMarketPlaceClick,
}: AppSidebarProps = {}) {
  const t = useTranslations('sidebar');
  // Use native Next.js routing with navigation guard
  const router = useRouter();
  const safeNavigate = useSafeNavigate();
  const pathname = usePathname();
  const { view: currentView, conversationId: urlConversationId, isDetailPage } = useCurrentView();

  // Use unified context for all app state
  const appContext = useUnifiedApp();
  const { state: appState, setCurrentConversationId } = appContext;
  const streaming = useStreamingSafe();
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
  const setSidebarOpen = sidebarContext?.setOpen ?? (() => {});
  const setSidebarCollapsed = sidebarContext?.setCollapsed ?? (() => {});

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
  const subscriptionNotReady = isSubscriptionLoading || subscription === undefined;
  const hasActiveSubscription = subscriptionNotReady || !!(planCode && planCode !== 'FREE');

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
      safeNavigate(`/app/c/${conversation.id}`);
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
        appContext.setIsNavigatingToNewChat(false); // Ensure flag is cleared
      } else {
        // Coming from a conversation page - set flag and navigate
        console.log('[AppSidebar] Navigating from conversation to new chat');
        appContext.setIsNavigatingToNewChat(true);
        safeNavigate('/app/chat');
      }
    }
    if (sidebarOpen) {
      setSidebarOpen(false);
    }
  }, [safeNavigate, sidebarOpen, setSidebarOpen, setCurrentConversationId, appContext, streaming, pathname]);

  const handleSignOut = useCallback(() => {
    logout({ logoutParams: { returnTo: `${window.location.origin}/app/` } });
  }, [logout]);


  const handleSidebarToggle = useCallback(() => {
    setSidebarOpen(!sidebarOpen);
  }, [sidebarOpen, setSidebarOpen]);

  const handleLogin = useCallback(() => {
    loginWithRedirect();
  }, [loginWithRedirect]);

  // currentView already comes from useCurrentView() hook

  // Memoized collapsed icons with active state - always show chat nav items
  const collapsedIcons = useMemo(() => {
    return chatNavItems.map(item => ({
      ...item,
      title: t(`nav.${item.titleKey}`),
      isActive: currentView === item.view,
      onClick: (e: React.MouseEvent) => {
        e.stopPropagation();
        handleNavigate(item.path);
      }
    }));
  }, [handleNavigate, currentView, t]);

  // Sidebar container classes
  const sidebarClasses = useMemo(() => {
    const baseClasses = 'bg-theme-secondary transition-all duration-700 ease-in-out flex-shrink-0 overflow-hidden';
    const widthClasses = sidebarOpen ? 'w-64' : 'w-0';
    const collapsedWidthClasses = sidebarCollapsed ? 'md:w-16' : 'md:w-64';
    const positionClasses = sidebarOpen ? 'absolute md:relative z-[60] h-full' : 'hidden md:block';
    return `${widthClasses} ${collapsedWidthClasses} ${baseClasses} ${positionClasses}`;
  }, [sidebarOpen, sidebarCollapsed]);

  return (
    <>
      <div className={sidebarClasses}>
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
                    variant="ghostGray"
                    size="icon"
                    className="w-8 h-8 hidden md:flex items-center justify-center opacity-0 group-hover/header:opacity-100 transition-opacity duration-300 absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 z-10"
                    title={t('expandSidebar')}
                  >
                    <PanelLeft className="w-5 h-5" />
                  </Button>
                ) : (
                  <Button
                    onClick={() => setSidebarCollapsed(true)}
                    variant="ghostGray"
                    size="icon"
                    className="w-8 h-8 mr-1 hidden md:flex items-center justify-center group -ml-1 text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal"
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
              <div className={`hidden md:flex flex-col justify-center items-center gap-2 transition-all duration-300 ease-in-out ${sidebarCollapsed
                ? 'opacity-100 scale-100 mt-4'
                : 'opacity-0 scale-95 h-0 overflow-hidden pointer-events-none'
                }`}>
                {/* Home icon - opens the new-chat landing page (above Marketplace) */}
                <NavIconButton
                  key="home"
                  icon={Home}
                  title={t('nav.home')}
                  isActive={currentView === 'chat' && !isDetailPage && !currentConversationId}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleConversationSelect(null);
                  }}
                />
                {collapsedIcons.map((item) => (
                  <NavIconButton
                    key={item.path}
                    icon={item.icon}
                    title={item.title}
                    onClick={item.onClick}
                    isActive={item.isActive}
                  />
                ))}
                {/* New Chat icon */}
                <NavIconButton
                  key="new-chat"
                  icon={MessageCircle}
                  title={t('nav.newChat')}
                  isActive={currentView === 'chat' && !isDetailPage && !currentConversationId}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleConversationSelect(null);
                  }}
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
              onNewChat={() => handleConversationSelect(null)}
              onSearchClick={() => {
                setIsSearchModalOpen(true);
                if (sidebarOpen) handleSidebarToggle();
              }}
              onSearchWorkflows={() => console.log('Search workflows')}
              onSearchDataSources={() => console.log('Search data sources')}
              onMarketPlaceClick={() => handleNavigate('/app/marketplace')}
              onSignOut={handleSignOut}
              user={user}
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
              <UserSection
                sidebarCollapsed={sidebarCollapsed && !sidebarOpen}
                user={user}
                avatarUrl={avatarUrl}
                numericUserId={numericUserId}
                hasActiveSubscription={IS_CE ? false : hasActiveSubscription}
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
            ) : (
              <SignInSection
                sidebarCollapsed={sidebarCollapsed && !sidebarOpen}
                onLogin={handleLogin}
              />
            )}
          </div>
        </div>
      </div>

      {/* Mobile Sidebar Overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/60 z-40 md:hidden"
          onClick={handleSidebarToggle}
        />
      )}

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
  hasActiveSubscription: boolean;
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

export const UserSection = memo(function UserSection({
  sidebarCollapsed,
  user,
  avatarUrl,
  numericUserId,
  hasActiveSubscription,
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
  const locale = useLocale();
  const router = useRouter();
  const pathname = usePathname();
  // Build version + update status for the About menu entry (shared ['app-version'] query).
  const { version: appVersion } = useAppVersion();
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
  const showUpgrade = IS_CE ? !isInstallCloudLinked : !hasActiveSubscription;
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
    // Group 1: Settings, Pricing & Credits/Usage. The quota entry exists in BOTH editions:
    // cloud shows the credit ledger, CE shows the $-denominated CeQuotaPage (local ledger
    // + mirrored cloud spend when linked).
    [
      { icon: User, label: t('settings'), onClick: () => { onNavigate('/app/settings/overview'); setShowMenu(false); } },
      { icon: CreditCard, label: t('pricing'), onClick: () => { onNavigate('/app/settings/pricing'); setShowMenu(false); } },
      { icon: Coins, label: IS_CE ? t('cost') : t('credits'), onClick: () => { onNavigate('/app/settings/quota'); setShowMenu(false); } },
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
    // Group 3: Information, Language & Theme
    [
      { icon: Info, label: t('about'), isAbout: true, onClick: () => { onNavigate('/app/settings/information'); setShowMenu(false); } },
      { icon: Globe, label: currentLanguageLabel, isLanguage: true, onClick: () => { setShowLanguageSubmenu(!showLanguageSubmenu); } },
      { icon: selectedThemeOption.icon, label: selectedThemeOption.label, isTheme: true, onClick: () => { setShowThemeSubmenu(!showThemeSubmenu); } },
    ],
    // Group 4: Sign Out (at bottom)
    [
      { icon: LogOut, label: t('signOut'), onClick: () => { onSignOut(); setShowMenu(false); } },
    ],
  ];

  const menuContent = showMenu && mounted ? createPortal(
    <div
      ref={menuRef}
      className="fixed z-[9999] bg-theme-primary rounded-2xl p-2 border border-gray-300/70 dark:border-gray-600/70"
      style={{
        top: `${menuPosition.top}px`,
        left: `${menuPosition.left}px`,
        // Min 240px so the menu keeps the expanded width even when the sidebar is
        // collapsed (the collapsed avatar button is ~32px wide; without this the
        // menu would shrink to a narrow popup).
        width: `${Math.max(menuPosition.width, 240)}px`,
        transform: 'translateY(-100%)'
      }}
    >
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
                    {item.isAbout && <AboutMenuVersion version={appVersion} />}
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
                              : 'text-theme-secondary hover:bg-gray-50 dark:hover:bg-gray-800/50'
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
                                className="text-xs px-2 py-0.5 rounded-md border border-theme hover:bg-gray-50 dark:hover:bg-gray-800/50 disabled:opacity-50 cursor-pointer"
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
                                : 'text-theme-secondary hover:bg-gray-50 dark:hover:bg-gray-800/50'
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
                        className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors text-sm text-theme-secondary hover:bg-gray-50 dark:hover:bg-gray-800/50"
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
                                : 'text-theme-secondary hover:bg-gray-50 dark:hover:bg-gray-800/50'
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
          {!isCreditBalanceLoading && creditBalance !== null && creditBalance !== undefined && (
            <BalanceBreakdownTooltip subBalance={creditSubBalance ?? null} paygBalance={creditPaygBalance ?? null}>
              <button
                onClick={(e) => { e.stopPropagation(); onNavigate('/app/settings/quota'); }}
                className="flex items-center gap-0.5 text-xs text-theme-muted hover:text-theme-primary transition-colors cursor-pointer"
                title={`${IS_CE ? t('cost') : t('credits')}: ${formatCredits(creditBalance)}`}
              >
                <Coins className="h-3 w-3" />
                <span className="text-[10px]">{formatCredits(creditBalance)}</span>
              </button>
            </BalanceBreakdownTooltip>
          )}
          <button
            ref={buttonRef}
            onClick={(e) => { e.stopPropagation(); setShowMenu(!showMenu); }}
            className="w-8 h-8 p-0 rounded-full flex items-center justify-center cursor-pointer hover:ring-2 hover:ring-[var(--accent-primary)] transition-all"
            title={finalDisplayName}
          >
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
                {(avatarUrl || user?.picture) ? (
                  <img
                    src={avatarUrl || user.picture}
                    alt={finalDisplayName}
                    className="w-8 h-8 rounded-full border border-theme object-cover flex-shrink-0 mr-2"
                  />
                ) : (
                  // No photo: canonical user avatar (server-generated initials SVG),
                  // not a generic person icon - same letters as the public profile.
                  <div className="mr-2 flex-shrink-0">
                    <PublisherAvatar userId={numericUserId} name={finalDisplayName} size={32} variant="overlay" />
                  </div>
                )}
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
                    ) : showUpgrade ? (
                      <>
                        <span className="text-xs text-theme-muted">
                          {displayPlanName}
                        </span>
                        <span
                          role="link"
                          onClick={(e) => { e.stopPropagation(); onNavigate('/app/settings/pricing'); }}
                          className="text-xs px-2 py-0.5 rounded-full bg-black dark:bg-white text-white dark:text-black hover:bg-black/90 dark:hover:bg-white/90 font-medium transition-all cursor-pointer"
                        >
                          {t('upgrade')}
                        </span>
                      </>
                    ) : (
                      <span className="text-xs text-theme-muted">
                        {displayPlanName}
                      </span>
                    )}
                    {/* Credit balance indicator - owner-pays (ADR-009):
                        backend resolves payer, returned balance IS the owner's
                        wallet for guests, executor's wallet for solo users. */}
                    {!isCreditBalanceLoading && creditBalance !== null && creditBalance !== undefined && (
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
                    )}
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
          variant="contrast"
          className="w-8 h-8 p-0 rounded-full flex items-center justify-center shadow-none hover:shadow-none"
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
        variant="contrast"
        className="w-full justify-start shadow-none hover:shadow-none"
      >
        <User className="w-4 h-4" />
        {t('signIn')}
      </Button>
    </div>
  );
});
