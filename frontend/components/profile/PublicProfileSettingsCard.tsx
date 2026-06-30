'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { useTranslations } from 'next-intl';
import { Check, Info, Loader2, Pencil, User } from 'lucide-react';
import { Link } from '@/i18n/navigation';
import { useUserProfile } from '@/hooks/useUserProfile';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';

/** Debounce window before an edit is auto-persisted (no manual Save button). */
const AUTOSAVE_DEBOUNCE_MS = 600;

/**
 * Account-settings section to edit the in-app profile (handle / bio / visibility). Styled to
 * match the "Account Information" section on the same page - a round-icon header and platform
 * form components (Label / Textarea / Select). Bio + visibility are auto-saved (debounced)
 * through useUserProfile → PUT /api/users/profile. The @handle follows the same
 * 1-change-per-week rule as the display name, so it is NEVER auto-saved (a debounced save of a
 * half-typed value would burn the weekly change) - it has an explicit Save button, is disabled
 * while in cooldown, and the payload of the debounced saves deliberately omits it. Profiles are
 * viewable IN-APP only (keyed by @handle) and never expose the real name - only the chosen
 * display name.
 */
export function PublicProfileSettingsCard() {
  const t = useTranslations('profile');
  const { profile, updateUserProfile } = useUserProfile();

  const [handleInput, setHandleInput] = useState('');
  const [bio, setBio] = useState('');
  const [visibility, setVisibility] = useState<'PUBLIC' | 'PRIVATE'>('PUBLIC');
  const [isSaving, setIsSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  // @handle cooldown state (same shape as the display-name rule on this page).
  const [canChangeHandle, setCanChangeHandle] = useState(true);
  const [handleNextChangeDate, setHandleNextChangeDate] = useState<string | null>(null);
  const [handleSaving, setHandleSaving] = useState(false);
  const [handleError, setHandleError] = useState<string | null>(null);
  // Pencil-toggled edit mode - mirrors the display-name field on this page: the
  // value reads as a disabled input until the pencil is clicked.
  const [handleEditing, setHandleEditing] = useState(false);

  const seededRef = useRef(false);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Always-current snapshot so the debounced flush reads the latest field values
  // without having to re-create the timer on every keystroke. Synced in an effect
  // (not during render) so the debounce - which fires well after commit - sees it.
  const stateRef = useRef({ bio, visibility });
  useEffect(() => {
    stateRef.current = { bio, visibility };
  }, [bio, visibility]);

  // Seed the form ONCE from the loaded profile. Re-seeding on every profile change
  // would clobber an in-flight edit when the post-save refetch lands mid-typing.
  useEffect(() => {
    if (seededRef.current || !profile) return;
    setHandleInput(profile.handle ?? '');
    setBio(profile.bio ?? '');
    setVisibility(profile.profileVisibility === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC');
    seededRef.current = true;
  }, [profile]);

  const fetchHandleStatus = useCallback(async () => {
    try {
      const status = await unifiedApiService.getHandleStatus();
      setCanChangeHandle(status.canChange);
      setHandleNextChangeDate(status.nextChangeDate);
    } catch {
      // Silently fail - assume can change; the backend still enforces the rule.
    }
  }, []);

  useEffect(() => {
    void fetchHandleStatus();
  }, [fetchHandleStatus]);

  // Link to the in-app profile by its public @handle (never the numeric user/tenant id).
  // Uses the SAVED handle (a taken/invalid edit is ignored server-side, so the link stays valid).
  const handle: string | undefined = profile?.handle ?? undefined;

  // Bio + visibility only - the @handle is excluded from the debounced auto-save on purpose
  // (its weekly cooldown must never be consumed by a half-typed value).
  const doSave = useCallback(async () => {
    const snap = stateRef.current;
    setIsSaving(true);
    setSaved(false);
    // useUserProfile.updateUserProfile catches its own errors and resolves to null
    // on failure (it never throws), so a plain await + result check suffices.
    const result = await updateUserProfile({
      bio: snap.bio,
      profileVisibility: snap.visibility,
    } as never);
    setIsSaving(false);
    if (result) setSaved(true);
  }, [updateUserProfile]);

  // Explicit @handle save. The backend silently keeps the current handle when the new one is
  // invalid/taken (200 with an unchanged handle) and 429s during the cooldown (→ null result),
  // so both outcomes are detected here and surfaced.
  const saveHandle = useCallback(async () => {
    const wanted = handleInput.trim();
    const previous = profile?.handle ?? '';
    // Case-variants of the current handle slugify back to it server-side - treat as a
    // silent no-op instead of a false "taken/invalid" error.
    if (!wanted || wanted.toLowerCase() === previous.toLowerCase()) {
      setHandleInput(previous);
      setHandleEditing(false);
      return;
    }
    setHandleSaving(true);
    setHandleError(null);
    const result = await updateUserProfile({ handle: wanted } as never);
    setHandleSaving(false);
    if (!result) {
      // Failed save - most likely the 429 cooldown; re-sync the status to show the date.
      await fetchHandleStatus();
      setHandleError(t('handleSaveError'));
      return;
    }
    if (!result.handle || result.handle === previous) {
      // 200 but the handle was not applied → invalid or already taken (server-side rule).
      setHandleInput(previous);
      setHandleError(t('handleTakenOrInvalid'));
      return;
    }
    setHandleInput(result.handle); // reflect server-side slugification (e.g. spaces → _)
    setSaved(true);
    setHandleEditing(false);
    await fetchHandleStatus(); // the change just consumed the weekly slot
  }, [handleInput, profile?.handle, updateUserProfile, fetchHandleStatus, t]);

  const cancelHandleEdit = useCallback(() => {
    setHandleInput(profile?.handle ?? '');
    setHandleError(null);
    setHandleEditing(false);
  }, [profile?.handle]);

  // Keep the latest doSave behind a ref so the unmount-flush effect can run once
  // (empty deps) without going stale.
  const doSaveRef = useRef(doSave);
  useEffect(() => {
    doSaveRef.current = doSave;
  }, [doSave]);

  const scheduleSave = useCallback(() => {
    setSaved(false);
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      saveTimerRef.current = null;
      void doSave();
    }, AUTOSAVE_DEBOUNCE_MS);
  }, [doSave]);

  // Flush a pending save on unmount so a quick navigation doesn't drop the last edit.
  useEffect(() => {
    return () => {
      if (saveTimerRef.current) {
        clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
        void doSaveRef.current();
      }
    };
  }, []);

  const handleDirty = handleInput.trim() !== (profile?.handle ?? '') && handleInput.trim() !== '';
  // Date formatting follows the browser locale (never hardcoded).
  const nextChangeLabel = handleNextChangeDate
    ? parseUtcAware(handleNextChangeDate).toLocaleDateString(
        getClientLocale(),
        { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' },
      )
    : null;

  return (
    <div className="space-y-6">
      {/* Header - mirrors the "Account Information" section header on this page. */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div className="flex items-center space-x-3">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center">
            <User className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-theme-primary">{t('sectionTitle')}</h3>
            <p className="text-sm text-theme-secondary">{t('sectionSubtitle')}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          {isSaving || handleSaving ? (
            <span className="flex items-center gap-1.5 text-xs text-theme-secondary">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              {t('saving')}
            </span>
          ) : saved ? (
            <span className="flex items-center gap-1.5 text-xs text-green-600 dark:text-green-400">
              <Check className="h-3.5 w-3.5" />
              {t('saved')}
            </span>
          ) : null}
          {handle && (
            <Link
              href={`/app/u/${handle}`}
              className="inline-flex flex-shrink-0 items-center gap-1.5 text-sm text-[var(--accent-primary)] hover:underline"
            >
              <User className="h-3.5 w-3.5" />
              {t('viewPublicProfile')}
            </Link>
          )}
        </div>
      </div>

      <div className="space-y-4">
        {/* Handle (@) - the public, URL-safe id used at /app/u/{handle}. Explicit save only:
            it follows the same 1-change-per-week rule as the display name, and the SAME edit
            UX as the display-name field above: ⓘ tooltip + a disabled input with a pencil
            toggle, then Save / Cancel. */}
        <div className="space-y-2">
          <div className="flex items-center gap-1.5">
            <Label htmlFor="profile-handle">{t('handle')}</Label>
            <TooltipProvider delayDuration={0}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Info className="h-3.5 w-3.5 text-theme-secondary cursor-help" data-testid="profile-handle-info" />
                </TooltipTrigger>
                <TooltipContent side="top" className="max-w-xs">
                  <p className="text-xs">
                    {!canChangeHandle && nextChangeLabel
                      ? t('handleCooldownHint', { date: nextChangeLabel })
                      : t('handleHint')}
                  </p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
          {handleEditing ? (
            <div className="space-y-2">
              <div className="flex items-center gap-1.5">
                <span className="text-sm text-theme-muted">@</span>
                <Input
                  id="profile-handle"
                  data-testid="profile-handle"
                  value={handleInput}
                  onChange={(e) => {
                    setHandleInput(e.target.value);
                    setHandleError(null);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && handleDirty && !handleSaving) {
                      e.preventDefault();
                      void saveHandle();
                    }
                  }}
                  disabled={handleSaving}
                  autoFocus
                  maxLength={32}
                  placeholder={t('handlePlaceholder')}
                />
              </div>
              {handleError ? (
                <p className="text-xs text-red-500">{handleError}</p>
              ) : (
                // Same inline hint the display-name field shows while editing.
                <p className="text-xs text-theme-muted">{t('handleHint')}</p>
              )}
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  data-testid="profile-handle-save"
                  onClick={() => void saveHandle()}
                  disabled={handleSaving || !handleDirty}
                  className="h-8 px-3"
                >
                  {handleSaving
                    ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    : <Check className="h-3.5 w-3.5 mr-1" />}
                  {t('save')}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  data-testid="profile-handle-cancel"
                  onClick={cancelHandleEdit}
                  disabled={handleSaving}
                  className="h-8 px-3"
                >
                  {t('cancel')}
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <Input
                id="profile-handle"
                data-testid="profile-handle"
                value={handleInput ? `@${handleInput}` : ''}
                disabled
                placeholder={t('handlePlaceholder')}
                className="flex-1 bg-muted/30 disabled:opacity-100 disabled:cursor-default disabled:text-foreground"
              />
              <Button
                size="icon"
                variant="ghost"
                data-testid="profile-handle-edit"
                onClick={() => setHandleEditing(true)}
                disabled={!canChangeHandle}
                className="h-9 w-9 shrink-0"
                title={!canChangeHandle && nextChangeLabel
                  ? t('handleCooldownHint', { date: nextChangeLabel })
                  : undefined}
              >
                <Pencil className="h-3.5 w-3.5" />
              </Button>
            </div>
          )}
          {!canChangeHandle && nextChangeLabel && (
            <p className="text-xs text-theme-muted" data-testid="profile-handle-cooldown">
              {t('handleCooldownHint', { date: nextChangeLabel })}
            </p>
          )}
        </div>

        {/* Bio */}
        <div className="space-y-2">
          <Label htmlFor="profile-bio">{t('bio')}</Label>
          <Textarea
            id="profile-bio"
            data-testid="profile-bio"
            value={bio}
            onChange={(e) => {
              setBio(e.target.value);
              scheduleSave();
            }}
            maxLength={500}
            rows={4}
            placeholder={t('bioPlaceholder')}
            className="resize-none"
          />
        </div>

        {/* Visibility - platform Select, same dropdown styling as the other settings. */}
        <div className="space-y-2">
          <Label>{t('visibility')}</Label>
          <Select
            value={visibility}
            onValueChange={(v) => {
              setVisibility(v === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC');
              scheduleSave();
            }}
          >
            <SelectTrigger className="w-full sm:w-[200px]" data-testid="profile-visibility">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="PUBLIC">{t('visibilityPublic')}</SelectItem>
              <SelectItem value="PRIVATE">{t('visibilityPrivate')}</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-theme-muted">{t('visibilityHint')}</p>
        </div>
      </div>
    </div>
  );
}

export default PublicProfileSettingsCard;
