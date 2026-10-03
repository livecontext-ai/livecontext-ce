"use client";

import React, { useState, useEffect, useMemo, useCallback } from "react";
import { useSearchParams } from "next/navigation";
import { Link, useRouter, usePathname } from "@/i18n/navigation";
import { useLocale, useTranslations } from "next-intl";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { urlEnum, useUrlState } from "@/hooks/useUrlState";
import { useAuth } from "@/lib/providers/smart-providers";
import { useUserProfile } from "@/hooks/useUserProfile";
import { useTheme, type ThemePreference } from "@/components/ThemeProvider";
import { useSidePanelLayoutSafe, type SidePanelBottomMode, type SidePanelDefaultPosition } from "@/contexts/SidePanelLayoutContext";
import { useWorkflowLayoutDirection, type WorkflowLayoutDirection } from "@/contexts/WorkflowLayoutDirectionContext";
import { useInspectorDock, type InspectorDock } from "@/contexts/InspectorDockContext";
import { useInspectorOpenMode, type InspectorOpenMode } from '@/contexts/InspectorOpenModeContext';
import { useSubscription } from "@/lib/hooks/smart-hooks-complete";
import { OverviewPageSkeleton } from "@/components/skeletons";
import { ScheduledChangeAlert } from "@/components/billing";
import { PublicProfileSettingsCard } from "@/components/profile/PublicProfileSettingsCard";
import { BadgeCollection } from "@/components/badges/BadgeCollection";
import { NotificationPreferencesPanel } from "@/components/settings/NotificationPreferencesPanel";
import { MarketingConsentSetting } from "@/components/settings/MarketingConsentSetting";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { formatUtcDate } from "@/lib/utils/dateFormatters";
import {
  User,
  Bell,
  Shield,
  Palette,
  Eye,
  EyeOff,
  Settings,
  Trash2,
  Pencil,
  Check,
  X,
  CheckCircle2,
  ChevronRight,
  Trophy,
} from "lucide-react";
import { InfoPopover } from "@/components/ui/info-popover";
import { AvatarGallery } from "@/components/settings/AvatarGallery";
import { TimeZonePreferenceRow } from "@/components/settings/TimeZonePreferenceRow";
import { unifiedApiService } from "@/lib/api/unified-api-service";
import { IS_CE, IS_CLOUD } from "@/lib/edition";
import { reportExplicitLocaleChoice } from "@/lib/lifecycle/localeChoice";
import { embeddedChangePassword } from "@/lib/providers/embedded-auth-provider";
import { evaluatePasswordChange } from "@/lib/auth/changePasswordOutcome";
import { isFederatedAccount, isOrganizationSamlAccount, securityTabSections } from "@/lib/utils/userUtils";
import { TwoFactorSettingsCard } from "@/components/settings/TwoFactorSettingsCard";
import { PageHeader, SettingRow } from "@/components/settings";
import { AdaptiveTabBar } from "@/components/settings/AdaptiveTabBar";
import { writeLocaleCookie } from "@/lib/utils/locale";

interface Preferences {
  language: string;
}

/**
 * Format plan code to display name
 */
function formatPlanName(planCode: string | undefined): string {
  if (!planCode || planCode === 'FREE') return 'Free';
  if (planCode === 'STARTER') return 'Starter';
  if (planCode === 'PRO') return 'Pro';
  if (planCode.startsWith('ENTERPRISE_')) {
    const tier = planCode.replace('ENTERPRISE_', '');
    return `Enterprise ${tier.charAt(0) + tier.slice(1).toLowerCase()}`;
  }
  if (planCode === 'ENTERPRISE') return 'Enterprise';
  return planCode;
}

// Every tab id this page can show. An id the address carries and this list does not falls back
// to the profile tab.
const OVERVIEW_TAB_IDS = ["profile", "security", "trophies", "preferences", "notifications", "advanced"] as const;

export default function SettingsOverviewPage() {
  const { user, isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect, logout } = useAuth();
  const {
    profile: userProfile,
    isLoading: profileLoading,
    updateUserProfile,
    fetchUserProfile,
  } = useUserProfile();
  const { subscription, isLoading: isSubLoading, forceLoadSubscription } = useSubscription();
  const [refreshScheduledChange, setRefreshScheduledChange] = useState(0);

  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const currentLocale = useLocale();
  const t = useTranslations('settings');

  // Extract plan info from subscription
  const planCode = (subscription as any)?.subscription?.planCode || null;
  const planName = formatPlanName(planCode);
  const isPaid = planCode && planCode !== 'FREE';

  const memberSince = useMemo(() => {
    if (!user?.updated_at && !user?.created_at) return 'N/A';
    return formatUtcDate(user.updated_at || user.created_at);
  }, [user]);

  // App theme (light / dark / auto) - shown in General Preferences below the language.
  const { themePreference, setTheme } = useTheme();

  // Side-panel layout preferences - shown below the theme. `defaultPosition` picks
  // where the panel opens BY DEFAULT across the app (right / bottom); the two header
  // dock buttons still override the active dock live. `bottomMode` picks which bottom
  // variant (content-width or full-width) the bottom dock uses.
  const {
    defaultPosition: sidePanelDefaultPosition,
    setDefaultPosition: setSidePanelDefaultPosition,
    bottomMode: sidePanelBottomMode,
    setBottomMode: setSidePanelBottomMode,
  } = useSidePanelLayoutSafe();

  // Workflow canvas reading direction. The THROWING hook on purpose: this page is
  // mounted under WorkflowLayoutDirectionProvider (app/[locale]/app/layout.tsx), so
  // a missing provider is a bug we want loud. The safe variant would degrade the
  // select into a no-op that silently discards the user's choice.
  // The stored DEFAULT, not the active direction. This page describes what a workflow
  // starts from; reading `direction` meant that after opening a workflow whose plan stamps
  // a direction, this select displayed that workflow's direction as the user's default -
  // and being a controlled select, it could not even be used to re-pick the value it was
  // showing, so there was no way to state the default it was misreporting.
  const { defaultDirection: workflowLayoutDirection, setDirection: setWorkflowLayoutDirection } =
    useWorkflowLayoutDirection();

  // Where the node inspector opens. Throwing hook for the same reason as the one
  // above: this page is mounted under InspectorDockProvider, so a missing provider
  // is a bug, not a reason to silently drop the user's choice. The very same value
  // is exposed from a workflow's own canvas settings - one preference, two places
  // to reach it.
  const { dock: inspectorDock, setDock: setInspectorDock } = useInspectorDock();
  // Same shape and same contract as the dock above: one stored value, written from here
  // and from each workflow's canvas settings.
  const { openMode: inspectorOpenMode, setOpenMode: setInspectorOpenMode } = useInspectorOpenMode();

  // All useState hooks first
  // The open tab lives in the address (`?tab=`), so a reload or a shared link reopens it.
  const [activeTab, setActiveTab] = useUrlState<string>("tab", "profile", {
    codec: urlEnum(OVERVIEW_TAB_IDS),
    history: "push",
  });
  const [showPassword, setShowPassword] = useState(false);
  const [showNewPassword, setShowNewPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  // Grace window before the account is actually purged. Read from the backend when the
  // dialog opens rather than hardcoded, so the number in the copy is the same one the
  // purge scheduler acts on. The 30 is only the value shown if that read fails.
  const [gracePeriodDays, setGracePeriodDays] = useState(30);
  const [deleteConfirmation, setDeleteConfirmation] = useState("");
  const [isDeleting, setIsDeleting] = useState(false);
  const [passwordForm, setPasswordForm] = useState({
    currentPassword: "",
    newPassword: "",
    confirmPassword: "",
  });
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordMessage, setPasswordMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [preferences, setPreferences] = useState<Preferences>({
    language: currentLocale,
  });

  // Sync language preference when locale changes
  useEffect(() => {
    setPreferences(prev => ({ ...prev, language: currentLocale }));
  }, [currentLocale]);

  // Display name editing state
  const [displayNameEditing, setDisplayNameEditing] = useState(false);
  const [displayNameValue, setDisplayNameValue] = useState("");
  const [displayNameSaving, setDisplayNameSaving] = useState(false);
  const [displayNameMessage, setDisplayNameMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [canChangeDisplayName, setCanChangeDisplayName] = useState(true);
  const [nextChangeDate, setNextChangeDate] = useState<string | null>(null);
  const [checkingDisplayName, setCheckingDisplayName] = useState(false);
  const [displayNameAvailable, setDisplayNameAvailable] = useState(false);
  const [displayNameError, setDisplayNameError] = useState<string | null>(null);

  // Debounced display name availability check
  const checkDisplayName = useCallback(async (name: string) => {
    if (!name || name.trim().length < 3) {
      setDisplayNameError(null);
      setDisplayNameAvailable(false);
      return;
    }
    // Skip check if name hasn't changed from current
    const currentName = userProfile?.displayName || userProfile?.username;
    if (name.trim() === currentName) {
      setDisplayNameError(null);
      setDisplayNameAvailable(true);
      return;
    }
    setCheckingDisplayName(true);
    try {
      const response = await unifiedApiService.checkDisplayName(name.trim());
      if (!response.available) {
        setDisplayNameError(t('profile.displayNameTaken'));
        setDisplayNameAvailable(false);
      } else {
        setDisplayNameError(null);
        setDisplayNameAvailable(true);
      }
    } catch {
      setDisplayNameError(t('profile.displayNameCheckError'));
      setDisplayNameAvailable(false);
    } finally {
      setCheckingDisplayName(false);
    }
  }, [t, userProfile?.displayName, userProfile?.username]);

  useEffect(() => {
    if (!displayNameEditing || !displayNameValue.trim()) return;
    const timer = setTimeout(() => {
      checkDisplayName(displayNameValue);
    }, 500);
    return () => clearTimeout(timer);
  }, [displayNameValue, displayNameEditing, checkDisplayName]);

  // Fetch display name change status
  const fetchDisplayNameStatus = useCallback(async () => {
    try {
      const status = await unifiedApiService.getDisplayNameStatus();
      setCanChangeDisplayName(status.canChange);
      setNextChangeDate(status.nextChangeDate);
    } catch {
      // Silently fail - assume can change
    }
  }, []);

  useEffect(() => {
    if (isAuthenticated) {
      fetchDisplayNameStatus();
    }
  }, [isAuthenticated, fetchDisplayNameStatus]);

  // Federated accounts (social login OR org SAML/SSO) manage their password at the
  // upstream provider, so the local Security/password tab doesn't apply to them.
  const isExternalAccount = isFederatedAccount(user);
  // Password block and/or two-factor card (see securityTabSections for who gets which).
  const securitySections = securityTabSections(user, IS_CLOUD);
  const showTwoFactor = securitySections.twoFactor;
  const showSecurityTab = securitySections.show;

  const tabs = [
    { id: "profile", label: t('tabs.profile'), icon: User },
    ...(showSecurityTab
      ? [{ id: "security", label: t('tabs.security'), icon: Shield }]
      : []),
    { id: "trophies", label: t('tabs.trophies'), icon: Trophy },
    { id: "preferences", label: t('tabs.preferences'), icon: Palette },
    { id: "notifications", label: t('tabs.notifications'), icon: Bell },
    { id: "advanced", label: t('tabs.advanced'), icon: Settings },
  ];

  // Redirect to profile if active tab no longer exists (e.g., Security removed for federated accounts)
  useEffect(() => {
    const availableTabIds = tabs.map((tab) => tab.id);
    if (!availableTabIds.includes(activeTab)) {
      setActiveTab("profile");
    }
  }, [tabs, activeTab, setActiveTab]);

  // Show skeleton only during initial auth check - content appears progressively
  // Use isAuthChecking (not isLoading) for faster UI rendering
  if (isAuthChecking) {
    return (
      <div className="space-y-8">
        <div className="mx-auto max-w-4xl">
          <OverviewPageSkeleton />
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
                {t('unauthorized')}
              </h1>
              <p className="text-theme-secondary mb-6">
                {t('mustBeLoggedIn')}
              </p>
              <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                <User className="w-4 h-4 mr-1" />
                {t('signIn')}
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const handleDisplayNameEdit = () => {
    setDisplayNameValue(userProfile?.displayName || userProfile?.username || "");
    setDisplayNameEditing(true);
    setDisplayNameMessage(null);
    setDisplayNameError(null);
    setDisplayNameAvailable(false);
  };

  const handleDisplayNameCancel = () => {
    setDisplayNameEditing(false);
    setDisplayNameMessage(null);
    setDisplayNameError(null);
    setDisplayNameAvailable(false);
  };

  const handleDisplayNameSave = async () => {
    if (!displayNameValue.trim() || displayNameValue.trim().length < 3) {
      return;
    }

    setDisplayNameSaving(true);
    setDisplayNameMessage(null);

    try {
      await unifiedApiService.updateUserProfile({ displayName: displayNameValue.trim() });
      setDisplayNameEditing(false);
      setDisplayNameMessage({ type: 'success', text: t('profile.displayNameChanged') });
      fetchDisplayNameStatus();
      // Refresh profile data
      if (typeof fetchUserProfile === 'function') {
        fetchUserProfile();
      }
    } catch (err: any) {
      if (err?.status === 429 || err?.response?.status === 429) {
        setDisplayNameMessage({ type: 'error', text: t('profile.displayNameCooldown') });
      } else {
        setDisplayNameMessage({ type: 'error', text: t('profile.displayNameError') });
      }
    } finally {
      setDisplayNameSaving(false);
    }
  };

  // CE-only: changes the embedded-auth password via POST /api/auth/change-password.
  // In Cloud the form is not rendered (password lives in Keycloak - see the Security
  // tab's IS_CLOUD branch, which redirects to the kc_action=UPDATE_PASSWORD flow).
  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordMessage(null);
    setPasswordSaving(true);
    const outcome = await evaluatePasswordChange(passwordForm, embeddedChangePassword);
    setPasswordSaving(false);

    switch (outcome) {
      case 'mismatch':
        setPasswordMessage({ type: 'error', text: t('security.passwordMismatch') });
        return;
      case 'too_short':
        setPasswordMessage({ type: 'error', text: t('security.passwordTooShort') });
        return;
      case 'wrong_current':
        setPasswordMessage({ type: 'error', text: t('security.currentPasswordIncorrect') });
        return;
      case 'error':
        setPasswordMessage({ type: 'error', text: t('security.passwordChangeError') });
        return;
      case 'success':
        setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
        setPasswordMessage({ type: 'success', text: t('security.passwordChangeSuccess') });
        // The backend revoked all refresh tokens - sign out so the user re-authenticates
        // with the new password instead of hitting a silent refresh failure later.
        setTimeout(() => { logout(); }, 1800);
        return;
    }
  };

  return (
    <div className="space-y-8">
      <div className="mx-auto max-w-4xl space-y-8">
        {/* Scheduled Change Alert */}
        <ScheduledChangeAlert
          key={refreshScheduledChange}
          onCancel={() => {
            forceLoadSubscription();
          }}
          onRefresh={() => setRefreshScheduledChange(prev => prev + 1)}
        />

        {/* ===== SETTINGS TABS SECTION ===== */}
        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <AdaptiveTabBar
            className="mb-6"
            tabs={tabs.map((tab) => ({ id: tab.id, label: tab.label, icon: <tab.icon className="w-4 h-4" /> }))}
            value={activeTab}
            onChange={setActiveTab}
          />

          {/* Profile Tab */}
          <TabsContent value="profile" className="space-y-6">
            <div className="flex flex-col gap-8">
              {/* Account Information */}
              <div className="space-y-6">
                <PageHeader icon={User} title={t('profile.accountInfo')} subtitle={t('profile.accountInfoDesc')} headingLevel="h2" />

                {/* Avatar Gallery */}
                <AvatarGallery />

                <div className="space-y-4">
                  <div className="space-y-2">
                    <div className="flex items-center gap-1.5">
                      <Label>{t('profile.displayName')}</Label>
                      <InfoPopover label={t('profile.displayName')}>
                        {!canChangeDisplayName && nextChangeDate
                          ? t('profile.displayNameCooldownMessage', { date: formatUtcDate(nextChangeDate) })
                          : t('profile.displayNameCooldownInfo')}
                      </InfoPopover>
                    </div>
                    {displayNameEditing ? (
                      <div className="space-y-2">
                        <div className="relative">
                          <Input
                            value={displayNameValue}
                            onChange={(e) => {
                              const sanitized = e.target.value.replace(/[^a-zA-ZÀ-ÿ0-9\s\-_]/g, '');
                              setDisplayNameValue(sanitized);
                            }}
                            disabled={displayNameSaving}
                            maxLength={30}
                            minLength={3}
                            autoFocus
                            placeholder={t('profile.displayNamePlaceholder')}
                            className={cn("pr-10",
                              displayNameError
                                ? "border-red-500 focus-visible:ring-red-500"
                                : displayNameAvailable && displayNameValue.trim().length >= 3
                                  ? "border-emerald-500 focus-visible:ring-emerald-500"
                                  : ""
                            )}
                          />
                          <div className="absolute right-3 top-1/2 -translate-y-1/2">
                            {(checkingDisplayName || displayNameSaving) && (
                              <div className="h-4 w-4 border-2 border-theme-muted border-t-theme-primary rounded-full animate-spin" />
                            )}
                            {displayNameAvailable && !checkingDisplayName && !displayNameSaving && displayNameValue.trim().length >= 3 && (
                              <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                            )}
                          </div>
                        </div>
                        {displayNameError ? (
                          <p className="text-xs text-red-600 dark:text-red-400">{displayNameError}</p>
                        ) : (
                          <p className="text-xs text-theme-muted">{t('profile.displayNameHint')}</p>
                        )}
                        <div className="flex items-center gap-2">
                          <Button
                            size="sm"
                            onClick={handleDisplayNameSave}
                            disabled={displayNameSaving || checkingDisplayName || !displayNameAvailable || displayNameValue.trim().length < 3}
                            className="h-8 px-3"
                          >
                            <Check className="h-3.5 w-3.5 mr-1" />
                            {t('common.save')}
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={handleDisplayNameCancel}
                            disabled={displayNameSaving}
                            className="h-8 px-3"
                          >
                            {t('common.cancel')}
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <div className="flex items-center gap-2">
                        <Input
                          value={profileLoading ? t('common.loading') : (userProfile?.displayName || userProfile?.username || t('common.notProvided'))}
                          disabled
                          className="flex-1 bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground"
                        />
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={handleDisplayNameEdit}
                          disabled={profileLoading || !canChangeDisplayName}
                          className="h-9 w-9 shrink-0"
                          title={!canChangeDisplayName && nextChangeDate
                            ? t('profile.displayNameCooldownMessage', { date: formatUtcDate(nextChangeDate) })
                            : undefined}
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    )}
                    {displayNameMessage && (
                      <p className={cn("text-xs mt-1", displayNameMessage.type === 'success' ? "text-green-600" : "text-red-600")}>
                        {displayNameMessage.text}
                      </p>
                    )}
                  </div>
                  <div className="space-y-2">
                    <Label>{t('profile.email')}</Label>
                    <Input
                      value={user?.email || t('common.notProvided')}
                      disabled
                      className="bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>{t('profile.accountType')}</Label>
                    <Input
                      value={user?.identity_provider === 'google'
                        ? "Google"
                        : user?.identity_provider === 'github'
                          ? "GitHub"
                          : user?.identity_provider === 'microsoft'
                            ? "Microsoft"
                            : user?.identity_provider === 'facebook'
                              ? "Facebook"
                              : isOrganizationSamlAccount(user)
                                ? t('profile.ssoAccount')
                                : t('profile.localAccount')}
                      disabled
                      className="bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground"
                    />
                  </div>
                </div>
              </div>

              {/* Public profile - bio / website / social links / visibility */}
              <PublicProfileSettingsCard />
            </div>
          </TabsContent>

          {/* Security Tab */}
          {showSecurityTab && (
            <TabsContent value="security" className="space-y-6">
              <div className="space-y-6">
                {/* The header titles the password block ("Change Password"), so a social
                    account, whose tab holds only the two-factor card, does not get it. */}
                {!isExternalAccount && (
                  <PageHeader icon={Shield} title={t('security.title')} subtitle={t('security.description')} headingLevel="h2" />
                )}

                {/* Cloud (Keycloak) owns the password - redirect to the
                    kc_action=UPDATE_PASSWORD Application-Initiated Action. CE wires the
                    form below to POST /api/auth/change-password. */}
                {isExternalAccount ? null : IS_CLOUD ? (
                  <div className="rounded-lg border border-theme bg-theme-tertiary p-6 space-y-4">
                    <p className="text-sm text-theme-secondary">{t('security.cloudManagedDescription')}</p>
                    <Button
                      type="button"
                      size="sm"
                      className="h-8 px-3"
                      onClick={() =>
                        loginWithRedirect({ authorizationParams: { kc_action: 'UPDATE_PASSWORD' } })
                      }
                    >
                      <Shield className="w-4 h-4 mr-1" />
                      <span>{t('security.changePasswordRedirect')}</span>
                    </Button>
                  </div>
                ) : (
                <form
                  onSubmit={handlePasswordSubmit}
                  className="space-y-6"
                >
                  <div className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="currentPassword">
                        {t('security.currentPassword')}
                      </Label>
                      <div className="relative">
                        <Input
                          id="currentPassword"
                          type={showPassword ? "text" : "password"}
                          value={passwordForm.currentPassword}
                          onChange={(e) =>
                            setPasswordForm({
                              ...passwordForm,
                              currentPassword: e.target.value,
                            })
                          }
                          placeholder={t('security.currentPasswordPlaceholder')}
                          className="pr-12"
                        />
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setShowPassword(!showPassword)}
                          className="absolute right-2 top-1/2 -translate-y-1/2"
                        >
                          {showPassword ? (
                            <EyeOff className="w-4 h-4" />
                          ) : (
                            <Eye className="w-4 h-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="newPassword">{t('security.newPassword')}</Label>
                      <div className="relative">
                        <Input
                          id="newPassword"
                          type={showNewPassword ? "text" : "password"}
                          value={passwordForm.newPassword}
                          onChange={(e) =>
                            setPasswordForm({
                              ...passwordForm,
                              newPassword: e.target.value,
                            })
                          }
                          placeholder={t('security.newPasswordPlaceholder')}
                          className="pr-12"
                        />
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() =>
                            setShowNewPassword(!showNewPassword)
                          }
                          className="absolute right-2 top-1/2 -translate-y-1/2"
                        >
                          {showNewPassword ? (
                            <EyeOff className="w-4 h-4" />
                          ) : (
                            <Eye className="w-4 h-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="confirmPassword">
                        {t('security.confirmPassword')}
                      </Label>
                      <div className="relative">
                        <Input
                          id="confirmPassword"
                          type={showConfirmPassword ? "text" : "password"}
                          value={passwordForm.confirmPassword}
                          onChange={(e) =>
                            setPasswordForm({
                              ...passwordForm,
                              confirmPassword: e.target.value,
                            })
                          }
                          placeholder={t('security.confirmPasswordPlaceholder')}
                          className="pr-12"
                        />
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() =>
                            setShowConfirmPassword(!showConfirmPassword)
                          }
                          className="absolute right-2 top-1/2 -translate-y-1/2"
                        >
                          {showConfirmPassword ? (
                            <EyeOff className="w-4 h-4" />
                          ) : (
                            <Eye className="w-4 h-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                  </div>
                  {passwordMessage && (
                    <p className={cn("text-sm", passwordMessage.type === 'success' ? "text-green-600" : "text-red-600")}>
                      {passwordMessage.text}
                    </p>
                  )}
                  <div className="flex justify-end">
                    <Button type="submit" size="sm" disabled={passwordSaving} className="h-8 px-3">
                      <Shield className="w-4 h-4 mr-1" />
                      <span>{passwordSaving ? t('security.changing') : t('security.updatePassword')}</span>
                    </Button>
                  </div>
                </form>
                )}

                {showTwoFactor && <TwoFactorSettingsCard standalone={isExternalAccount} />}
              </div>
            </TabsContent>
          )}

          {/* Trophies Tab - the user's badge wall. Mounted lazily by Radix's
              TabsContent, so the badges query (which evaluates server-side)
              only fires once the user actually opens the tab. */}
          <TabsContent value="trophies" className="space-y-6">
            <BadgeCollection />
          </TabsContent>

          {/* Preferences Tab */}
          <TabsContent value="preferences" className="space-y-6">
            <div className="space-y-6">
              <PageHeader icon={Palette} title={t('preferences.title')} subtitle={t('preferences.description')} headingLevel="h2" />

              <div className="space-y-6">
                <SettingRow title={t('preferences.language')} description={t('preferences.languageDescription')}>
                    <Select
                      value={preferences.language}
                      onValueChange={(value) => {
                        setPreferences({ ...preferences, language: value });
                        // Persist language preference in cookie (1 year)
                        writeLocaleCookie(value);
                        // Best-effort, never awaited: the switch must not wait on or fail because of it.
                        reportExplicitLocaleChoice(value);
                        // Navigate to the new locale path, preserving tab parameter
                        const tab = searchParams.get('tab');
                        const path = tab ? `${pathname}?tab=${tab}` : pathname;
                        router.push(path, { locale: value });
                      }}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder={t('preferences.selectLanguage')} />
                      </SelectTrigger>
                      <SelectContent>
                        {/* Every supported locale (i18n/routing.ts), native names - same list as
                            the sidebar language menu and the landing footer select. */}
                        <SelectItem value="en">English</SelectItem>
                        <SelectItem value="fr">Français</SelectItem>
                        <SelectItem value="es">Español</SelectItem>
                        <SelectItem value="de">Deutsch</SelectItem>
                        <SelectItem value="pt">Português</SelectItem>
                        <SelectItem value="zh">中文</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* Display time zone - stored on the ACCOUNT (auth.users.time_zone), unlike the
                    theme below: it decides how every date in the product reads AND how the
                    emails the backend sends are timed, so it has to follow the person to their
                    other devices and be readable by a service hours later. */}
                <TimeZonePreferenceRow />

                {/* Theme - persisted by ThemeProvider (localStorage), 'auto' follows the OS. */}
                <SettingRow title={t('preferences.theme')} description={t('preferences.themeDescription')}>
                    <Select
                      value={themePreference}
                      onValueChange={(value) => setTheme(value as ThemePreference)}
                    >
                      <SelectTrigger className="w-full" data-testid="theme-select">
                        <SelectValue placeholder={t('preferences.selectTheme')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="light">{t('preferences.themeLight')}</SelectItem>
                        <SelectItem value="dark">{t('preferences.themeDark')}</SelectItem>
                        <SelectItem value="auto">{t('preferences.themeAuto')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* Default side-panel opening position - persisted client-side
                    (localStorage), scoped per workspace. Picks where the panel opens
                    BY DEFAULT everywhere in the app: 'right' (docks right, resizes
                    width) or 'bottom' (docks under the content, using the bottom style
                    below). The two header dock buttons still override the active dock
                    live for the current session. */}
                <SettingRow title={t('preferences.sidePanelDefaultPosition')} description={t('preferences.sidePanelDefaultPositionDescription')}>
                    <Select
                      value={sidePanelDefaultPosition}
                      onValueChange={(value) => setSidePanelDefaultPosition(value as SidePanelDefaultPosition)}
                    >
                      <SelectTrigger className="w-full" data-testid="side-panel-default-position-select">
                        <SelectValue placeholder={t('preferences.sidePanelDefaultPosition')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="right">{t('preferences.sidePanelDefaultPositionRight')}</SelectItem>
                        <SelectItem value="bottom">{t('preferences.sidePanelDefaultPositionBottom')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* Workflow reading direction - persisted client-side (localStorage),
                    scoped per workspace like the dock preference above. Drives the
                    builder canvas as a whole: which edges of a node its connections
                    leave from, which side its buttons hang off, and the direction the
                    auto-layout button arranges the graph in. Existing workflows keep
                    their saved node positions; the choice applies to how the canvas
                    is wired and to any layout it computes from then on. */}
                <SettingRow title={t('preferences.workflowLayoutDirection')} description={t('preferences.workflowLayoutDirectionDescription')}>
                    <Select
                      value={workflowLayoutDirection}
                      onValueChange={(value) => setWorkflowLayoutDirection(value as WorkflowLayoutDirection)}
                    >
                      <SelectTrigger className="w-full" data-testid="workflow-layout-direction-select">
                        <SelectValue placeholder={t('preferences.workflowLayoutDirection')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="horizontal">{t('preferences.workflowLayoutDirectionHorizontal')}</SelectItem>
                        <SelectItem value="vertical">{t('preferences.workflowLayoutDirectionVertical')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* Where the node inspector opens - persisted client-side
                    (localStorage), scoped per workspace like the two above. The same
                    control sits in each workflow's canvas settings and writes THIS
                    value, so the two never disagree. 'panel' is a request, not a
                    guarantee: surfaces with no side panel (the standalone builder,
                    the marketplace preview) keep the floating inspector rather than
                    leave it nowhere to open. */}
                <SettingRow title={t('preferences.inspectorDock')} description={t('preferences.inspectorDockDescription')}>
                    <Select
                      value={inspectorDock}
                      onValueChange={(value) => setInspectorDock(value as InspectorDock)}
                    >
                      <SelectTrigger className="w-full" data-testid="inspector-dock-select">
                        <SelectValue placeholder={t('preferences.inspectorDock')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="canvas">{t('preferences.inspectorDockCanvas')}</SelectItem>
                        <SelectItem value="panel">{t('preferences.inspectorDockPanel')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* How much of the inspector a node click opens. Persisted client-side
                    (localStorage), scoped per workspace, and written by the same control
                    inside each workflow's canvas settings, so the two never disagree.
                    Build mode only: opening a RUN opens its results, so the three-column
                    view is not a preference there. A node with no three-column view to
                    show (an API step with no tool chosen, anything still on a navigation
                    step) stays compact on its own - a preference must not leave a node
                    unconfigurable. */}
                <SettingRow title={t('preferences.inspectorOpenMode')} description={t('preferences.inspectorOpenModeDescription')}>
                    <Select
                      value={inspectorOpenMode}
                      onValueChange={(value) => setInspectorOpenMode(value as InspectorOpenMode)}
                    >
                      <SelectTrigger className="w-full" data-testid="inspector-open-mode-select">
                        <SelectValue placeholder={t('preferences.inspectorOpenMode')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="simple">{t('preferences.inspectorOpenModeSimple')}</SelectItem>
                        <SelectItem value="advanced">{t('preferences.inspectorOpenModeAdvanced')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>

                {/* Bottom-panel style - persisted client-side (localStorage), scoped per
                    workspace. The header now carries BOTH dock buttons (bottom + right);
                    this setting only chooses which bottom variant that button opens:
                    'bottom-full' (default) spans the full window width with the sidebar
                    shrinking above it; 'bottom' stays content-width, right of the sidebar. */}
                <SettingRow title={t('preferences.sidePanelBottomMode')} description={t('preferences.sidePanelBottomModeDescription')}>
                    <Select
                      value={sidePanelBottomMode}
                      onValueChange={(value) => setSidePanelBottomMode(value as SidePanelBottomMode)}
                    >
                      <SelectTrigger className="w-full" data-testid="side-panel-bottom-mode-select">
                        <SelectValue placeholder={t('preferences.sidePanelBottomMode')} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="bottom-full">{t('preferences.sidePanelBottomModeFull')}</SelectItem>
                        <SelectItem value="bottom">{t('preferences.sidePanelBottomModeContent')}</SelectItem>
                      </SelectContent>
                    </Select>
                </SettingRow>
              </div>

              {/* Chat defaults - V312 per-(user, workspace) chat options. The EDITOR lives only
                  on the Agents page "Settings" tab, with the agents it configures. This row is
                  the signpost for anyone who still looks for it under Preferences; it reads the
                  same /v3/chat/defaults store on the other side. */}
              <SettingRow title={t('preferences.chatDefaultsTitle')} description={t('preferences.chatDefaultsDescription')}>
                {/* Wraps rather than nowrap: in French or German the label is wider than the
                    control column and spilled over the description. */}
                <div className="flex @lg:justify-end">
                  <Link
                    href="/app/agent?view=settings"
                    className="inline-flex items-center gap-1 text-sm font-medium text-[var(--accent-primary)] hover:underline @lg:text-end"
                  >
                    {t('preferences.chatDefaultsLink')}
                    <ChevronRight className="h-3.5 w-3.5" />
                  </Link>
                </div>
              </SettingRow>
            </div>
          </TabsContent>

          {/* Notifications Tab */}
          <TabsContent value="notifications" className="space-y-6">
            {/* The cloud lifecycle e-mails opt-in (marketing consent), then where each alert goes. */}
            {!IS_CE && <MarketingConsentSetting />}
            <NotificationPreferencesPanel enabled={activeTab === "notifications"} />
          </TabsContent>

          {/* Advanced Tab */}
          <TabsContent value="advanced" className="space-y-6">
            <PageHeader icon={Settings} title={t('tabs.advanced')} subtitle={t('advanced.description')} headingLevel="h2" />
            <div className="border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 rounded-lg p-6">
              <div className="flex items-start gap-3">
                <Trash2 className="w-6 h-6 shrink-0 text-red-600 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <h3 className="font-medium text-red-800 dark:text-red-200 mb-2">
                    {t('advanced.deleteTitle')}
                  </h3>
                  <p className="text-sm text-red-700 dark:text-red-300 mb-4">
                    {t('advanced.deleteWarning')}
                  </p>
                  <Button
                    data-testid="settings-delete-account"
                    variant="destructive"
                    onClick={() => {
                      setShowDeleteConfirm(true);
                      unifiedApiService
                        .getAccountDeletionStatus()
                        .then((s) => setGracePeriodDays(s.gracePeriodDays))
                        .catch(() => { /* keep the default, the dialog still opens */ });
                    }}
                    size="sm"
                    className="h-8 px-3"
                  >
                    <Trash2 className="w-4 h-4 mr-1" />
                    <span>{t('advanced.deleteButton')}</span>
                  </Button>
                </div>
              </div>
            </div>
          </TabsContent>
        </Tabs>

        {/* Account deletion confirmation modal */}
        <Dialog open={showDeleteConfirm} onOpenChange={setShowDeleteConfirm}>
          <DialogContent className="max-w-md bg-theme-primary">
            <DialogHeader>
              <div className="text-center">
                <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-2xl flex items-center justify-center mx-auto mb-4">
                  <Trash2 className="w-8 h-8 text-red-600" />
                </div>
                <DialogTitle>{t('deleteDialog.title')}</DialogTitle>
                <DialogDescription className="mt-2">
                  {t('deleteDialog.description', { days: gracePeriodDays })}
                </DialogDescription>
              </div>
            </DialogHeader>

            {/* Recommended actions */}
            <div className="bg-theme-tertiary border border-theme rounded-xl p-4">
              <h4 className="text-sm font-semibold text-theme-primary mb-2">
                {t('deleteDialog.whatHappens')}
              </h4>
              <div className="space-y-1 text-left text-sm text-theme-secondary">
                <p>* {t('deleteDialog.dataDeleted')}</p>
                <p>* {t('deleteDialog.subscriptionCancelled')}</p>
                <p>* {t('deleteDialog.toolsDeleted')}</p>
                <p>* {t('deleteDialog.cannotUndo', { days: gracePeriodDays })}</p>
              </div>
            </div>

            {/* Confirmation field */}
            <div className="space-y-2">
              <Label
                htmlFor="deleteConfirmation"
                className="text-theme-primary"
              >
                {t('deleteDialog.confirmInstruction')}{" "}
                <span className="font-mono text-red-600">DELETE</span> :
              </Label>
              <Input
                type="text"
                id="deleteConfirmation"
                value={deleteConfirmation}
                onChange={(e) => setDeleteConfirmation(e.target.value)}
                placeholder={t('deleteDialog.typePlaceholder')}
                className="focus:ring-red-500/50"
              />
            </div>

            <DialogFooter className="flex gap-3 sm:flex-row sm:justify-end">
              <Button
                variant="outline"
                size="sm"
                className="h-8 px-3"
                onClick={() => {
                  setShowDeleteConfirm(false);
                  setDeleteConfirmation("");
                }}
              >
                {t('common.cancel')}
              </Button>
              <Button
                data-testid="settings-delete-account-confirm"
                variant="destructive"
                size="sm"
                className="h-8 px-3"
                onClick={async () => {
                  setIsDeleting(true);
                  try {
                    await unifiedApiService.deleteAccount();
                    await logout();
                  } catch (err) {
                    console.error('Account deletion failed:', err);
                    setIsDeleting(false);
                  }
                }}
                disabled={deleteConfirmation !== "DELETE" || isDeleting}
              >
                {isDeleting ? t('common.deleting') : t('deleteDialog.deletePermanently')}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </div>
  );
}
