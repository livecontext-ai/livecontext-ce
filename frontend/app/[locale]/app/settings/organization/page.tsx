"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
import { useLocale, useTranslations } from "next-intl";
import { IS_CE } from "@/lib/edition";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useWorkspaceEntitlements } from "@/hooks/useWorkspaceEntitlements";
import { useAuth } from "@/lib/providers/smart-providers";
import { apiClient } from "@/lib/api";
import { organizationApi, type Organization, type OrganizationMember, type Invitation, type OrganizationRole } from "@/lib/api/organization-api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import InviteMemberModal from "@/components/organization/InviteMemberModal";
import MemberAccessModal from "@/components/organization/MemberAccessModal";
import MemberQuotaDialog from "@/components/organization/MemberQuotaDialog";
import OrganizationDangerZone from "@/components/organization/OrganizationDangerZone";
import OrganizationAuditLogPanel from "@/components/organization/OrganizationAuditLogPanel";
import OrganizationSsoPanel from "@/components/organization/OrganizationSsoPanel";
import { WorkspaceAvatar } from "@/components/organization/WorkspaceAvatar";
import { ConfirmDeleteModal } from "@/components/chat/ConfirmDeleteModal";
import LoadingSpinner from "@/components/LoadingSpinner";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import {
  Building2,
  Users,
  Crown,
  Shield,
  Gauge,
  User,
  Eye,
  Check,
  Star,
  Pencil,
  X,
  UserPlus,
  Clock,
  Info,
  Trash2,
  RefreshCw,
  ChevronDown,
  ArrowUpRight,
  Camera,
  Lock,
  Settings,
  Link2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { useCurrentOrgStore, reconcileCurrentOrgFromMemberships, type OrgRole } from "@/lib/stores/current-org-store";
import { formatUtcDate } from "@/lib/utils/dateFormatters";

const ROLE_ICONS: Record<string, React.ReactNode> = {
  OWNER: <Crown className="h-3.5 w-3.5 text-amber-500" />,
  ADMIN: <Crown className="h-3.5 w-3.5 text-yellow-500" />,
  MEMBER: <User className="h-3.5 w-3.5 text-theme-secondary" />,
  VIEWER: <Eye className="h-3.5 w-3.5 text-theme-muted" />,
};

const ROLE_BADGE_STYLES: Record<string, string> = {
  OWNER: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  ADMIN: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400",
  MEMBER: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
  VIEWER: "bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400",
};

const PLAN_BADGE_STYLES: Record<string, string> = {
  FREE: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
  STARTER: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
  PRO: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400",
  TEAM: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400",
};

function getPlanBadgeStyle(planCode: string | undefined) {
  if (!planCode) return PLAN_BADGE_STYLES.FREE;
  if (planCode.startsWith("ENTERPRISE")) return "bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-400";
  return PLAN_BADGE_STYLES[planCode] || PLAN_BADGE_STYLES.FREE;
}

// Pill-toggle categories for the workspace page (overview-style). Each id maps
// to a <TabsContent>; labelKey is resolved with the "settings" namespace.
const ORG_TABS: { id: string; labelKey: string; icon: React.ElementType }[] = [
  { id: "members", labelKey: "organization.tabs.members", icon: Users },
  { id: "workspaces", labelKey: "organization.tabs.workspaces", icon: Building2 },
  { id: "security", labelKey: "organization.tabs.security", icon: Shield },
  { id: "advanced", labelKey: "organization.tabs.advanced", icon: Settings },
];

function OrganizationSkeleton() {
  return (
    <div className="space-y-8">
      <Skeleton className="h-32 w-full rounded-xl" />
      <Skeleton className="h-24 w-full rounded-xl" />
      <Skeleton className="h-48 w-full rounded-xl" />
    </div>
  );
}

export default function OrganizationSettingsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect, user } = useAuth();
  const t = useTranslations("settings");
  const locale = useLocale();
  const tCommon = useTranslations("common");
  const tQuota = useTranslations("quota");
  // Reuse the sidebar's dormant-workspace copy (already localized in all 6
  // locales) so the badge + error wording stay consistent with the switcher.
  const tNav = useTranslations("sidebar");
  // Reuse the workspace-upgrade copy shared with the sidebar's "create workspace"
  // upgrade gate (already localized in all 6 locales).
  const tWs = useTranslations("modals.workspaceUpgrade");
  // Additional workspaces are a PRO+ entitlement - drives the Workspaces-tab upgrade card.
  // (Hook lives here, above the early returns, to respect the rules of hooks.)
  const { canCreateWorkspace } = useWorkspaceEntitlements();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();

  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [currentOrg, setCurrentOrg] = useState<Organization | null>(null);
  const [pendingInvitations, setPendingInvitations] = useState<Invitation[]>([]);
  // CE invite-by-link: which pending invitation's link was just copied (for the check icon).
  const [copiedInviteId, setCopiedInviteId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [memberAvatars, setMemberAvatars] = useState<Record<number, string>>({});
  const [editingName, setEditingName] = useState(false);
  const [newName, setNewName] = useState("");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [inviteModalOpen, setInviteModalOpen] = useState(false);
  const [roleDropdownOpen, setRoleDropdownOpen] = useState<number | null>(null);
  const [accessModalMember, setAccessModalMember] = useState<OrganizationMember | null>(null);
  const [quotaModalMember, setQuotaModalMember] = useState<OrganizationMember | null>(null);
  // Member-removal confirmation modal (replaces the native window.confirm so the
  // destructive action matches the rest of the app's delete dialogs).
  const [memberToRemove, setMemberToRemove] = useState<OrganizationMember | null>(null);
  const [removingMember, setRemovingMember] = useState(false);
  // Workspace-avatar editing (OWNER/ADMIN) - upload/replace/remove from the header.
  const avatarInputRef = useRef<HTMLInputElement>(null);
  const [avatarUploading, setAvatarUploading] = useState(false);
  // Workspace-switch UX: setDefault → invalidate × 8 → router.refresh takes
  // 1-3s in prod. Without visible feedback users hammer the radio and end up
  // in the rate-limit window (audit-B-M2 5s cooldown). isSwitchingOrg drives
  // a full-page overlay so the user sees progress.
  const [isSwitchingOrg, setIsSwitchingOrg] = useState(false);
  const [restoringOrgId, setRestoringOrgId] = useState<string | null>(null);
  // Categorized layout: a single overview-style pill toggle splits the page
  // sections into tabs (the workspace header above stays visible on every tab).
  // "members" is the default so the members table loads first (and existing
  // deep-links / tests that look for member rows keep working).
  const [activeTab, setActiveTab] = useState<string>("members");
  // Animated slider behind the active pill - mirrors the overview tabs.
  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const orgs = await organizationApi.getOrganizations();
      setOrganizations(orgs || []);

      // Keep the active-workspace store aligned with the fresh list. After deleting
      // the workspace the user was in, that org comes back flagged pendingDeletion;
      // reconcile evicts it and re-points the store at the default/personal workspace
      // so requests stop resolving to the deleted org (prod bug 2026-06-06).
      // Organization is a structural superset of CurrentOrgMembershipSnapshot
      // (id/currentUserRole/isDefault/pendingDeletion/paused) - pass it directly.
      reconcileCurrentOrgFromMemberships(orgs || []);

      // The header (currentOrg) is the user's active workspace. Never surface a
      // PAUSED (dormant) org here - the gateway already routes the user out of a
      // paused default to their own workspace, so mirror that: prefer the
      // non-paused default, else any non-paused org, and only fall back to a
      // paused one if the user genuinely has nothing else.
      const defaultOrg =
        orgs?.find((o) => o.isDefault && !o.paused) ||
        orgs?.find((o) => !o.paused) ||
        orgs?.find((o) => o.isDefault) ||
        orgs?.[0];
      if (defaultOrg) {
        const fullOrg = await organizationApi.getOrganization(defaultOrg.id);
        setCurrentOrg(fullOrg);
        setNewName(fullOrg?.name || "");

        // Fetch pending invitations if user can see them
        if (fullOrg.canInvite !== undefined) {
          try {
            const invitations = await organizationApi.getPendingInvitations(fullOrg.id);
            setPendingInvitations(invitations || []);
          } catch {
            // Non-critical
          }
        }

        // Fetch member avatars
        if (fullOrg?.members) {
          const tokenProvider = apiClient.getTokenProvider();
          const token = tokenProvider ? await tokenProvider() : null;
          if (token) {
            const avatarEntries = await Promise.all(
              fullOrg.members
                .filter((m) => m.avatarUrl)
                .map(async (m) => {
                  try {
                    const res = await fetch(`/api/proxy/users/${m.userId}/avatar`, {
                      headers: { Authorization: `Bearer ${token}` },
                    });
                    if (!res.ok) return null;
                    const blob = await res.blob();
                    return [m.userId, URL.createObjectURL(blob)] as const;
                  } catch {
                    return null;
                  }
                })
            );
            const avatarMap: Record<number, string> = {};
            for (const entry of avatarEntries) {
              if (entry) avatarMap[entry[0]] = entry[1];
            }
            setMemberAvatars(avatarMap);
          }
        }
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to load organizations";
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isAuthChecking || !isAuthenticated) {
      if (!isAuthChecking) setLoading(false);
      return;
    }
    fetchData();
  }, [isAuthenticated, isAuthChecking, fetchData]);

  // Auto-open the invite modal when arriving via the sidebar
  // "Invite teammates" entry (which deep-links with ?invite=1). Gated on the
  // same `canEdit && canInvite` checks as the manual button so a non-admin
  // can't surface the modal by hand-crafting the URL. After opening we strip
  // the param via router.replace so a refresh doesn't keep popping the modal.
  useEffect(() => {
    if (!currentOrg) return;
    if (searchParams?.get("invite") !== "1") return;
    const canEdit = currentOrg.currentUserRole === "OWNER" || currentOrg.currentUserRole === "ADMIN";
    if (!canEdit || !currentOrg.canInvite) return;
    // Invite lives under the Members tab - surface it before opening the modal so
    // the user lands on the relevant section.
    setActiveTab("members");
    setInviteModalOpen(true);
    const next = new URLSearchParams(searchParams?.toString() ?? "");
    next.delete("invite");
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : (pathname ?? "/app/settings/organization"));
  }, [currentOrg, searchParams, router, pathname]);

  // Deep-link support: ?tab=members|workspaces|security|advanced selects a tab.
  const tabParam = searchParams?.get("tab");
  useEffect(() => {
    if (tabParam && ["members", "workspaces", "security", "advanced"].includes(tabParam)) {
      setActiveTab(tabParam);
    }
  }, [tabParam]);

  // Position the animated slider behind the active pill. Recomputes when the
  // active tab changes and once content mounts (loading flips false / org loads),
  // since the pill row only exists after the skeleton is replaced.
  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(
        `[data-tab-id="${activeTab}"]`
      ) as HTMLButtonElement | null;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setTabSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    updateSlider();
    window.addEventListener("resize", updateSlider);
    return () => window.removeEventListener("resize", updateSlider);
  }, [activeTab, loading, currentOrg]);

  const handleSaveName = async () => {
    if (!currentOrg || !newName.trim() || newName === currentOrg.name) {
      setEditingName(false);
      return;
    }
    try {
      setSaving(true);
      await organizationApi.updateOrganization(currentOrg.id, { name: newName.trim() });
      setCurrentOrg((prev) => (prev ? { ...prev, name: newName.trim() } : null));
      setOrganizations((prev) => prev.map((o) => (o.id === currentOrg.id ? { ...o, name: newName.trim() } : o)));
      setEditingName(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch {
      setError("Failed to update organization");
    } finally {
      setSaving(false);
    }
  };

  // Self-service restore of a soft-deleted workspace (within the grace window). Mirrors the
  // user-menu switcher restore - owner-only, brings the workspace back into the normal list.
  const handleRestoreWorkspace = async (orgId: string) => {
    setRestoringOrgId(orgId);
    setError(null);
    try {
      await organizationApi.restoreOrganization(orgId);
      await fetchData();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRestoringOrgId(null);
    }
  };

  const handleSetDefault = async (orgId: string) => {
    // Guards: switch in flight → no-op; already default → no-op; paused → blocked.
    if (isSwitchingOrg) return;
    const targetOrg = organizations.find((o) => o.id === orgId);
    if (targetOrg?.isDefault) return;
    // Dormant-org guard: a paused workspace (owner downgraded below team) cannot
    // be entered, so it cannot be the default. The button is hidden for paused
    // orgs; this guards programmatic/stale calls and mirrors the backend 409.
    if (targetOrg?.paused) {
      setError(tNav("workspacePausedHint"));
      return;
    }

    setIsSwitchingOrg(true);
    setError(null);
    try {
      await organizationApi.setDefaultOrganization(orgId);

      // Local-state echo so the radio flips immediately.
      setOrganizations((prev) => prev.map((o) => ({ ...o, isDefault: o.id === orgId })));
      if (currentOrg) {
        setCurrentOrg((prev) => (prev ? { ...prev, isDefault: prev.id === orgId } : null));
      }

      // PR2 (Bug-2): swap the active workspace + refresh data.
      // (1) Push the selection into the active-org store so future apiClient
      //     requests send `X-Active-Organization-ID` matching the new default
      //     (gateway resolves the per-request org context from this header).
      const target = organizations.find((o) => o.id === orgId);
      const role = (target?.currentUserRole as OrgRole | undefined) ?? null;
      if (role) {
        useCurrentOrgStore.getState().setCurrentOrg(orgId, role);
      }
      // (2) Invalidate every org-scoped React Query bucket. We invalidate
      //     broadly (no orgId in keys today - that's a follow-up PR per
      //     plan.md §4.4) so any stale workspace-bound list refetches.
      //     The cost is one round-trip per active hook on next render.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["organizations"] }),
        queryClient.invalidateQueries({ queryKey: ["workflows"] }),
        queryClient.invalidateQueries({ queryKey: ["agents"] }),
        queryClient.invalidateQueries({ queryKey: ["dataSources"] }),
        queryClient.invalidateQueries({ queryKey: ["interfaces"] }),
        queryClient.invalidateQueries({ queryKey: ["conversations"] }),
        queryClient.invalidateQueries({ queryKey: ["user"] }),
        queryClient.invalidateQueries({ queryKey: ["plans"] }),
      ]);
      // (3) Refresh server components in the current segment so the sidebar
      //     plan badge / overview re-resolve from the gateway-fresh response.
      router.refresh();
    } catch (err: unknown) {
      // Surface the rate-limit window if user is clicking too fast (audit-B-M2
      // 5s cooldown). The thrown ApiError carries the 429 status; we'd ideally
      // parse Retry-After from headers, but the generic message tells the user
      // to wait a moment.
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes("429") || msg.toLowerCase().includes("too many")) {
        setError(t("organization.setDefaultRateLimited"));
      } else if (msg.includes("409")) {
        // ORG_PAUSED - the workspace went dormant since the list was fetched.
        setError(tNav("workspacePausedHint"));
      } else {
        setError(t("organization.setDefaultFailed"));
      }
    } finally {
      setIsSwitchingOrg(false);
    }
  };

  const handleAvatarFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file later
    if (!file || !currentOrg) return;
    if (file.size > 5 * 1024 * 1024) {
      setError(t("organization.avatarTooLarge"));
      return;
    }
    setAvatarUploading(true);
    setError(null);
    try {
      await organizationApi.uploadAvatar(currentOrg.id, file);
      await fetchData();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to upload avatar");
    } finally {
      setAvatarUploading(false);
    }
  };

  const handleRemoveAvatar = async () => {
    if (!currentOrg || !currentOrg.avatarUrl) return;
    setAvatarUploading(true);
    setError(null);
    try {
      await organizationApi.deleteAvatar(currentOrg.id);
      await fetchData();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to remove avatar");
    } finally {
      setAvatarUploading(false);
    }
  };

  const confirmRemoveMember = async () => {
    if (!currentOrg || !memberToRemove) return;
    setRemovingMember(true);
    try {
      await organizationApi.removeMember(currentOrg.id, memberToRemove.userId);
      setMemberToRemove(null);
      fetchData();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to remove member";
      setError(message);
    } finally {
      setRemovingMember(false);
    }
  };

  const handleChangeRole = async (member: OrganizationMember, newRole: OrganizationRole) => {
    if (!currentOrg) return;
    setRoleDropdownOpen(null);
    try {
      await organizationApi.changeMemberRole(currentOrg.id, member.userId, newRole);
      fetchData();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to change role";
      setError(message);
    }
  };

  const handleCancelInvitation = async (invId: string) => {
    if (!currentOrg) return;
    const confirmed = window.confirm(t("organization.cancelInvitationConfirm"));
    if (!confirmed) return;
    try {
      await organizationApi.cancelInvitation(currentOrg.id, invId);
      setPendingInvitations((prev) => prev.filter((i) => i.id !== invId));
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to cancel invitation";
      setError(message);
    }
  };

  // CE invite-by-link: copy the accept link for a still-pending invitation.
  const handleCopyInviteLink = async (inv: Invitation) => {
    if (!inv.token) return;
    const link = `${window.location.origin}/${locale}/invitations/accept?token=${encodeURIComponent(inv.token)}`;
    try {
      await navigator.clipboard.writeText(link);
      setCopiedInviteId(inv.id);
      setTimeout(() => setCopiedInviteId((id) => (id === inv.id ? null : id)), 2000);
    } catch {
      /* clipboard blocked - silently ignore */
    }
  };

  const formatDate = (dateStr: string) => formatUtcDate(dateStr);

  const getRoleLabel = (role: string) => {
    const key = `organization.role${role.charAt(0)}${role.slice(1).toLowerCase()}` as const;
    return t(key);
  };

  if (isAuthChecking || loading) return <OrganizationSkeleton />;

  if (!isAuthenticated) {
    return (
      <div className="min-h-[300px] flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-theme-primary mb-4">{t("unauthorized")}</h1>
          <p className="text-theme-secondary mb-6">{t("mustBeLoggedIn")}</p>
          <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
            <User className="w-4 h-4 mr-1" />
            {t("signIn")}
          </Button>
        </div>
      </div>
    );
  }

  if (error && !currentOrg) {
    return (
      <div className="p-4 border border-red-200 dark:border-red-800/50 rounded-xl">
        <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
      </div>
    );
  }

  const canEdit = currentOrg?.currentUserRole === "OWNER" || currentOrg?.currentUserRole === "ADMIN";
  const isOwner = currentOrg?.currentUserRole === "OWNER";
  const maxMembers = currentOrg?.maxMembers ?? 1;
  const isUnlimitedMembers = maxMembers >= 1_000_000;
  const memberLimitLabel = isUnlimitedMembers ? t("storage.unlimited") : String(maxMembers);
  const supportsTeam = currentOrg?.planCode === "TEAM"
    || currentOrg?.planCode?.startsWith("ENTERPRISE")
    || maxMembers > 1;
  // Owner-side "dormant" state: the owner is on a plan that no longer supports
  // teams, yet other members still belong to this workspace. Those members are
  // blocked from entering until the owner upgrades (the gateway/sidebar enforce
  // it for them) - surface that here so the owner understands why the seats look
  // active but the teammates can't get in.
  const teamDormant = isOwner && !supportsTeam && (currentOrg?.memberCount ?? 0) > 1;
  const usedSlots = (currentOrg?.memberCount ?? 0) + (currentOrg?.pendingInvitationCount ?? 0);
  const usagePercent = !isUnlimitedMembers && maxMembers > 0 ? Math.min((usedSlots / maxMembers) * 100, 100) : 0;
  const usageLabel = isUnlimitedMembers ? t("storage.unlimited") : `${Math.round(usagePercent)}%`;
  const barColor = usagePercent >= 90 ? "bg-red-500" : usagePercent >= 70 ? "bg-amber-500" : "bg-gray-900 dark:bg-white";

  return (
    <div className="space-y-8">
      {/* Error Banner */}
      {error && (
        <div className="p-4 border border-red-200 dark:border-red-800/50 rounded-xl">
          <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
          <button onClick={() => setError(null)} className="mt-1 text-xs text-red-500 hover:underline">
            {t("common.dismiss") ?? "Dismiss"}
          </button>
        </div>
      )}

      {/* Section 1: Workspace Header */}
      {currentOrg && (
        <section className="rounded-xl p-6 border border-theme">
          <div className="flex items-start justify-between">
            {/* Left: Avatar + Name + Details */}
            <div className="flex items-start gap-4 flex-1 min-w-0">
              {canEdit ? (
                <div className="relative group flex-shrink-0">
                  <button
                    type="button"
                    onClick={() => avatarInputRef.current?.click()}
                    disabled={avatarUploading}
                    title={t("organization.changeAvatar")}
                    aria-label={t("organization.changeAvatar")}
                    className="relative rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
                  >
                    <WorkspaceAvatar name={currentOrg.name} avatarUrl={currentOrg.avatarUrl} size="lg" />
                    <span className="absolute inset-0 rounded-full bg-black/45 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                      {avatarUploading ? <LoadingSpinner size="xs" /> : <Camera className="h-5 w-5 text-white" />}
                    </span>
                  </button>
                  {currentOrg.avatarUrl && !avatarUploading && (
                    <button
                      type="button"
                      onClick={handleRemoveAvatar}
                      title={t("organization.removeAvatar")}
                      aria-label={t("organization.removeAvatar")}
                      className="absolute -top-1 -right-1 w-5 h-5 bg-red-500 hover:bg-red-600 rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity focus:outline-none focus-visible:opacity-100"
                    >
                      <X className="h-3 w-3 text-white" />
                    </button>
                  )}
                  <input
                    ref={avatarInputRef}
                    type="file"
                    accept="image/jpeg,image/png,image/gif,image/webp"
                    onChange={handleAvatarFileChange}
                    className="hidden"
                  />
                </div>
              ) : (
                <WorkspaceAvatar name={currentOrg.name} avatarUrl={currentOrg.avatarUrl} size="lg" />
              )}
              <div className="flex-1 min-w-0">
              <div className="flex items-center gap-3 mb-1">
                {editingName ? (
                  <div className="flex items-center gap-2">
                    <Input
                      value={newName}
                      onChange={(e) => setNewName(e.target.value)}
                      className="h-11 w-full max-w-md text-xl font-semibold bg-white dark:bg-gray-800 border-black/10 dark:border-white/10"
                      disabled={saving}
                      autoFocus
                    />
                    <button
                      type="button"
                      onClick={handleSaveName}
                      disabled={saving || !newName.trim()}
                      aria-label={t("organization.save")}
                      title={t("organization.save")}
                      className="p-1.5 hover:bg-black/5 dark:hover:bg-white/5 rounded-lg"
                    >
                      <Check className="h-4 w-4 text-emerald-500" />
                    </button>
                    <button
                      type="button"
                      onClick={() => { setEditingName(false); setNewName(currentOrg.name); }}
                      disabled={saving}
                      aria-label={t("organization.cancel")}
                      title={t("organization.cancel")}
                      className="p-1.5 hover:bg-black/5 dark:hover:bg-white/5 rounded-lg"
                    >
                      <X className="h-4 w-4 text-theme-muted" />
                    </button>
                  </div>
                ) : (
                  <>
                    <h1 className="text-xl font-semibold text-theme-primary truncate">{currentOrg.name}</h1>
                    {canEdit && (
                      <button
                        type="button"
                        onClick={() => setEditingName(true)}
                        aria-label={tCommon("edit")}
                        title={tCommon("edit")}
                        className="p-1.5 hover:bg-black/5 dark:hover:bg-white/5 rounded-lg"
                      >
                        <Pencil className="h-3.5 w-3.5 text-theme-muted" />
                      </button>
                    )}
                    {saved && <span className="text-xs text-emerald-500">{t("common.saved")}</span>}
                  </>
                )}
              </div>
              {/* Personal workspaces slug = the user's own name (e.g. "ada-lovelace"),
                  which just duplicates the workspace name above and surfaces the raw
                  name - hide it. Team workspaces keep the slug (a real org handle). */}
              <p className="text-sm text-theme-secondary">
                {!currentOrg.isPersonal && <>{currentOrg.slug} &middot; </>}
                {t("organization.created")} {formatDate(currentOrg.createdAt)}
              </p>
              </div>
            </div>

            {/* Right: Badges */}
            <div className="flex items-center gap-2 flex-shrink-0">
              <span className={cn("inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium", ROLE_BADGE_STYLES[currentOrg.currentUserRole])}>
                {ROLE_ICONS[currentOrg.currentUserRole]}
                {getRoleLabel(currentOrg.currentUserRole)}
              </span>
              <span className={cn("inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium", getPlanBadgeStyle(currentOrg.planCode))}>
                {currentOrg.planCode || "FREE"}
              </span>
            </div>
          </div>

          {/* Stats Row */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mt-5 pt-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
                <Clock className="h-5 w-5 text-theme-primary" />
              </div>
              <div>
                <p className="text-lg font-semibold text-theme-primary">{pendingInvitations.length}</p>
                <p className="text-xs text-theme-secondary">{t("organization.pendingInvitations")}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
                <Building2 className="h-5 w-5 text-theme-primary" />
              </div>
              <div>
                <p className="text-lg font-semibold text-theme-primary">{memberLimitLabel}</p>
                <p className="text-xs text-theme-secondary">{t("organization.memberLimit")}</p>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Categorized sections - overview-style pill toggle. The workspace header
          above stays visible on every tab so the current workspace is always in
          view; only the section below the toggle swaps. */}
      {currentOrg && (
        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <div className="relative mb-6 flex max-w-full overflow-x-auto scrollbar-hide">
            <div
              ref={tabContainerRef}
              className="relative mx-auto inline-flex w-max items-center gap-0.5 sm:gap-1 p-1 sm:p-1.5 bg-theme-tertiary rounded-full"
            >
              {/* Slider highlight */}
              <div
                className="absolute top-1 sm:top-1.5 bottom-1 sm:bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
                style={{
                  left: tabSliderStyle.left,
                  width: tabSliderStyle.width,
                  opacity: tabSliderStyle.width ? 1 : 0,
                }}
              />
              {ORG_TABS.map((tab) => (
                <button
                  key={tab.id}
                  data-tab-id={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={cn(
                    "relative z-10 flex items-center gap-1.5 sm:gap-2 px-2.5 sm:px-4 py-1.5 sm:py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                    activeTab === tab.id
                      ? "text-[var(--text-primary)]"
                      : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
                  )}
                >
                  <tab.icon className={cn("w-4 h-4 flex-shrink-0", activeTab === tab.id ? "text-[var(--text-primary)]" : "text-current")} />
                  <span className="whitespace-nowrap">{t(tab.labelKey)}</span>
                </button>
              ))}
            </div>
          </div>

          {/* ===== Members tab ===== */}
          <TabsContent value="members" className="mt-0 space-y-8">
      {/* Owner-side dormant notice: plan dropped below team but members remain. */}
      {teamDormant && (
        <section className="rounded-xl p-4 border border-amber-300/60 dark:border-amber-800/50 bg-amber-50 dark:bg-amber-900/20">
          <div className="flex items-start gap-3">
            <Clock className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
            <p className="text-sm text-amber-800 dark:text-amber-300">
              {t("organization.teamDormantNotice", { count: (currentOrg?.memberCount ?? 1) - 1 })}
            </p>
          </div>
        </section>
      )}

      {/* Section 2: Plan & Team Status (for TEAM/ENTERPRISE) */}
      {currentOrg && supportsTeam && (
        <section className="bg-theme-secondary rounded-xl p-6 border border-theme">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-theme-primary">{t("organization.teamStatus")}</h2>
            {canEdit && currentOrg.canInvite && (
              <Button size="sm" onClick={() => setInviteModalOpen(true)} className="h-8 px-3">
                <UserPlus className="h-3.5 w-3.5 mr-1.5" />
                {t("organization.inviteMember")}
              </Button>
            )}
          </div>

          {/* Progress Bar */}
          <div className="space-y-2">
            <div className="flex items-center justify-between text-sm">
              <span className="text-theme-secondary">
                {t("organization.membersUsed", { current: String(usedSlots), max: memberLimitLabel })}
              </span>
              <span className="text-theme-muted">{usageLabel}</span>
            </div>
            <div className="h-1.5 bg-theme-tertiary rounded-full overflow-hidden">
              <div className={cn("h-full transition-all", barColor)} style={{ width: `${usagePercent}%` }} />
            </div>
          </div>

          {/* Member Limit Warning */}
          {!isUnlimitedMembers && !currentOrg.canInvite && usedSlots >= maxMembers && (
            <p className="mt-3 text-sm text-amber-600 dark:text-amber-400">
              {t("organization.memberLimitReached")}
            </p>
          )}
        </section>
      )}

      {/* Section 3: Members Table */}
      {currentOrg && currentOrg.members && currentOrg.members.length > 0 && (
        <section>
          <div className="mb-4 flex items-center gap-3">
            <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-theme-secondary">
              <Users className="h-5 w-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t("organization.members")}</h2>
              <p className="text-sm text-theme-secondary">
                {t("organization.membersCount", { count: currentOrg.memberCount ?? currentOrg.members.length })}
              </p>
            </div>
          </div>

          <div className="border border-theme rounded-xl overflow-x-auto">
            {/* Table Header */}
            <div className="grid grid-cols-[1fr_120px_120px_128px] gap-4 px-4 py-3 bg-theme-secondary border-b border-theme min-w-[560px]">
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.members")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.role")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.joined")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.actions")}</span>
            </div>

            {/* Rows */}
            {currentOrg.members.map((member) => (
              <div
                key={member.userId}
                className="grid grid-cols-[1fr_120px_120px_128px] gap-4 px-4 py-3 items-center hover:bg-theme-secondary/50 transition-colors border-b last:border-b-0 border-slate-100 dark:border-slate-800 min-w-[560px]"
              >
                {/* Name + Email */}
                <div className="flex items-center gap-3 min-w-0">
                  <div className="h-8 w-8 rounded-full bg-theme-tertiary flex items-center justify-center overflow-hidden flex-shrink-0">
                    {memberAvatars[member.userId] ? (
                      <img src={memberAvatars[member.userId]} alt={member.displayName} className="h-full w-full object-cover" />
                    ) : (
                      <User className="h-4 w-4 text-theme-muted" />
                    )}
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-theme-primary truncate">{member.displayName}</p>
                    <p className="text-xs text-theme-muted truncate">{member.email}</p>
                  </div>
                </div>

                {/* Role Badge */}
                <div className="flex flex-col items-start gap-1">
                  <span className={cn("inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium", ROLE_BADGE_STYLES[member.role])}>
                    {ROLE_ICONS[member.role]}
                    {getRoleLabel(member.role)}
                  </span>
                  {/* Dormant team: a non-owner member cannot enter until the owner
                      upgrades - mark the seat so the owner isn't misled by it. */}
                  {!supportsTeam && member.role !== "OWNER" && (
                    <span
                      className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
                      title={tNav("workspacePausedHint")}
                    >
                      {tNav("workspacePaused")}
                    </span>
                  )}
                </div>

                {/* Joined Date */}
                <span className="text-xs text-theme-secondary">{formatDate(member.joinedAt)}</span>

                {/* Actions */}
                <div className="flex items-center gap-1">
                  {/* Role dropdown is OWNER-only ON PURPOSE (not canEdit): the backend
                      (OrganizationMemberService.changeRole) rejects any non-OWNER
                      requester, forbids changing your own role, and never promotes to
                      OWNER (use ownership transfer). This gate mirrors those rules -
                      widening it to ADMIN would only surface a 403. */}
                  {isOwner && member.role !== "OWNER" && member.email !== user?.email && (
                    <Popover
                      open={roleDropdownOpen === member.userId}
                      onOpenChange={(open) =>
                        setRoleDropdownOpen(open ? member.userId : null)
                      }
                    >
                      <PopoverTrigger asChild>
                        <button
                          type="button"
                          className="p-1.5 hover:bg-black/5 dark:hover:bg-white/5 rounded-lg transition-colors"
                          title={t("organization.changeRole")}
                        >
                          <ChevronDown className="h-3.5 w-3.5 text-theme-muted" />
                        </button>
                      </PopoverTrigger>
                      <PopoverContent
                        align="end"
                        sideOffset={4}
                        className="z-50 w-auto min-w-[120px] p-0 py-1 rounded-lg bg-white dark:bg-gray-900 border border-black/10 dark:border-white/10 shadow-lg"
                      >
                        {(["ADMIN", "MEMBER", "VIEWER"] as OrganizationRole[])
                          .filter((r) => r !== member.role)
                          .map((r) => (
                            <button
                              key={r}
                              onClick={() => handleChangeRole(member, r)}
                              className="w-full text-left px-3 py-1.5 text-sm text-theme-primary hover:bg-black/5 dark:hover:bg-white/5 flex items-center gap-2"
                            >
                              {ROLE_ICONS[r]}
                              {getRoleLabel(r)}
                            </button>
                          ))}
                      </PopoverContent>
                    </Popover>
                  )}
                  {canEdit && (member.role === "MEMBER" || member.role === "VIEWER") && (
                    <button
                      onClick={() => setAccessModalMember(member)}
                      className="p-1.5 hover:bg-blue-50 dark:hover:bg-blue-900/20 rounded-lg transition-colors"
                      title={t("organization.manageAccess")}
                    >
                      <Shield className="h-3.5 w-3.5 text-blue-500" />
                    </button>
                  )}
                  {canEdit && member.role !== "OWNER" && (
                    <button
                      onClick={() => setQuotaModalMember(member)}
                      className="p-1.5 hover:bg-purple-50 dark:hover:bg-purple-900/20 rounded-lg transition-colors"
                      title={tQuota("manageQuota")}
                    >
                      <Gauge className="h-3.5 w-3.5 text-purple-500" />
                    </button>
                  )}
                  {canEdit && member.role !== "OWNER" && member.email !== user?.email && (
                    <button
                      onClick={() => setMemberToRemove(member)}
                      className="p-1.5 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg transition-colors"
                      title={t("organization.removeMember")}
                    >
                      <Trash2 className="h-3.5 w-3.5 text-red-500" />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Section 4: Pending Invitations */}
      {pendingInvitations.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-theme-primary mb-4">{t("organization.pendingInvitations")}</h2>

          <div className="border border-theme rounded-xl overflow-x-auto">
            <div className="grid grid-cols-[1fr_100px_120px_120px_88px] gap-4 px-4 py-3 bg-theme-secondary border-b border-theme min-w-[560px]">
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.email")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.role")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.invitedBy")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider">{t("organization.expires")}</span>
              <span className="text-xs text-theme-muted uppercase tracking-wider"></span>
            </div>

            {pendingInvitations.map((inv) => (
              <div
                key={inv.id}
                className="grid grid-cols-[1fr_100px_120px_120px_88px] gap-4 px-4 py-3 items-center border-b last:border-b-0 border-slate-100 dark:border-slate-800 min-w-[560px]"
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400">
                    {t("organization.pendingInvitations").split(" ")[0]}
                  </span>
                  <span className="text-sm text-theme-primary truncate">{inv.email}</span>
                </div>
                <span className={cn("inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium w-fit", ROLE_BADGE_STYLES[inv.role])}>
                  {ROLE_ICONS[inv.role]}
                  {getRoleLabel(inv.role)}
                </span>
                <span className="text-xs text-theme-secondary truncate">{inv.invitedByName}</span>
                <span className="text-xs text-theme-secondary">{formatDate(inv.expiresAt)}</span>
                <div className="flex items-center gap-1">
                  {IS_CE && inv.token && (
                    <button
                      onClick={() => handleCopyInviteLink(inv)}
                      className="p-1.5 hover:bg-emerald-50 dark:hover:bg-emerald-900/20 rounded-lg transition-colors"
                      title={t("organization.copyInviteLink")}
                    >
                      {copiedInviteId === inv.id ? (
                        <Check className="h-3.5 w-3.5 text-emerald-500" />
                      ) : (
                        <Link2 className="h-3.5 w-3.5 text-emerald-500" />
                      )}
                    </button>
                  )}
                  {canEdit && (
                    <button
                      onClick={() => handleCancelInvitation(inv.id)}
                      className="p-1.5 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg transition-colors"
                      title={t("organization.cancelInvitation")}
                    >
                      <X className="h-3.5 w-3.5 text-red-500" />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Upgrade CTA (for non-TEAM/ENTERPRISE) - grouped with team management. */}
      {currentOrg && !supportsTeam && (
        <section className="bg-theme-secondary rounded-xl p-6 border border-theme">
          <div className="flex items-start gap-4">
            <div className="w-10 h-10 bg-black dark:bg-white rounded-full flex items-center justify-center flex-shrink-0">
              <Users className="h-5 w-5 text-white dark:text-black" />
            </div>
            <div className="flex-1">
              <h3 className="text-lg font-semibold text-theme-primary mb-1">{t("organization.upgradeTitle")}</h3>
              <p className="text-sm text-theme-secondary mb-4">{t("organization.upgradeDescription")}</p>
              <Button size="sm" onClick={() => router.push("/app/settings/pricing")} className="h-8 px-4">
                {t("organization.upgradeCta")}
                <ArrowUpRight className="h-3.5 w-3.5 ml-1.5" />
              </Button>
            </div>
          </div>
        </section>
      )}
          </TabsContent>

          {/* ===== Workspaces tab ===== */}
          <TabsContent value="workspaces" className="mt-0 space-y-8">
      {/* Section 5: All Workspaces */}
      {currentOrg && (
        <section>
          <div className="mb-4 flex items-center gap-3">
            <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-theme-secondary">
              <Building2 className="h-5 w-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t("organization.allWorkspaces")}</h2>
              <p className="text-sm text-theme-secondary">
                {t("organization.workspacesCount", { count: organizations.length })}
              </p>
            </div>
          </div>

          <div className="space-y-2">
            {organizations.map((org) => {
              // A soft-deleted workspace stays listed (owner-only) but flagged: a Restore action
              // (not Set-as-default) + an info line on when it is permanently purged.
              if (org.pendingDeletion) {
                const daysLeft = org.purgeAt
                  ? Math.max(0, Math.ceil((new Date(org.purgeAt).getTime() - Date.now()) / 86_400_000))
                  : null;
                return (
                  <div
                    key={org.id}
                    className="flex items-center gap-4 p-4 rounded-xl border border-red-200 dark:border-red-800/40 bg-red-50/40 dark:bg-red-950/10"
                  >
                    <WorkspaceAvatar name={org.name} avatarUrl={org.avatarUrl} size="md" className="grayscale" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <p className="text-sm font-medium text-theme-secondary line-through truncate">{org.name}</p>
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300">
                          {tNav("workspaceDeleted")}
                        </span>
                      </div>
                      {daysLeft !== null && (
                        <p className="text-xs text-theme-secondary flex items-center gap-1 mt-0.5">
                          <Info className="h-3 w-3 shrink-0" />
                          {tNav("purgeInDays", { days: daysLeft })}
                        </p>
                      )}
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => handleRestoreWorkspace(org.id)}
                      disabled={restoringOrgId === org.id}
                      className="text-xs"
                    >
                      {restoringOrgId === org.id ? tNav("restoring") : tNav("restore")}
                    </Button>
                  </div>
                );
              }
              return (
              <div
                key={org.id}
                className={cn(
                  "flex items-center gap-4 p-4 rounded-xl transition-colors",
                  org.isDefault
                    ? "bg-theme-secondary border-2 border-amber-400/50"
                    : "border border-theme hover:bg-theme-secondary/50"
                )}
              >
                <WorkspaceAvatar name={org.name} avatarUrl={org.avatarUrl} size="md" />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <p className="text-sm font-medium text-theme-primary">{org.name}</p>
                    {org.isDefault && <Star className="h-3.5 w-3.5 fill-amber-500 text-amber-500" />}
                    <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium", getPlanBadgeStyle(org.planCode))}>
                      {org.planCode || "FREE"}
                    </span>
                    {org.paused && (
                      <span
                        className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
                        title={tNav("workspacePausedHint")}
                      >
                        {tNav("workspacePaused")}
                      </span>
                    )}
                    {/* The base/personal workspace is the user's fallback - it can never
                        be deleted (see OrganizationDangerZone). Flag it so users know the
                        missing Delete action is intentional, not a bug. */}
                    {org.isPersonal && (
                      <span
                        className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-theme-secondary text-theme-secondary border border-theme"
                        title={tNav("workspaceUndeletableHint")}
                      >
                        <Lock className="h-3 w-3" />
                        {tNav("workspaceUndeletable")}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-theme-secondary">
                    {org.memberCount} {org.memberCount === 1 ? "member" : "members"} &middot; {getRoleLabel(org.currentUserRole)}
                  </p>
                </div>
                {/* A paused (dormant) workspace cannot be entered, so it can never
                    become the default - hide the action entirely for it. */}
                {!org.isDefault && !org.paused && (
                  <Button size="sm" variant="ghost" onClick={() => handleSetDefault(org.id)} className="text-xs">
                    {t("organization.setDefault")}
                  </Button>
                )}
              </div>
              );
            })}
          </div>
        </section>
      )}

      {/* Upgrade CTA - additional workspaces are a PRO+ entitlement (shared-wallet model;
          PRO=3, TEAM=10, ENTERPRISE=unlimited; FREE/STARTER=1). Shown only when the current
          plan can't create more workspaces. Mirrors the Members-tab Team upsell but with the
          workspace-specific copy + "Upgrade to PRO" - the team card lives in the Members tab.
          Gates on the ACCOUNT-level entitlement (useWorkspaceEntitlements: active-org / personal
          / cloud-governed plan), unlike the Members card's supportsTeam which reads the VIEWED
          org's planCode - creating a workspace is an account capability, not a per-org one. */}
      {currentOrg && !canCreateWorkspace && (
        <section className="bg-theme-secondary rounded-xl p-6 border border-theme">
          <div className="flex items-start gap-4">
            <div className="w-10 h-10 bg-black dark:bg-white rounded-full flex items-center justify-center flex-shrink-0">
              <Building2 className="h-5 w-5 text-white dark:text-black" />
            </div>
            <div className="flex-1">
              <h3 className="text-lg font-semibold text-theme-primary mb-1">{tWs("workspaceTitle")}</h3>
              <p className="text-sm text-theme-secondary mb-4">{tWs("workspaceDescription")}</p>
              <Button size="sm" onClick={() => router.push("/app/settings/pricing")} className="h-8 px-4">
                {tWs("workspaceUpgradeCta")}
                <ArrowUpRight className="h-3.5 w-3.5 ml-1.5" />
              </Button>
            </div>
          </div>
        </section>
      )}

          </TabsContent>

          {/* ===== Security tab ===== */}
          <TabsContent value="security" className="mt-0 space-y-8">
      {currentOrg && (
        <OrganizationSsoPanel
          orgId={currentOrg.id}
          currentUserRole={currentOrg.currentUserRole}
          supportsTeam={supportsTeam}
        />
      )}

      {/* PR-4b - audit log (OWNER/ADMIN only, lazy-loaded on expand) */}
      {currentOrg && (
        <OrganizationAuditLogPanel
          orgId={currentOrg.id}
          currentUserRole={currentOrg.currentUserRole}
        />
      )}
          </TabsContent>

          {/* ===== Advanced tab ===== */}
          <TabsContent value="advanced" className="mt-0 space-y-8">
      {/* PR-4a/c + PR-cascade - danger zone (collapsed by default) */}
      {currentOrg && (
        <OrganizationDangerZone
          org={currentOrg}
          members={currentOrg.members ?? []}
          currentUserRole={currentOrg.currentUserRole}
          onChanged={fetchData}
        />
      )}
          </TabsContent>
        </Tabs>
      )}

      {/* Invite Modal */}
      {currentOrg && (
        <InviteMemberModal
          open={inviteModalOpen}
          onClose={() => setInviteModalOpen(false)}
          orgId={currentOrg.id}
          onInviteSent={fetchData}
        />
      )}

      {/* Manage Access Modal */}
      {accessModalMember && currentOrg && (
        <MemberAccessModal
          orgId={currentOrg.id}
          member={accessModalMember}
          onClose={() => setAccessModalMember(null)}
        />
      )}

      {/* PR11c - Manage Quota Cap Dialog (OWNER/ADMIN only) */}
      {quotaModalMember && currentOrg && (
        <MemberQuotaDialog
          orgId={currentOrg.id}
          member={quotaModalMember}
          onClose={() => setQuotaModalMember(null)}
        />
      )}

      {/* Remove-member confirmation - reuses the shared destructive delete
          dialog (same one as chat/datasource/fleet) instead of window.confirm. */}
      <ConfirmDeleteModal
        isOpen={memberToRemove !== null}
        title={t("organization.removeMember")}
        message={t("organization.removeMemberConfirm", { name: memberToRemove?.displayName ?? "" })}
        confirmLabel={tCommon("remove")}
        isLoading={removingMember}
        onConfirm={confirmRemoveMember}
        onCancel={() => {
          if (!removingMember) setMemberToRemove(null);
        }}
      />

      {/* Workspace-switch overlay - visual style aligned with
          components/billing/BillingCycleChangeModal.tsx (same backdrop blur,
          rounded-xl shadow-2xl card, icon-in-pill header, LoadingSpinner
          component). Shown during setDefault → invalidate × 8 → router.refresh
          roundtrip (1-3s in prod). Without this users hammer the radio and
          hit the 5s cooldown (audit-B-M2). */}
      {isSwitchingOrg && (
        <div className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center z-[9999] p-4">
          <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 border border-theme animate-in fade-in-0 zoom-in-95 duration-300">
            <div className="flex flex-col items-center text-center">
              <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mb-4">
                <RefreshCw className="h-7 w-7 text-theme-primary animate-spin" />
              </div>
              <h2 className="text-2xl font-semibold text-theme-primary">
                {t("organization.switchingWorkspace")}
              </h2>
              <p className="text-sm text-theme-secondary mt-1">
                {t("organization.switchingWorkspaceHint")}
              </p>
              <div className="mt-6">
                <LoadingSpinner size="lg" />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
