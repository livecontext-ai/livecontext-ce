"use client";

/**
 * Danger zone for Settings>Organization (PR-4a/c + PR-cascade frontend).
 *
 * Surfaces the destructive backend endpoints as visible buttons gated by
 * the user's role + the org's personal flag. Long explanations live behind
 * an (i) popover next to each row title - keeps the surface compact.
 *
 *   - LEAVE       (visible for MEMBER / ADMIN / VIEWER ; OWNER must transfer first)
 *   - TRANSFER    (visible only for OWNER, requires picking a target member)
 *   - DELETE      (visible only for OWNER on NON-personal orgs, GitHub-style name confirm)
 *                 personal orgs render the row as disabled with the explanation
 *                 in the (i) popover
 *
 * Confirmation flows use styled in-page modals (same pattern as
 * CancellationModal) - NOT window.confirm / window.prompt / window.alert,
 * which ignore the app theme and look broken in dark mode.
 */

import { useCallback, useState } from "react";
import { useRouter, useParams } from "next/navigation";
import {
  organizationApi,
  type Organization,
  type OrganizationMember,
  type OrganizationRole,
} from "@/lib/api/organization-api";
import { useCurrentOrgStore } from "@/lib/stores/current-org-store";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { AlertTriangle, LogOut, Crown, Trash2, Info, ChevronDown, ChevronRight } from "lucide-react";
import LoadingSpinner from "@/components/LoadingSpinner";

type ModalKind = "leave" | "transfer" | "delete" | null;

interface Props {
  org: Organization;
  members: OrganizationMember[];
  currentUserRole: OrganizationRole | undefined;
  /** Called after a destructive action so the page can refetch. */
  onChanged: () => void;
}

export default function OrganizationDangerZone({
  org,
  members,
  currentUserRole,
  onChanged,
}: Props) {
  const router = useRouter();
  const params = useParams();
  const locale = (params?.locale as string) ?? "en";

  // Collapsed by default - destructive actions should be intentional, not
  // accidentally hit while scrolling. Click the header to reveal the rows.
  const [expanded, setExpanded] = useState(false);
  const [openModal, setOpenModal] = useState<ModalKind>(null);

  const isOwner = currentUserRole === "OWNER";
  const isMember = !isOwner && !!currentUserRole;
  // The OWNER can soft-delete a non-personal workspace: a 30-day grace window (restorable)
  // then a hard-purge that deletes the operational data but RETAINS the financial ledger
  // (the org row is tombstoned so owner-pays references stay valid - ADR-009). The personal
  // workspace is the user's fallback and is never deletable (rendered as a disabled row).
  const canDelete = isOwner && !org.isPersonal;

  // Each handler just opens its modal - the API call + UI feedback lives
  // inside the modal component below so error/loading state doesn't leak
  // back into the row buttons.
  const onLeaveSuccess = useCallback(() => {
    setOpenModal(null);
    router.push(`/${locale}/app/settings/organization`);
    onChanged();
  }, [router, locale, onChanged]);

  const onTransferSuccess = useCallback(() => {
    setOpenModal(null);
    onChanged();
  }, [onChanged]);

  const onDeleteSuccess = useCallback(() => {
    setOpenModal(null);
    // If the user just deleted the workspace they were *currently in*, the active-org
    // store still points at it. The deleted org keeps coming back from
    // /organizations/me (owner-only, pendingDeletion) so a refetch alone won't move
    // the user off it - clear the active workspace so the gateway falls back to the
    // user's default/personal workspace instead of stranding them on the deleted one
    // (prod bug 2026-06-06). onChanged() then refetches + reconciles to the default.
    if (useCurrentOrgStore.getState().currentOrgId === org.id) {
      useCurrentOrgStore.getState().clear();
    }
    router.push(`/${locale}/app/settings/organization`);
    onChanged();
  }, [router, locale, onChanged, org.id]);

  // Members see "Leave"; OWNERs see "Delete" (or the disabled personal-workspace row).
  // Only skip when the caller has no role at all.
  if (!currentUserRole) {
    return null;
  }

  return (
    <>
      <Collapsible open={expanded} onOpenChange={setExpanded} asChild>
        <section className="overflow-hidden rounded-xl border border-red-200 dark:border-red-800/50">
          <CollapsibleTrigger asChild>
            <button
              type="button"
              aria-expanded={expanded}
              className="flex w-full items-center justify-between gap-4 p-6 text-left transition-colors hover:bg-red-50/70 dark:hover:bg-red-950/20"
            >
              <div className="flex min-w-0 items-center gap-3">
                <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/30">
                  <AlertTriangle className="h-5 w-5 text-red-600 dark:text-red-400" />
                </div>
                <div className="min-w-0">
                  <h2 className="text-lg font-semibold text-red-900 dark:text-red-300">
                    Danger zone
                  </h2>
                  <p className="text-sm text-red-700/80 dark:text-red-400/70">
                    Destructive actions for this workspace
                  </p>
                </div>
              </div>
              {expanded ? (
                <ChevronDown className="h-5 w-5 flex-shrink-0 text-red-600 dark:text-red-400" />
              ) : (
                <ChevronRight className="h-5 w-5 flex-shrink-0 text-red-600 dark:text-red-400" />
              )}
            </button>
          </CollapsibleTrigger>
          <CollapsibleContent className="space-y-4 border-t border-red-100 px-6 pb-6 pt-4 dark:border-red-900/30">
            {isMember && (
              <DangerRow
                icon={<LogOut className="h-4 w-4" />}
                title="Leave organization"
                info={`Remove yourself from ${org.name}. An OWNER or ADMIN can re-invite you later.`}
                buttonLabel="Leave"
                onClick={() => setOpenModal("leave")}
              />
            )}
            {/* Transfer ownership stays DISABLED on purpose (verified 2026-07-02):
                the backend flips role + owner_id but Stripe subscription migration
                is out of scope (see OrganizationMemberService.transferOwnership
                Javadoc + CREDIT_CONVERGENCE_PLAN.md "Org ownership transfer" -
                the rebind queue is not implemented). Re-enabling now would let an
                owner transfer and silently drop the org to FREE (getOwnerPlan
                resolves via the NEW owner, who has no subscription). Kept as
                `false &&` so the modal + copy survive the future re-enable.
                Delete, whose own blocker (credit-ledger orphaning) was solved,
                was re-enabled in 3c14ccb9b. */}
            {false && isOwner && (
              <DangerRow
                icon={<Crown className="h-4 w-4" />}
                title="Transfer ownership"
                info="Promote another member to OWNER. You become ADMIN. Your billing subscription stays on your account - the new owner can take it over later."
                buttonLabel="Transfer"
                onClick={() => setOpenModal("transfer")}
              />
            )}
            {canDelete && (
              <DangerRow
                icon={<Trash2 className="h-4 w-4" />}
                title="Delete workspace"
                info="Soft-delete this workspace. You have a 30-day grace period to restore it; after that the data is permanently purged (billing history is retained)."
                buttonLabel="Delete"
                onClick={() => setOpenModal("delete")}
                destructive
              />
            )}
            {isOwner && org.isPersonal && (
              <DangerRow
                icon={<Trash2 className="h-4 w-4" />}
                title="Delete organization"
                info="Your personal organization cannot be deleted - it is the fallback that keeps you a member of at least one org."
                buttonLabel="Delete"
                onClick={() => {}}
                disabled
                destructive
              />
            )}
          </CollapsibleContent>
        </section>
      </Collapsible>

      {openModal === "leave" && (
        <LeaveOrganizationModal
          org={org}
          onClose={() => setOpenModal(null)}
          onSuccess={onLeaveSuccess}
        />
      )}
      {openModal === "transfer" && (
        <TransferOwnershipModal
          org={org}
          members={members}
          onClose={() => setOpenModal(null)}
          onSuccess={onTransferSuccess}
        />
      )}
      {openModal === "delete" && (
        <DeleteOrganizationModal
          org={org}
          onClose={() => setOpenModal(null)}
          onSuccess={onDeleteSuccess}
        />
      )}
    </>
  );
}

function DangerRow({
  icon,
  title,
  info,
  buttonLabel,
  onClick,
  disabled,
  destructive,
}: {
  icon: React.ReactNode;
  title: string;
  info: string;
  buttonLabel: string;
  onClick: () => void;
  disabled?: boolean;
  destructive?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 pb-4 last:pb-0 border-b last:border-b-0 border-red-100 dark:border-red-900/30">
      <div className="flex items-center gap-2 text-sm font-medium text-theme-primary">
        <span className="text-red-600 dark:text-red-400">{icon}</span>
        <span>{title}</span>
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label={`More info about ${title.toLowerCase()}`}
              className="p-1 rounded-md hover:bg-black/5 dark:hover:bg-white/5 text-theme-muted hover:text-theme-secondary transition-colors"
            >
              <Info className="h-3.5 w-3.5" />
            </button>
          </PopoverTrigger>
          <PopoverContent
            side="top"
            align="start"
            className="w-80 p-4 text-sm bg-theme-primary border-theme leading-relaxed text-theme-secondary"
          >
            {info}
          </PopoverContent>
        </Popover>
      </div>
      <Button
        size="sm"
        variant={destructive ? "destructive" : "outline"}
        onClick={onClick}
        disabled={disabled}
      >
        {buttonLabel}
      </Button>
    </div>
  );
}

// ============================================================================
// Confirmation modals - same shell pattern as components/billing/CancellationModal.tsx.
// Backdrop, theme-aware container, AlertTriangle icon, Cancel + destructive buttons.
// Each modal owns its own busy / error state so the row buttons stay clean.
// ============================================================================

function ModalShell({
  children,
  onClose,
  disableClose,
}: {
  children: React.ReactNode;
  onClose: () => void;
  disableClose?: boolean;
}) {
  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center z-[9999] p-4"
      onClick={disableClose ? undefined : onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <p className="text-sm text-red-600 dark:text-red-400 mb-4 flex items-center justify-center gap-2">
      <AlertTriangle className="h-4 w-4" />
      {message}
    </p>
  );
}

function LeaveOrganizationModal({
  org,
  onClose,
  onSuccess,
}: {
  org: Organization;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await organizationApi.leaveOrganization(org.id);
      onSuccess();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to leave organization");
      setBusy(false);
    }
  };

  return (
    <ModalShell onClose={onClose} disableClose={busy}>
      <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
        <LogOut className="h-8 w-8 text-red-600 dark:text-red-400" />
      </div>

      <h2 className="text-2xl font-semibold text-theme-primary mb-2">
        Leave {org.name}?
      </h2>

      <p className="text-sm text-theme-secondary mb-6">
        You&apos;ll lose access to its workflows, agents and data. An OWNER or ADMIN can re-invite you later.
      </p>

      {error && <ErrorBanner message={error} />}

      <div className="flex gap-3">
        <Button onClick={onClose} variant="outline" className="flex-1" disabled={busy}>
          Cancel
        </Button>
        <Button onClick={confirm} variant="destructive" className="flex-1" disabled={busy}>
          {busy ? (
            <>
              <LoadingSpinner size="xs" />
              Leaving…
            </>
          ) : (
            "Leave"
          )}
        </Button>
      </div>
    </ModalShell>
  );
}

function TransferOwnershipModal({
  org,
  members,
  onClose,
  onSuccess,
}: {
  org: Organization;
  members: OrganizationMember[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const candidates = members.filter((m) => m.role !== "OWNER");
  const [targetUserId, setTargetUserId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const target = candidates.find((m) => m.userId === targetUserId);

  const confirm = async () => {
    if (!target) return;
    setBusy(true);
    setError(null);
    try {
      await organizationApi.transferOwnership(org.id, target.userId);
      onSuccess();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to transfer ownership");
      setBusy(false);
    }
  };

  return (
    <ModalShell onClose={onClose} disableClose={busy}>
      <div className="w-16 h-16 bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
        <Crown className="h-8 w-8 text-amber-600 dark:text-amber-400" />
      </div>

      <h2 className="text-2xl font-semibold text-theme-primary mb-2">
        Transfer ownership of {org.name}
      </h2>

      <p className="text-sm text-theme-secondary mb-4">
        Pick the new OWNER. You will be demoted to ADMIN. Your billing subscription stays on your account - the new owner can take it over later.
      </p>

      {candidates.length === 0 ? (
        <p className="text-sm text-theme-secondary mb-6">
          No other member to transfer ownership to. Invite someone first, then come back here.
        </p>
      ) : (
        <div className="space-y-2 mb-4 text-left">
          {candidates.map((m) => {
            const selected = m.userId === targetUserId;
            return (
              <button
                key={m.userId}
                type="button"
                onClick={() => setTargetUserId(m.userId)}
                className={`w-full text-left px-4 py-3 rounded-lg border text-sm transition-colors ${
                  selected
                    ? "border-gray-400 bg-gray-50 dark:border-gray-400 dark:bg-gray-700 text-gray-900 dark:text-white"
                    : "border-gray-200 dark:border-gray-600 hover:border-gray-300 dark:hover:border-gray-500 text-gray-700 dark:text-gray-300"
                }`}
              >
                <div className="font-medium">{m.displayName || m.email}</div>
                <div className="text-xs text-gray-500 dark:text-gray-400">{m.role}</div>
              </button>
            );
          })}
        </div>
      )}

      {error && <ErrorBanner message={error} />}

      <div className="flex gap-3">
        <Button onClick={onClose} variant="outline" className="flex-1" disabled={busy}>
          Cancel
        </Button>
        <Button
          onClick={confirm}
          variant="destructive"
          className="flex-1"
          disabled={busy || !target}
        >
          {busy ? (
            <>
              <LoadingSpinner size="xs" />
              Transferring…
            </>
          ) : (
            "Transfer"
          )}
        </Button>
      </div>
    </ModalShell>
  );
}

function DeleteOrganizationModal({
  org,
  onClose,
  onSuccess,
}: {
  org: Organization;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [typedName, setTypedName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameMatches = typedName === org.name;

  const confirm = async () => {
    if (!nameMatches) return;
    setBusy(true);
    setError(null);
    try {
      await organizationApi.deleteOrganization(org.id, typedName);
      onSuccess();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to delete organization");
      setBusy(false);
    }
  };

  return (
    <ModalShell onClose={onClose} disableClose={busy}>
      <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
        <Trash2 className="h-8 w-8 text-red-600 dark:text-red-400" />
      </div>

      <h2 className="text-2xl font-semibold text-theme-primary mb-2">
        Delete {org.name}?
      </h2>

      <p className="text-sm text-theme-secondary mb-4">
        This soft-deletes the workspace - 30-day grace period before hard purge. To confirm, type the exact name below.
      </p>

      <div className="mb-6 text-left">
        <label htmlFor="delete-org-name" className="text-sm text-theme-secondary mb-1.5 block">
          Type <span className="font-mono font-medium text-theme-primary">{org.name}</span> to confirm
        </label>
        <Input
          id="delete-org-name"
          type="text"
          autoFocus
          value={typedName}
          onChange={(e) => setTypedName(e.target.value)}
          placeholder={org.name}
          disabled={busy}
        />
      </div>

      {error && <ErrorBanner message={error} />}

      <div className="flex gap-3">
        <Button onClick={onClose} variant="outline" className="flex-1" disabled={busy}>
          Cancel
        </Button>
        <Button
          onClick={confirm}
          variant="destructive"
          className="flex-1"
          disabled={busy || !nameMatches}
        >
          {busy ? (
            <>
              <LoadingSpinner size="xs" />
              Deleting…
            </>
          ) : (
            "Delete"
          )}
        </Button>
      </div>
    </ModalShell>
  );
}
