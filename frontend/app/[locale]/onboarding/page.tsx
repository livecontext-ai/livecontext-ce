'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';
import { useTranslations, useLocale } from 'next-intl';
import { useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/api';
import { IS_CE } from '@/lib/edition';
import { track } from '@/lib/analytics/analytics';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Checkbox } from '@/components/ui/checkbox';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { MARKETING_CONSENT_QUERY_KEY } from '@/components/settings/MarketingConsentSetting';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import {
  User,
  Briefcase,
  Sparkles,
  Target,
  CheckCircle2,
  AlertCircle,
  ArrowRight,
  ArrowLeft,
  Building2,
  Rocket,
  LogOut,
  Mail
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { APP_SUGGESTIONS_FLAG, armWelcomeGift } from '@/lib/onboarding/welcomeGiftHandoff';
import { leaveForChat } from '@/lib/navigation/leaveForChat';
import { assignLocation } from '@/lib/navigation/assignLocation';
import {
  captureCeLinkFromSearch,
  ceLinkPricingPath,
  continuePendingCeLink,
  hasPendingCeLink,
} from '@/lib/cloud-link/pendingCeLink';

/**
 * Ask the app tree to show the suggested applications on the next screen.
 *
 * <p>Guarded on its own, like {@link armWelcomeGift}, and that is the point:
 * a tab with site data blocked throws on write, and two unguarded writes in one
 * `try` are a package. A store that rejects the first key would skip the second
 * in silence, so a reader who completed onboarding correctly would get neither
 * modal with nothing anywhere to say why. Guarded separately, one refused write
 * costs exactly one modal.
 *
 * <p>Neither write can strand the user: the completion has already set
 * `pageState = 'completed'`, and the effect watching that state is what
 * redirects to chat.
 */
function armAppSuggestions(): void {
  try {
    sessionStorage.setItem(APP_SUGGESTIONS_FLAG, '1');
  } catch {
    // No suggestions in this tab. Nothing downstream waits on a flag that was
    // never written.
  }
}

// Types
interface OnboardingData {
  displayName: string;
  profession: string;
  companySize: string;
  primaryGoal: string;
  toolsUsed: string[];
  previousTool: string;
  referralSource: string;
  currentStep: number;
}

interface OnboardingResponse {
  needsOnboarding: boolean;
  completed: boolean;
  skipped: boolean;
  emailVerified?: boolean;
  currentStep: number;
  displayName?: string;
  profession?: string;
  companySize?: string;
  primaryGoal?: string | null;
  toolsUsed?: string[];
  previousTool?: string | null;
  referralSource?: string | null;
}

// Constants
const COMPANY_SIZES = [
  { value: 'solo', label: 'Solo' },
  { value: 'startup', label: '1-10' },
  { value: 'small', label: '11-50' },
  { value: 'medium', label: '51-200' },
  { value: 'enterprise', label: '200+' },
];

const CE_TEAM_SIZES = [
  { value: 'solo', labelKey: 'ce.teamSizes.solo' },
  { value: 'team', labelKey: 'ce.teamSizes.team' },
  { value: 'department', labelKey: 'ce.teamSizes.department' },
  { value: 'community', labelKey: 'ce.teamSizes.community' },
  { value: 'enterprise', labelKey: 'ce.teamSizes.enterprise' },
];

// Roles/Professions - broader audience
const ROLES = [
  { value: 'sales', labelKey: 'roles.sales' },
  { value: 'marketing', labelKey: 'roles.marketing' },
  { value: 'customer-success', labelKey: 'roles.customerSuccess' },
  { value: 'support', labelKey: 'roles.support' },
  { value: 'ecommerce', labelKey: 'roles.ecommerce' },
  { value: 'operations', labelKey: 'roles.operations' },
  { value: 'product', labelKey: 'roles.product' },
  { value: 'engineering', labelKey: 'roles.engineering' },
  { value: 'data-analytics', labelKey: 'roles.dataAnalytics' },
  { value: 'finance', labelKey: 'roles.finance' },
  { value: 'hr', labelKey: 'roles.hr' },
  { value: 'founder', labelKey: 'roles.founder' },
  { value: 'freelancer', labelKey: 'roles.freelancer' },
  { value: 'other', labelKey: 'roles.other' },
];

const CE_ROLES = [
  { value: 'instance-admin', labelKey: 'ce.roles.instanceAdmin' },
  { value: 'maintainer', labelKey: 'ce.roles.maintainer' },
  { value: 'workflow-builder', labelKey: 'ce.roles.workflowBuilder' },
  { value: 'developer', labelKey: 'ce.roles.developer' },
  { value: 'operations', labelKey: 'ce.roles.operations' },
  { value: 'community', labelKey: 'ce.roles.community' },
  { value: 'other', labelKey: 'ce.roles.other' },
];

// Primary goal - the ONE thing the user wants to automate first (single choice)
const PRIMARY_GOALS = [
  { value: 'email-follow-ups', labelKey: 'primaryGoals.emailFollowUps' },
  { value: 'content-publishing', labelKey: 'primaryGoals.contentPublishing' },
  { value: 'lead-generation', labelKey: 'primaryGoals.leadGeneration' },
  { value: 'customer-support', labelKey: 'primaryGoals.customerSupport' },
  { value: 'reporting', labelKey: 'primaryGoals.reporting' },
  { value: 'data-sync', labelKey: 'primaryGoals.dataSync' },
  { value: 'monitoring-alerts', labelKey: 'primaryGoals.monitoringAlerts' },
  { value: 'ai-assistant', labelKey: 'primaryGoals.aiAssistant' },
  { value: 'other', labelKey: 'primaryGoals.other' },
];

// CE primary goals reuse the self-hosted use-case vocabulary (single choice)
const CE_USE_CASES = [
  { value: 'internal-automation', labelKey: 'ce.useCases.internalAutomation' },
  { value: 'private-assistants', labelKey: 'ce.useCases.privateAssistants' },
  { value: 'data-pipelines', labelKey: 'ce.useCases.dataPipelines' },
  { value: 'tool-orchestration', labelKey: 'ce.useCases.toolOrchestration' },
  { value: 'team-workspaces', labelKey: 'ce.useCases.teamWorkspaces' },
  { value: 'marketplace-publishing', labelKey: 'ce.useCases.marketplacePublishing' },
  { value: 'evaluation-sandbox', labelKey: 'ce.useCases.evaluationSandbox' },
  { value: 'other', labelKey: 'ce.useCases.other' },
];

// Tools the user already works with (multi choice, both editions)
const TOOLS = [
  { value: 'gmail', labelKey: 'tools.gmail' },
  { value: 'outlook', labelKey: 'tools.outlook' },
  { value: 'google-sheets', labelKey: 'tools.googleSheets' },
  { value: 'slack', labelKey: 'tools.slack' },
  { value: 'notion', labelKey: 'tools.notion' },
  { value: 'hubspot', labelKey: 'tools.hubspot' },
  { value: 'salesforce', labelKey: 'tools.salesforce' },
  { value: 'shopify', labelKey: 'tools.shopify' },
  { value: 'stripe', labelKey: 'tools.stripe' },
  { value: 'github', labelKey: 'tools.github' },
  { value: 'discord', labelKey: 'tools.discord' },
  { value: 'telegram', labelKey: 'tools.telegram' },
  { value: 'linkedin', labelKey: 'tools.linkedin' },
  { value: 'airtable', labelKey: 'tools.airtable' },
  { value: 'other', labelKey: 'tools.other' },
];

// What the user automates with today (single choice, both editions)
const PREVIOUS_TOOLS = [
  { value: 'none', labelKey: 'previousTools.none' },
  { value: 'zapier-make', labelKey: 'previousTools.zapierMake' },
  { value: 'n8n', labelKey: 'previousTools.n8n' },
  { value: 'custom-code', labelKey: 'previousTools.customCode' },
  { value: 'other-platform', labelKey: 'previousTools.otherPlatform' },
];

// How the user heard about LiveContext (single choice, both editions)
const REFERRAL_SOURCES = [
  { value: 'search', labelKey: 'referralSources.search' },
  { value: 'social', labelKey: 'referralSources.social' },
  { value: 'word-of-mouth', labelKey: 'referralSources.wordOfMouth' },
  { value: 'github', labelKey: 'referralSources.github' },
  { value: 'article', labelKey: 'referralSources.article' },
  { value: 'ai-assistant', labelKey: 'referralSources.aiAssistant' },
  { value: 'other', labelKey: 'referralSources.other' },
];

const valuesOf = (options: Array<{ value: string }>) => new Set(options.map(option => option.value));

const normalizeSelection = (value: string | null | undefined, allowedValues: Set<string>): string => {
  if (!value) return '';
  return allowedValues.has(value) ? value : '';
};

const normalizeSelections = (values: string[] | undefined, allowedValues: Set<string>): string[] => {
  if (!values) return [];
  const normalized: string[] = [];
  for (const value of values) {
    if (allowedValues.has(value) && !normalized.includes(value)) {
      normalized.push(value);
    }
  }
  return normalized;
};

const ROLE_VALUES = valuesOf(ROLES);
const CE_ROLE_VALUES = valuesOf(CE_ROLES);
const ALL_ROLE_VALUES = new Set([...ROLE_VALUES, ...CE_ROLE_VALUES]);
const COMPANY_SIZE_VALUES = valuesOf(COMPANY_SIZES);
const CE_TEAM_SIZE_VALUES = valuesOf(CE_TEAM_SIZES);
const GOAL_VALUES = valuesOf(PRIMARY_GOALS);
const CE_GOAL_VALUES = valuesOf(CE_USE_CASES);
const TOOL_VALUES = valuesOf(TOOLS);
const PREVIOUS_TOOL_VALUES = valuesOf(PREVIOUS_TOOLS);
const REFERRAL_SOURCE_VALUES = valuesOf(REFERRAL_SOURCES);

// Steps 1-3 (profile, first goal + tools, previous tool + referral), then the chat. Connecting
// apps is not part of onboarding: the setup checklist in the sidebar carries it ("Connect an
// app"), so onboarding stays short. It had been a panel shown after completion; it was removed
// because the checklist made it a second place asking the same thing.
const ONBOARDING_STEPS = 3;
const RESEND_COOLDOWN_SECONDS = 60;
/** When the last verification code was sent, so a refresh respects the cooldown. */
const EMAIL_CODE_LAST_SENT_KEY = 'email_verification_last_sent';

type PageState = 'loading' | 'needs_auth' | 'ready' | 'completed' | 'error';

// Extract name from email if needed, then sanitize
const sanitizeDisplayName = (value: string): string => {
  let name = value;

  // If it looks like an email, extract the part before @
  if (value.includes('@')) {
    name = value.split('@')[0];
  }

  // Remove special characters but keep letters (including accented), numbers, spaces, hyphens, underscores
  return name.replace(/[^a-zA-ZÀ-ÿ0-9\s\-_]/g, '');
};

export default function OnboardingPage() {
  const locale = useLocale();
  const { user, isLoading: authLoading, isAuthenticated, loginWithRedirect, logout } = useAuth();
  const t = useTranslations('onboarding');

  const queryClient = useQueryClient();

  const [pageState, setPageState] = useState<PageState>('loading');
  const [currentStep, setCurrentStep] = useState(1);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [displayNameError, setDisplayNameError] = useState<string | null>(null);
  const [checkingDisplayName, setCheckingDisplayName] = useState(false);
  const [displayNameAvailable, setDisplayNameAvailable] = useState(false);
  // The availability check itself failed (no answer), as opposed to "taken".
  const [displayNameCheckFailed, setDisplayNameCheckFailed] = useState(false);
  const [customRole, setCustomRole] = useState('');
  const initRef = useRef(false);

  // Email verification state
  const [emailVerified, setEmailVerified] = useState<boolean | null>(null);
  const [otpDigits, setOtpDigits] = useState<string[]>(['', '', '', '', '', '']);
  const [verifying, setVerifying] = useState(false);
  const [codeSent, setCodeSent] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);
  const [emailVerificationSuccess, setEmailVerificationSuccess] = useState(false);
  const otpInputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const codeSentOnMountRef = useRef(false);
  // Sign-out: the ref blocks a second click in the same frame, the state drives the UI.
  const signingOutRef = useRef(false);
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState<string | null>(null);
  // The departure event is sent once, even if a failed redirect is retried.
  const departureCountedRef = useRef(false);
  // Only the newest availability check may write its result.
  const checkTicketRef = useRef(0);
  // A save or skip was refused and has not succeeded since: shows the form sign-out.
  const [submitFailed, setSubmitFailed] = useState(false);
  // Marketing e-mails, cloud only. Unchecked by default: consent is opt-in. Sent when the
  // person leaves step 1 (advance or skip), not on every click.
  const [marketingConsent, setMarketingConsent] = useState(false);
  // What the server holds as far as this page knows. It defaults to false, so an untouched
  // box sends nothing.
  const marketingConsentSentRef = useRef(false);

  const [data, setData] = useState<OnboardingData>({
    displayName: '',
    profession: '',
    companySize: '',
    primaryGoal: '',
    toolsUsed: [],
    previousTool: '',
    referralSource: '',
    currentStep: 0,
  });

  const emailCodeFlowEnabled = !IS_CE;
  const roles = IS_CE ? CE_ROLES : ROLES;
  const goalOptions = IS_CE ? CE_USE_CASES : PRIMARY_GOALS;
  const companySizes = IS_CE ? CE_TEAM_SIZES : COMPANY_SIZES;
  const roleValues = IS_CE ? CE_ROLE_VALUES : ROLE_VALUES;
  const companySizeValues = IS_CE ? CE_TEAM_SIZE_VALUES : COMPANY_SIZE_VALUES;
  const goalValues = IS_CE ? CE_GOAL_VALUES : GOAL_VALUES;

  // Per-step completion rules (skip stays possible at any step)
  // A name the check could not answer for is usable: an outage must not disable
  // both Skip and Next. Uniqueness is re-checked server-side on save and skip.
  const displayNameUsable = displayNameAvailable || displayNameCheckFailed;
  const step1Valid = Boolean(data.displayName.trim()) && displayNameUsable;
  const step2Valid = Boolean(data.primaryGoal);
  const step3Valid = Boolean(data.previousTool) && Boolean(data.referralSource);

  // Determine the first step and total steps based on email verification
  const firstStep = emailCodeFlowEnabled && emailVerified === false ? 0 : 1;
  const totalSteps = emailCodeFlowEnabled && emailVerified === false ? ONBOARDING_STEPS + 1 : ONBOARDING_STEPS;
  const progressSteps = Array.from({ length: totalSteps }, (_, i) => i + firstStep);

  // Through a module, not an inline window.location assignment: jsdom no-ops that, so a stray
  // call is invisible to every test. One shipped, and the panel it navigated away from was the
  // whole point of the change that introduced it.
  const navigateToChat = useCallback(() => leaveForChat(locale), [locale]);

  // Resend cooldown timer
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const timer = setInterval(() => {
      setResendCooldown(prev => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [resendCooldown]);

  // Cloud only: a self-hosted install asked to be linked (?ce_link=1&...). Captured on arrival,
  // before anything else can navigate, into sessionStorage, which is what carries it across the
  // sign-in redirect, the email-verification step and reloads (the sign-in returnTo stays the
  // bare onboarding path on purpose: its own OIDC callback brings a `state` of its own).
  const [ceLinkPending, setCeLinkPending] = useState(false);
  const ceLinkContinuationStartedRef = useRef(false);
  useEffect(() => {
    if (IS_CE) return;
    captureCeLinkFromSearch(window.location.search);
    setCeLinkPending(hasPendingCeLink());
  }, []);

  // Initialize - use cached data from FirstLoginGuard if available
  useEffect(() => {
    if (initRef.current) return;
    if (authLoading) return;

    if (!isAuthenticated) {
      setPageState('needs_auth');
      return;
    }

    initRef.current = true;

    const init = async () => {
      try {
        // Check email verification status. CE local accounts are verified at
        // registration and must never enter the cloud email-code onboarding step.
        let isVerified = true;
        if (emailCodeFlowEnabled) {
          try {
            const emailStatus = await apiClient.get<{ verified: boolean }>('/auth/email/status');
            isVerified = emailStatus.verified;
          } catch {
            // If the endpoint fails, assume verified to not block onboarding
            isVerified = true;
          }
        }
        setEmailVerified(isVerified);

        // Try to get cached status from FirstLoginGuard first
        const cacheKey = ['user', 'onboarding-status', user?.sub];
        let response = queryClient.getQueryData<OnboardingResponse>(cacheKey);

        // Only fetch if not in cache
        if (!response) {
          response = await apiClient.get<OnboardingResponse>('/auth-service/api/onboarding/status');
          // Cache for future use
          if (response) {
            queryClient.setQueryData(cacheKey, response);
          }
        }

        if (!response) {
          setPageState('ready');
          setCurrentStep(emailCodeFlowEnabled && !isVerified ? 0 : 1);
          if (user?.name) {
            setData(prev => ({ ...prev, displayName: sanitizeDisplayName(user.name || '') }));
          }
          return;
        }

        if (!response.needsOnboarding) {
          // Already onboarded: straight to the chat. Setting 'completed' is enough: its effect is
          // the one navigation (calling navigateToChat here as well left the page twice).
          setPageState('completed');
          return;
        }

        if (response.displayName) {
          const profession = response!.profession || '';
          const professionIsOption = roleValues.has(profession);
          const professionIsCustom = Boolean(profession) && !ALL_ROLE_VALUES.has(profession);

          setData(prev => ({
            ...prev,
            displayName: sanitizeDisplayName(response!.displayName || ''),
            profession: professionIsOption ? profession : professionIsCustom ? 'other' : '',
            companySize: normalizeSelection(response!.companySize, companySizeValues),
            // A goal saved under the other edition is dropped (its option is not shown here)
            primaryGoal: normalizeSelection(response!.primaryGoal, goalValues),
            toolsUsed: normalizeSelections(response!.toolsUsed, TOOL_VALUES),
            previousTool: normalizeSelection(response!.previousTool, PREVIOUS_TOOL_VALUES),
            referralSource: normalizeSelection(response!.referralSource, REFERRAL_SOURCE_VALUES),
          }));
          // Restore custom role if profession was custom
          if (professionIsCustom) {
            setCustomRole(profession);
          }
          const restoredStep = response.currentStep > 0 ? response.currentStep : 1;
          setCurrentStep(emailCodeFlowEnabled && !isVerified ? 0 : restoredStep);
        } else {
          if (user?.name) {
            setData(prev => ({ ...prev, displayName: sanitizeDisplayName(user.name || '') }));
          }
          setCurrentStep(emailCodeFlowEnabled && !isVerified ? 0 : 1);
        }

        setPageState('ready');
      } catch (err) {
        console.error('Failed to check onboarding status:', err);
        setPageState('error');
        setError(t('errorDescription'));
      }
    };

    init();
  }, [
    authLoading,
    isAuthenticated,
    user?.sub,
    user?.name,
    t,
    queryClient,
    navigateToChat,
    emailCodeFlowEnabled,
    roles,
    roleValues,
    companySizeValues,
    goalValues,
  ]);

  // Auto-send code on mount when step 0 is shown (respects cooldown across page refreshes)
  useEffect(() => {
    if (currentStep !== 0 || codeSentOnMountRef.current || pageState !== 'ready') return;
    codeSentOnMountRef.current = true;

    // Check sessionStorage for recent code send to avoid duplicate emails on refresh.
    // Guarded: a blocked store throws here, inside the effect that draws the step.
    let lastSentStr: string | null = null;
    try {
      lastSentStr = sessionStorage.getItem(EMAIL_CODE_LAST_SENT_KEY);
    } catch { /* storage unavailable: treat as never sent */ }
    if (lastSentStr) {
      const elapsed = Math.floor((Date.now() - parseInt(lastSentStr, 10)) / 1000);
      if (elapsed < RESEND_COOLDOWN_SECONDS) {
        // Still within cooldown - show remaining time, don't re-send
        setResendCooldown(RESEND_COOLDOWN_SECONDS - elapsed);
        setCodeSent(true);
        return;
      }
    }

    handleSendCode();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStep, pageState]);

  // Redirect on completion. A pending CE link (cloud only) takes precedence over the chat:
  // eligible -> Keycloak, which returns to the self-hosted install; no paid plan (or the check
  // failed) -> the pricing page, whose banner explains why and can re-check.
  useEffect(() => {
    if (pageState !== 'completed') return;
    if (IS_CE || !hasPendingCeLink()) {
      navigateToChat();
      return;
    }
    // Once per page: the eligibility answer decides a full-page navigation either way.
    if (ceLinkContinuationStartedRef.current) return;
    ceLinkContinuationStartedRef.current = true;
    continuePendingCeLink().then((outcome) => {
      if (outcome === 'plan_required' || outcome === 'error') {
        assignLocation(ceLinkPricingPath(locale));
      } else if (outcome === 'none') {
        navigateToChat();
      }
    });
  }, [pageState, navigateToChat, locale]);

  // Check display name
  const checkDisplayName = useCallback(async (name: string) => {
    const ticket = ++checkTicketRef.current;
    const isCurrent = () => ticket === checkTicketRef.current;

    if (!name || name.trim().length < 3) {
      setDisplayNameError(null);
      setDisplayNameAvailable(false);
      setDisplayNameCheckFailed(false);
      return;
    }

    setCheckingDisplayName(true);
    try {
      const response = await apiClient.get<{ available: boolean; message: string }>(
        `/auth-service/api/onboarding/check-display-name?displayName=${encodeURIComponent(name)}`
      );
      if (!isCurrent()) return;
      setDisplayNameCheckFailed(false);

      if (!response.available) {
        setDisplayNameError(response.message || t('displayNameTaken'));
        setDisplayNameAvailable(false);
      } else {
        setDisplayNameError(null);
        setDisplayNameAvailable(true);
      }
    } catch {
      if (!isCurrent()) return;
      setDisplayNameError(t('displayNameCheckError'));
      setDisplayNameAvailable(false);
      setDisplayNameCheckFailed(true);
    } finally {
      if (isCurrent()) setCheckingDisplayName(false);
    }
  }, [t]);

  // Debounced check
  useEffect(() => {
    if (pageState !== 'ready') return;

    const timer = setTimeout(() => {
      if (data.displayName.trim()) {
        checkDisplayName(data.displayName);
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [data.displayName, checkDisplayName, pageState]);

  /**
   * Sign out from onboarding. Offered where nothing else lets the person leave:
   * the email step (the code goes to the address they want to leave, and there
   * is no change-email endpoint), the error card, and a save or skip that keeps
   * failing. This page has no sidebar or user menu, and FirstLoginGuard sends
   * app routes back here while the email is unverified.
   */
  const handleSignOut = (from: 'email_verification' | 'form_error' | 'error') => {
    if (signingOutRef.current) return;
    signingOutRef.current = true;
    setSigningOut(true);
    setSignOutError(null);
    // Before logout(), which resets the analytics identity.
    if (!departureCountedRef.current) {
      departureCountedRef.current = true;
      track('onboarding_signed_out', { signed_out_from: from });
    }
    // The cooldown belongs to the address being left: kept, the next account's
    // step 0 would show "Code sent!" without sending anything. Restored if the
    // sign-out fails, since the same account stays on the page.
    let stamp: string | null = null;
    try {
      stamp = sessionStorage.getItem(EMAIL_CODE_LAST_SENT_KEY);
      sessionStorage.removeItem(EMAIL_CODE_LAST_SENT_KEY);
    } catch { /* storage unavailable */ }
    logout().catch(() => {
      if (stamp) {
        try {
          sessionStorage.setItem(EMAIL_CODE_LAST_SENT_KEY, stamp);
        } catch { /* storage unavailable */ }
      }
      signingOutRef.current = false;
      setSigningOut(false);
      setSignOutError(t('signOutFailed'));
    });
  };

  // Email verification handlers
  const handleSendCode = async () => {
    setError(null);
    try {
      await apiClient.post('/auth/email/send-code', {});
      setCodeSent(true);
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setOtpDigits(['', '', '', '', '', '']);
      otpInputRefs.current[0]?.focus();
      // Persist send timestamp so page refresh respects the cooldown. Guarded on
      // its own so a storage failure is not reported as a failed send.
      try {
        sessionStorage.setItem(EMAIL_CODE_LAST_SENT_KEY, Date.now().toString());
      } catch { /* storage unavailable */ }
    } catch (err: any) {
      if (err?.status === 429) {
        setError(t('emailVerification.rateLimited'));
      } else {
        setError(err?.message || t('emailVerification.rateLimited'));
      }
    }
  };

  const handleOtpChange = (index: number, value: string) => {
    // Only accept digits
    const digit = value.replace(/\D/g, '').slice(-1);
    const newDigits = [...otpDigits];
    newDigits[index] = digit;
    setOtpDigits(newDigits);
    setError(null);

    if (digit && index < 5) {
      otpInputRefs.current[index + 1]?.focus();
    }

    // Auto-submit when all 6 digits are entered
    if (digit && index === 5) {
      const fullCode = newDigits.join('');
      if (fullCode.length === 6) {
        handleVerifyCode(fullCode);
      }
    }
  };

  const handleOtpKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !otpDigits[index] && index > 0) {
      otpInputRefs.current[index - 1]?.focus();
    }
  };

  const handleOtpPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (pasted.length === 0) return;

    const newDigits = [...otpDigits];
    for (let i = 0; i < 6; i++) {
      newDigits[i] = pasted[i] || '';
    }
    setOtpDigits(newDigits);

    // Focus the next empty input or the last one
    const nextEmpty = newDigits.findIndex(d => !d);
    const focusIndex = nextEmpty === -1 ? 5 : nextEmpty;
    otpInputRefs.current[focusIndex]?.focus();

    // Auto-submit if all 6 digits pasted
    if (pasted.length === 6) {
      handleVerifyCode(pasted);
    }
  };

  const handleVerifyCode = async (code: string) => {
    setVerifying(true);
    setError(null);
    try {
      await apiClient.post('/auth/email/verify-code', { code });
      setEmailVerificationSuccess(true);
      setEmailVerified(true);
      // Clean up cooldown storage after successful verification. Guarded: a throw
      // here would skip the timer below and strand a correct code on this screen.
      try {
        sessionStorage.removeItem(EMAIL_CODE_LAST_SENT_KEY);
      } catch { /* storage unavailable */ }
      // Brief delay to show success animation, then advance
      setTimeout(() => {
        setCurrentStep(1);
        setEmailVerificationSuccess(false);
      }, 1500);
    } catch (err: any) {
      const errorType = err?.error || err?.data?.error;
      if (errorType === 'expired') {
        setError(t('emailVerification.expiredCode'));
      } else if (errorType === 'too_many_attempts') {
        setError(t('emailVerification.tooManyAttempts'));
      } else if (errorType === 'invalid_code') {
        setError(t('emailVerification.invalidCode'));
      } else {
        setError(err?.message || t('emailVerification.invalidCode'));
      }
      // Clear OTP inputs on error
      setOtpDigits(['', '', '', '', '', '']);
      otpInputRefs.current[0]?.focus();
    } finally {
      setVerifying(false);
    }
  };

  // Save progress
  /**
   * Persist what has been answered so far.
   *
   * @return true when the server took it. The caller must not advance on false: a step that
   *   moves on from a failed save walks the person forward on answers nobody stored, and the
   *   last one is the step that leaves the page for an OAuth screen, so they come back to a
   *   session the server has never heard of.
   */
  const saveProgress = async (complete = false): Promise<boolean> => {
    setSaving(true);
    setError(null);

    try {
      const endpoint = complete
        ? '/auth-service/api/onboarding/complete'
        : '/auth-service/api/onboarding/save';

      // Use custom role text if "other" is selected
      const professionToSave = data.profession === 'other' && customRole.trim()
        ? customRole.trim()
        : data.profession;

      // Persona answers are bounded option values only (no free text besides the role).
      await apiClient.post(endpoint, {
        displayName: data.displayName,
        profession: professionToSave,
        companySize: data.companySize,
        primaryGoal: data.primaryGoal || null,
        toolsUsed: data.toolsUsed,
        previousTool: data.previousTool || null,
        referralSource: data.referralSource || null,
        currentStep,
      });

      if (complete) {
        // Invalidate cache so FirstLoginGuard knows onboarding is complete
        queryClient.setQueryData(['user', 'onboarding-status', user?.sub], {
          needsOnboarding: false,
          completed: true,
          skipped: false,
          emailVerified: true,
        });
        // Optimistically set displayName in profile cache so sidebar shows it immediately
        const profileKey = ['user', 'profile', user?.sub || 'anonymous'];
        queryClient.setQueryData(profileKey, (old: any) => ({
          ...(old || {}),
          displayName: data.displayName.trim(),
          username: data.displayName.trim(),
        }));
        // Also invalidate to trigger a background refetch with complete server data
        queryClient.invalidateQueries({ queryKey: ['user', 'profile'] });
        // 'completed' is what navigates to the chat (the effect watching it).
        setPageState('completed');
        track('onboarding_completed', {
          // Bounded option values only ('other' when the role is custom) - never
          // the free-text role the user typed.
          profession: data.profession || null,
          primary_goal: data.primaryGoal || null,
          tools_count: data.toolsUsed.length,
          previous_tool: data.previousTool || null,
          referral_source: data.referralSource || null,
        });
        // What a new account sees first: the monthly credits it already has
        // (workflows, plus chat and agent turns on the models marked Free),
        // then the applications it can start from. The
        // order matters and is enforced by the hand-off, not by luck - see
        // welcomeGiftHandoff.
        armWelcomeGift();
        armAppSuggestions();
        // Not navigating here: the effect watching pageState === 'completed' is the one
        // navigation, so the welcome hand-offs above are armed before the page is left.
      }
      setSubmitFailed(false);
      return true;
    } catch {
      // The step's translated message, never the raw one: these endpoints answer
      // an empty 400 for several reasons, and the client's own fallbacks
      // ("Request timeout", "Failed to fetch") are untranslated English.
      setError(t('saveError'));
      setSubmitFailed(true);
      return false;
    } finally {
      setSaving(false);
    }
  };

  /**
   * Persist the marketing consent when it differs from what was last sent. Best-effort and
   * never awaited by the step change: a lost write must not hold the person on this screen,
   * and Settings can change it later.
   */
  const persistMarketingConsent = () => {
    if (IS_CE || marketingConsent === marketingConsentSentRef.current) return;
    const value = marketingConsent;
    marketingConsentSentRef.current = value;
    unifiedApiService.setMarketingConsent(value).then(
      () => queryClient.invalidateQueries({ queryKey: MARKETING_CONSENT_QUERY_KEY }),
    ).catch(() => {
      // Allow a later step change to try again.
      marketingConsentSentRef.current = !value;
    });
  };

  // Skip
  const handleSkip = async () => {
    if (!data.displayName.trim() || !displayNameUsable) {
      setError(t('displayNameRequired'));
      return;
    }

    setSaving(true);
    try {
      await apiClient.post('/auth-service/api/onboarding/skip', {
        displayName: data.displayName.trim(),
      });
      // Invalidate cache so FirstLoginGuard knows onboarding is skipped
      queryClient.setQueryData(['user', 'onboarding-status', user?.sub], {
        needsOnboarding: false,
        completed: false,
        skipped: true,
        emailVerified: true,
      });
      // Optimistically set displayName in profile cache so sidebar shows it immediately
      const profileKey = ['user', 'profile', user?.sub || 'anonymous'];
      queryClient.setQueryData(profileKey, (old: any) => ({
        ...(old || {}),
        displayName: data.displayName.trim(),
        username: data.displayName.trim(),
      }));
      // Also invalidate to trigger a background refetch with complete server data
      queryClient.invalidateQueries({ queryKey: ['user', 'profile'] });
      setPageState('completed');
      persistMarketingConsent();
      track('onboarding_skipped', { skipped_at_step: currentStep });
      // Shown on the skip path too, and deliberately: what the plan grants does
      // not depend on how much the user chose to tell us, and someone who
      // skipped the questions is if anything likelier not to know it yet.
      armWelcomeGift();
      armAppSuggestions();
      // Not navigating here: the effect watching pageState === 'completed' is the one
      // navigation, as on the completion path. It is also what continues a pending CE
      // link instead of leaving for the chat; a direct call here skipped that and left twice.
    } catch {
      setError(t('skipError'));
      setSubmitFailed(true);
    } finally {
      setSaving(false);
    }
  };

  // Navigation
  const nextStep = async () => {
    // Step 0 is handled by OTP auto-submit, no manual next
    if (currentStep === 0) return;

    if (currentStep === 1 && !step1Valid) {
      setError(t('displayNameRequired'));
      return;
    }
    if (currentStep === 2 && !step2Valid) {
      setError(t('primaryGoalRequired'));
      return;
    }
    // Three steps, and step 3 is the last.
    if (currentStep === 3 && !step3Valid) {
      setError(t('step3Required'));
      return;
    }

    const lastStep = firstStep + totalSteps - 1;
    if (currentStep < lastStep) {
      // Stay on this step if the save was refused.
      if (!await saveProgress()) return;
      if (currentStep === 1) persistMarketingConsent();
      setCurrentStep(prev => prev + 1);
      // onboarding_step_completed is emitted server-side by the save endpoint
      // (single producer, so the funnel is not double-counted).
    } else {
      await saveProgress(true);
    }
  };

  const prevStep = () => {
    const minStep = emailCodeFlowEnabled && emailVerified === false ? 0 : 1;
    if (currentStep > minStep) setCurrentStep(prev => prev - 1);
  };

  // Toggle a multi-choice value
  const toggleTool = (value: string) => {
    setData(prev => {
      const updated = prev.toolsUsed.includes(value)
        ? prev.toolsUsed.filter(v => v !== value)
        : [...prev.toolsUsed, value];
      return { ...prev, toolsUsed: updated };
    });
  };

  const chipClass = (selected: boolean) =>
    `px-3 py-1.5 text-sm rounded-md border transition-colors ${
      selected
        ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
        : 'border-theme hover:bg-theme-secondary text-theme-primary'
    }`;

  // Login
  const handleLogin = () => {
    loginWithRedirect({ appState: { returnTo: `/${locale}/onboarding` } });
  };

  // Loading state
  if (pageState === 'loading' || authLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  // Auth required
  if (pageState === 'needs_auth') {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary p-4">
        <Card className="max-w-sm w-full border-theme">
          <CardHeader className="text-center space-y-4">
            <div className="mx-auto w-12 h-12 rounded-xl bg-theme-secondary flex items-center justify-center">
              <User className="h-6 w-6 text-theme-secondary" />
            </div>
            <div>
              <CardTitle className="text-xl">{t('loginRequired')}</CardTitle>
              <CardDescription className="mt-1">
                {ceLinkPending ? t('ceLinkLoginRequiredDescription') : t('loginRequiredDescription')}
              </CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex justify-center pb-6">
            <Button onClick={handleLogin}>{t('signIn')}</Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  // Error state
  if (pageState === 'error') {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary p-4">
        <Card className="max-w-sm w-full border-theme">
          <CardHeader className="text-center space-y-4">
            <div className="mx-auto w-12 h-12 rounded-xl bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
              <AlertCircle className="h-6 w-6 text-red-600 dark:text-red-400" />
            </div>
            <div>
              <CardTitle className="text-xl">{t('errorTitle')}</CardTitle>
              <CardDescription className="mt-1">{error || t('errorDescription')}</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex flex-col items-center gap-3 pb-6">
            <Button variant="outline" onClick={() => window.location.reload()}>
              {t('retry')}
            </Button>
            <button
              type="button"
              onClick={() => handleSignOut('error')}
              disabled={signingOut}
              aria-busy={signingOut}
              className="inline-flex items-center gap-1.5 text-sm text-theme-muted hover:text-theme-secondary disabled:opacity-50 transition-colors"
            >
              <LogOut className="h-3.5 w-3.5" />
              {t('signOut')}
            </button>
            {signOutError && (
              <p className="text-sm text-red-600 dark:text-red-400">{signOutError}</p>
            )}
          </CardContent>
        </Card>
      </div>
    );
  }

  // Completed (brief loading)
  if (pageState === 'completed') {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  const lastStep = firstStep + totalSteps - 1;

  // Main form
  return (
    <div className="min-h-screen bg-theme-primary py-8 px-4 flex flex-col items-center pt-[15vh]">
      <div className="max-w-lg w-full">
        {/* Progress */}
        <div className="mb-6">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              {progressSteps.map((step) => (
                <div
                  key={step}
                  className={`h-2 w-8 rounded-full transition-colors ${
                    step <= currentStep
                      ? 'bg-[var(--accent-primary)]'
                      : 'bg-theme-tertiary'
                  }`}
                />
              ))}
            </div>
            {currentStep > 0 && (
              <button
                onClick={handleSkip}
                disabled={saving || !data.displayName.trim() || !displayNameUsable}
                className="text-sm text-theme-muted hover:text-theme-secondary transition-colors disabled:opacity-50"
              >
                {t('skipForNow')}
              </button>
            )}
          </div>
        </div>

        {/* Step 0: Email Verification */}
        {currentStep === 0 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-theme-secondary flex items-center justify-center">
                  <Mail className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{t('emailVerification.title')}</CardTitle>
                  <CardDescription>
                    {t('emailVerification.description', { email: user?.email || '' })}
                  </CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-6">
              {emailVerificationSuccess ? (
                <div className="flex flex-col items-center py-6 animate-fade-in">
                  <div className="w-16 h-16 rounded-2xl bg-emerald-100 dark:bg-emerald-900/30 flex items-center justify-center mb-4">
                    <CheckCircle2 className="h-8 w-8 text-emerald-500" />
                  </div>
                  <p className="text-sm font-medium text-emerald-600 dark:text-emerald-400">
                    {t('emailVerification.verified')}
                  </p>
                </div>
              ) : (
                <>
                  {/* OTP Input */}
                  <div className="flex justify-center gap-2">
                    {otpDigits.map((digit, index) => (
                      <input
                        key={index}
                        ref={el => { otpInputRefs.current[index] = el; }}
                        type="text"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        maxLength={1}
                        value={digit}
                        onChange={(e) => handleOtpChange(index, e.target.value)}
                        onKeyDown={(e) => handleOtpKeyDown(index, e)}
                        onPaste={index === 0 ? handleOtpPaste : undefined}
                        disabled={verifying}
                        className="w-11 h-13 text-center text-lg font-mono rounded-lg border border-theme bg-theme-primary text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:border-transparent disabled:opacity-50 transition-colors"
                      />
                    ))}
                  </div>

                  {/* Verifying spinner */}
                  {verifying && (
                    <div className="flex items-center justify-center gap-2">
                      <LoadingSpinner size="xs" />
                      <span className="text-sm text-theme-muted">{t('emailVerification.verifying')}</span>
                    </div>
                  )}

                  {/* Code sent confirmation */}
                  {codeSent && !error && !verifying && (
                    <p className="text-sm text-emerald-600 dark:text-emerald-400 text-center">
                      {t('emailVerification.codeSent')}
                    </p>
                  )}

                  {/* Resend button */}
                  <div className="flex justify-center">
                    <button
                      onClick={handleSendCode}
                      disabled={resendCooldown > 0 || verifying}
                      className="text-sm text-[var(--accent-primary)] hover:underline disabled:opacity-50 disabled:no-underline transition-colors"
                    >
                      {resendCooldown > 0
                        ? t('emailVerification.resendIn', { seconds: resendCooldown })
                        : t('emailVerification.resendCode')
                      }
                    </button>
                  </div>

                  {/* Step 0 only. Disabled while a code is being checked, so a click
                      cannot drop a session that is about to become valid. */}
                  <div className="flex flex-col items-center gap-1 pt-1">
                    <button
                      type="button"
                      onClick={() => handleSignOut('email_verification')}
                      disabled={verifying || signingOut}
                      aria-busy={signingOut}
                      className="inline-flex items-center gap-1.5 text-sm text-theme-muted hover:text-theme-secondary disabled:opacity-50 transition-colors"
                    >
                      <LogOut className="h-3.5 w-3.5" />
                      {t('emailVerification.wrongEmail')}
                    </button>
                    {signOutError && (
                      <p className="text-sm text-red-600 dark:text-red-400">{signOutError}</p>
                    )}
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 1: Profile */}
        {currentStep === 1 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-theme-secondary flex items-center justify-center">
                  <User className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{IS_CE ? t('ce.step1.title') : t('step1.title')}</CardTitle>
                  <CardDescription>{IS_CE ? t('ce.step1.description') : t('step1.description')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              {/* Display Name */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary">
                  {t('displayName')} <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <Input
                    type="text"
                    value={data.displayName}
                    onChange={(e) => {
                      const sanitized = sanitizeDisplayName(e.target.value);
                      setData(prev => ({ ...prev, displayName: sanitized }));
                      // Until the debounced check runs, a failed check belongs to the previous name.
                      setDisplayNameCheckFailed(false);
                    }}
                    placeholder={t('displayNamePlaceholder')}
                    className={`pr-10 ${
                      displayNameError
                        ? 'border-red-500 focus-visible:ring-red-500'
                        : displayNameAvailable
                        ? 'border-emerald-500 focus-visible:ring-emerald-500'
                        : ''
                    }`}
                    maxLength={30}
                  />
                  <div className="absolute right-3 top-1/2 -translate-y-1/2">
                    {checkingDisplayName && (
                      <LoadingSpinner size="xs" />
                    )}
                    {displayNameAvailable && !checkingDisplayName && (
                      <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                    )}
                  </div>
                </div>
                {displayNameError && (
                  <p className="text-sm text-red-600 dark:text-red-400">{displayNameError}</p>
                )}
                <p className="text-xs text-theme-muted">{t('displayNameHint')}</p>
              </div>

              {/* Role/Profession */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary flex items-center gap-2">
                  <Briefcase className="h-3.5 w-3.5" />
                  {IS_CE ? t('ce.role') : t('role')}
                </label>
                <div className="flex flex-wrap gap-2">
                  {roles.map((role) => (
                    <button
                      key={role.value}
                      type="button"
                      onClick={() => {
                        setData(prev => ({ ...prev, profession: role.value }));
                        if (role.value !== 'other') {
                          setCustomRole('');
                        }
                      }}
                      className={`px-3 py-1.5 text-sm rounded-md border transition-colors ${
                        data.profession === role.value
                          ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
                          : 'border-theme hover:bg-theme-secondary text-theme-primary'
                      }`}
                    >
                      {t(role.labelKey)}
                    </button>
                  ))}
                </div>
                {/* Custom role input when "Other" is selected */}
                {data.profession === 'other' && (
                  <div className="mt-3">
                    <Input
                      type="text"
                      value={customRole}
                      onChange={(e) => setCustomRole(e.target.value)}
                      placeholder={t('customRolePlaceholder')}
                      className="max-w-xs"
                      maxLength={50}
                      autoFocus
                    />
                  </div>
                )}
              </div>

              {/* Company Size */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary flex items-center gap-2">
                  <Building2 className="h-3.5 w-3.5" />
                  {IS_CE ? t('ce.teamSize') : t('companySize')}
                </label>
                <div className="flex flex-wrap gap-2">
                  {companySizes.map((size) => (
                    <button
                      key={size.value}
                      type="button"
                      onClick={() => setData(prev => ({ ...prev, companySize: size.value }))}
                      className={`px-3 py-1.5 text-sm rounded-md border transition-colors ${
                        data.companySize === size.value
                          ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
                          : 'border-theme hover:bg-theme-secondary text-theme-primary'
                      }`}
                    >
                      {'labelKey' in size ? t(size.labelKey) : size.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Marketing e-mails: cloud only, unchecked by default (opt-in). */}
              {!IS_CE && (
                <div className="flex items-start gap-2 pt-1">
                  <Checkbox
                    id="onboarding-marketing-consent"
                    checked={marketingConsent}
                    onCheckedChange={(checked) => setMarketingConsent(checked === true)}
                    className="mt-0.5"
                  />
                  <label
                    htmlFor="onboarding-marketing-consent"
                    className="text-sm text-theme-secondary cursor-pointer"
                  >
                    {t('marketingConsent')}
                  </label>
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 2: First goal + tools already in use */}
        {currentStep === 2 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-theme-secondary flex items-center justify-center">
                  <Target className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{IS_CE ? t('ce.step2.title') : t('step2.title')}</CardTitle>
                  <CardDescription>{IS_CE ? t('ce.step2.description') : t('step2.description')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              {/* Primary goal (single choice) */}
              <div className="space-y-2" role="group" aria-labelledby="onboarding-primary-goal-label">
                <label id="onboarding-primary-goal-label" className="text-sm font-medium text-theme-primary">
                  {IS_CE ? t('ce.primaryGoalLabel') : t('primaryGoalLabel')} <span className="text-red-500">*</span>
                </label>
                <div className="flex flex-wrap gap-2">
                  {goalOptions.map((goal) => (
                    <button
                      key={goal.value}
                      type="button"
                      aria-pressed={data.primaryGoal === goal.value}
                      onClick={() => setData(prev => ({ ...prev, primaryGoal: goal.value }))}
                      className={chipClass(data.primaryGoal === goal.value)}
                    >
                      {t(goal.labelKey)}
                    </button>
                  ))}
                </div>
              </div>

              {/* Tools already in use (multi choice, optional) */}
              <div className="space-y-2" role="group" aria-labelledby="onboarding-tools-label">
                <label id="onboarding-tools-label" className="text-sm font-medium text-theme-primary">
                  {t('toolsUsedLabel')}
                </label>
                <div className="flex flex-wrap gap-2">
                  {TOOLS.map((tool) => (
                    <button
                      key={tool.value}
                      type="button"
                      aria-pressed={data.toolsUsed.includes(tool.value)}
                      onClick={() => toggleTool(tool.value)}
                      className={chipClass(data.toolsUsed.includes(tool.value))}
                    >
                      {t(tool.labelKey)}
                    </button>
                  ))}
                </div>
                <p className="text-xs text-theme-muted">{t('toolsUsedHint')}</p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Step 3: Previous tool + referral source */}
        {currentStep === 3 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-theme-secondary flex items-center justify-center">
                  <Sparkles className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{IS_CE ? t('ce.step3.title') : t('step3.title')}</CardTitle>
                  <CardDescription>{IS_CE ? t('ce.step3.description') : t('step3.description')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              {/* Previous automation tool (single choice) */}
              <div className="space-y-2" role="group" aria-labelledby="onboarding-previous-tool-label">
                <label id="onboarding-previous-tool-label" className="text-sm font-medium text-theme-primary">
                  {t('previousToolLabel')} <span className="text-red-500">*</span>
                </label>
                <div className="flex flex-wrap gap-2">
                  {PREVIOUS_TOOLS.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={data.previousTool === option.value}
                      onClick={() => setData(prev => ({ ...prev, previousTool: option.value }))}
                      className={chipClass(data.previousTool === option.value)}
                    >
                      {t(option.labelKey)}
                    </button>
                  ))}
                </div>
              </div>

              {/* Referral source (single choice) */}
              <div className="space-y-2" role="group" aria-labelledby="onboarding-referral-source-label">
                <label id="onboarding-referral-source-label" className="text-sm font-medium text-theme-primary">
                  {t('referralSourceLabel')} <span className="text-red-500">*</span>
                </label>
                <div className="flex flex-wrap gap-2">
                  {REFERRAL_SOURCES.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={data.referralSource === option.value}
                      onClick={() => setData(prev => ({ ...prev, referralSource: option.value }))}
                      className={chipClass(data.referralSource === option.value)}
                    >
                      {t(option.labelKey)}
                    </button>
                  ))}
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Error */}
        {error && (
          <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
            <p className="text-sm text-red-600 dark:text-red-400 flex items-center gap-2">
              <AlertCircle className="h-4 w-4" />
              {error}
            </p>
          </div>
        )}

        {/* Driven by submitFailed, not by error: a retry clears error and disables
            Skip and Next while it runs, and the way out must stay reachable then.
            submitFailed is only ever set by a save or skip, so never on step 0. */}
        {submitFailed && currentStep > 0 && (
          <div className="mt-3 flex flex-col items-start gap-1">
            <button
              type="button"
              onClick={() => handleSignOut('form_error')}
              disabled={signingOut}
              aria-busy={signingOut}
              className="inline-flex items-center gap-1.5 text-sm text-theme-muted hover:text-theme-secondary disabled:opacity-50 transition-colors"
            >
              <LogOut className="h-3.5 w-3.5" />
              {t('signOut')}
            </button>
            {signOutError && (
              <p className="text-sm text-red-600 dark:text-red-400">{signOutError}</p>
            )}
          </div>
        )}

        {/* Navigation - hidden during step 0 (OTP auto-submits) */}
        {currentStep > 0 && (
          <div className="mt-6 flex justify-between">
            <Button
              variant="ghost"
              onClick={prevStep}
              disabled={currentStep <= 1 || saving}
            >
              <ArrowLeft className="h-4 w-4 mr-2" />
              {t('back')}
            </Button>

            <Button
              onClick={nextStep}
              disabled={
                saving
                || (currentStep === 1 && !step1Valid)
                || (currentStep === 2 && !step2Valid)
                || (currentStep === 3 && !step3Valid)
              }
            >
              {saving ? (
                <>
                  <LoadingSpinner size="xs" className="mr-2" />
                  {t('saving')}
                </>
              ) : currentStep === lastStep ? (
                <>
                  <Rocket className="h-4 w-4 mr-2" />
                  {t('complete')}
                </>
              ) : (
                <>
                  {t('next')}
                  <ArrowRight className="h-4 w-4 ml-2" />
                </>
              )}
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
