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
  Mail
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';

// Types
interface OnboardingData {
  displayName: string;
  profession: string;
  companySize: string;
  interests: string[];
  useCases: string[];
  experienceLevel: string;
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
  interests?: string[];
  useCases?: string[];
  experienceLevel?: string;
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

// Interests - broader categories
const INTERESTS = [
  { value: 'automation', labelKey: 'interests.automation' },
  { value: 'ai-ml', labelKey: 'interests.aiMl' },
  { value: 'data-analytics', labelKey: 'interests.dataAnalytics' },
  { value: 'integrations', labelKey: 'interests.integrations' },
  { value: 'productivity', labelKey: 'interests.productivity' },
  { value: 'business-intelligence', labelKey: 'interests.businessIntelligence' },
  { value: 'customer-experience', labelKey: 'interests.customerExperience' },
  { value: 'sales-crm', labelKey: 'interests.salesCrm' },
  { value: 'marketing-automation', labelKey: 'interests.marketingAutomation' },
  { value: 'ecommerce-tools', labelKey: 'interests.ecommerceTools' },
  { value: 'other', labelKey: 'interests.other' },
];

const CE_INTERESTS = [
  { value: 'self-hosted-agents', labelKey: 'ce.interests.selfHostedAgents' },
  { value: 'local-automation', labelKey: 'ce.interests.localAutomation' },
  { value: 'private-data', labelKey: 'ce.interests.privateData' },
  { value: 'integrations', labelKey: 'ce.interests.integrations' },
  { value: 'team-workflows', labelKey: 'ce.interests.teamWorkflows' },
  { value: 'local-marketplace', labelKey: 'ce.interests.localMarketplace' },
  { value: 'governance', labelKey: 'ce.interests.governance' },
  { value: 'other', labelKey: 'ce.interests.other' },
];

// Use cases - broader applications
const USE_CASES = [
  { value: 'workflow-automation', labelKey: 'useCases.workflowAutomation' },
  { value: 'chatbots-assistants', labelKey: 'useCases.chatbotsAssistants' },
  { value: 'data-integration', labelKey: 'useCases.dataIntegration' },
  { value: 'content-generation', labelKey: 'useCases.contentGeneration' },
  { value: 'lead-generation', labelKey: 'useCases.leadGeneration' },
  { value: 'customer-support', labelKey: 'useCases.customerSupport' },
  { value: 'reporting-dashboards', labelKey: 'useCases.reportingDashboards' },
  { value: 'ecommerce-automation', labelKey: 'useCases.ecommerceAutomation' },
  { value: 'team-collaboration', labelKey: 'useCases.teamCollaboration' },
  { value: 'monitoring-alerts', labelKey: 'useCases.monitoringAlerts' },
  { value: 'other', labelKey: 'useCases.other' },
];

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

// Experience levels - more universal
const EXPERIENCE_LEVELS = [
  { value: 'beginner', labelKey: 'experience.beginner', descKey: 'experience.beginnerDesc' },
  { value: 'intermediate', labelKey: 'experience.intermediate', descKey: 'experience.intermediateDesc' },
  { value: 'advanced', labelKey: 'experience.advanced', descKey: 'experience.advancedDesc' },
];

const CE_EXPERIENCE_LEVELS = [
  { value: 'first-install', labelKey: 'ce.experience.firstInstall', descKey: 'ce.experience.firstInstallDesc' },
  { value: 'operator', labelKey: 'ce.experience.operator', descKey: 'ce.experience.operatorDesc' },
  { value: 'power-user', labelKey: 'ce.experience.powerUser', descKey: 'ce.experience.powerUserDesc' },
];

const valuesOf = (options: Array<{ value: string }>) => new Set(options.map(option => option.value));

const normalizeSelection = (value: string | undefined, allowedValues: Set<string>): string => {
  if (!value) return '';
  return allowedValues.has(value) ? value : '';
};

const normalizeSelectionsWithCustomValue = (
  values: string[] | undefined,
  allowedValues: Set<string>,
  knownValues: Set<string>,
): { values: string[]; customValue: string } => {
  if (!values) {
    return { values: [], customValue: '' };
  }

  const normalizedValues: string[] = [];
  let customValue = '';

  for (const value of values) {
    if (allowedValues.has(value)) {
      if (!normalizedValues.includes(value)) {
        normalizedValues.push(value);
      }
      continue;
    }

    if (knownValues.has(value)) {
      continue;
    }

    if (!customValue) {
      customValue = value;
    }
    if (!normalizedValues.includes('other')) {
      normalizedValues.push('other');
    }
  }

  return { values: normalizedValues, customValue };
};

const ROLE_VALUES = valuesOf(ROLES);
const CE_ROLE_VALUES = valuesOf(CE_ROLES);
const ALL_ROLE_VALUES = new Set([...ROLE_VALUES, ...CE_ROLE_VALUES]);
const COMPANY_SIZE_VALUES = valuesOf(COMPANY_SIZES);
const CE_TEAM_SIZE_VALUES = valuesOf(CE_TEAM_SIZES);
const INTEREST_VALUES = valuesOf(INTERESTS);
const CE_INTEREST_VALUES = valuesOf(CE_INTERESTS);
const ALL_INTEREST_VALUES = new Set([...INTEREST_VALUES, ...CE_INTEREST_VALUES]);
const GOAL_VALUES = valuesOf(USE_CASES);
const CE_GOAL_VALUES = valuesOf(CE_USE_CASES);
const ALL_GOAL_VALUES = new Set([...GOAL_VALUES, ...CE_GOAL_VALUES]);
const EXPERIENCE_VALUES = valuesOf(EXPERIENCE_LEVELS);
const CE_EXPERIENCE_VALUES = valuesOf(CE_EXPERIENCE_LEVELS);

const ONBOARDING_STEPS = 3; // Steps 1-3 (profile, interests, goals)
const RESEND_COOLDOWN_SECONDS = 60;

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
  const { user, isLoading: authLoading, isAuthenticated, loginWithRedirect } = useAuth();
  const t = useTranslations('onboarding');
  const queryClient = useQueryClient();

  const [pageState, setPageState] = useState<PageState>('loading');
  const [currentStep, setCurrentStep] = useState(1);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [displayNameError, setDisplayNameError] = useState<string | null>(null);
  const [checkingDisplayName, setCheckingDisplayName] = useState(false);
  const [displayNameAvailable, setDisplayNameAvailable] = useState(false);
  const [customRole, setCustomRole] = useState('');
  const [customInterest, setCustomInterest] = useState('');
  const [customUseCase, setCustomUseCase] = useState('');
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

  const [data, setData] = useState<OnboardingData>({
    displayName: '',
    profession: '',
    companySize: '',
    interests: [],
    useCases: [],
    experienceLevel: '',
    currentStep: 0,
  });

  const emailCodeFlowEnabled = !IS_CE;
  const roles = IS_CE ? CE_ROLES : ROLES;
  const interests = IS_CE ? CE_INTERESTS : INTERESTS;
  const goalOptions = IS_CE ? CE_USE_CASES : USE_CASES;
  const experienceLevels = IS_CE ? CE_EXPERIENCE_LEVELS : EXPERIENCE_LEVELS;
  const companySizes = IS_CE ? CE_TEAM_SIZES : COMPANY_SIZES;
  const roleValues = IS_CE ? CE_ROLE_VALUES : ROLE_VALUES;
  const companySizeValues = IS_CE ? CE_TEAM_SIZE_VALUES : COMPANY_SIZE_VALUES;
  const interestValues = IS_CE ? CE_INTEREST_VALUES : INTEREST_VALUES;
  const goalValues = IS_CE ? CE_GOAL_VALUES : GOAL_VALUES;
  const experienceValues = IS_CE ? CE_EXPERIENCE_VALUES : EXPERIENCE_VALUES;
  const selectedGoalValues = data.useCases;
  let shouldShowCustomGoal = false;
  for (const value of selectedGoalValues) {
    if (value === 'other') {
      shouldShowCustomGoal = true;
      break;
    }
  }

  // Determine the first step and total steps based on email verification
  const firstStep = emailCodeFlowEnabled && emailVerified === false ? 0 : 1;
  const totalSteps = emailCodeFlowEnabled && emailVerified === false ? ONBOARDING_STEPS + 1 : ONBOARDING_STEPS;
  const progressSteps = Array.from({ length: totalSteps }, (_, i) => i + firstStep);

  const navigateToChat = useCallback(() => {
    window.location.href = `/${locale}/app/chat`;
  }, [locale]);

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
          setPageState('completed');
          navigateToChat();
          return;
        }

        if (response.displayName) {
          const profession = response!.profession || '';
          const professionIsOption = roleValues.has(profession);
          const professionIsCustom = Boolean(profession) && !ALL_ROLE_VALUES.has(profession);
          const restoredInterests = normalizeSelectionsWithCustomValue(
            response!.interests,
            interestValues,
            ALL_INTEREST_VALUES,
          );
          const restoredUseCases = normalizeSelectionsWithCustomValue(
            response!.useCases,
            goalValues,
            ALL_GOAL_VALUES,
          );

          setData(prev => ({
            ...prev,
            displayName: sanitizeDisplayName(response!.displayName || ''),
            profession: professionIsOption ? profession : professionIsCustom ? 'other' : '',
            companySize: normalizeSelection(response!.companySize, companySizeValues),
            interests: restoredInterests.values,
            useCases: restoredUseCases.values,
            experienceLevel: normalizeSelection(response!.experienceLevel, experienceValues),
          }));
          // Restore custom role if profession was custom
          if (professionIsCustom) {
            setCustomRole(profession);
          }
          setCustomInterest(restoredInterests.customValue);
          setCustomUseCase(restoredUseCases.customValue);
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
    interestValues,
    goalValues,
    experienceValues,
  ]);

  // Auto-send code on mount when step 0 is shown (respects cooldown across page refreshes)
  useEffect(() => {
    if (currentStep !== 0 || codeSentOnMountRef.current || pageState !== 'ready') return;
    codeSentOnMountRef.current = true;

    // Check sessionStorage for recent code send to avoid duplicate emails on refresh
    const STORAGE_KEY = 'email_verification_last_sent';
    const lastSentStr = sessionStorage.getItem(STORAGE_KEY);
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

  // Redirect on completion
  useEffect(() => {
    if (pageState === 'completed') {
      navigateToChat();
    }
  }, [pageState, navigateToChat]);

  // Check display name
  const checkDisplayName = useCallback(async (name: string) => {
    if (!name || name.trim().length < 3) {
      setDisplayNameError(null);
      setDisplayNameAvailable(false);
      return;
    }

    setCheckingDisplayName(true);
    try {
      const response = await apiClient.get<{ available: boolean; message: string }>(
        `/auth-service/api/onboarding/check-display-name?displayName=${encodeURIComponent(name)}`
      );

      if (!response.available) {
        setDisplayNameError(response.message || t('displayNameTaken'));
        setDisplayNameAvailable(false);
      } else {
        setDisplayNameError(null);
        setDisplayNameAvailable(true);
      }
    } catch {
      setDisplayNameError(t('displayNameCheckError'));
      setDisplayNameAvailable(false);
    } finally {
      setCheckingDisplayName(false);
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

  // Email verification handlers
  const handleSendCode = async () => {
    setError(null);
    try {
      await apiClient.post('/auth/email/send-code', {});
      setCodeSent(true);
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setOtpDigits(['', '', '', '', '', '']);
      otpInputRefs.current[0]?.focus();
      // Persist send timestamp so page refresh respects the cooldown
      sessionStorage.setItem('email_verification_last_sent', Date.now().toString());
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
      // Clean up cooldown storage after successful verification
      sessionStorage.removeItem('email_verification_last_sent');
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
  const saveProgress = async (complete = false) => {
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

      // Replace "other" in interests with custom interest text
      const interestsToSave = data.interests.map(interest =>
        interest === 'other' && customInterest.trim() ? customInterest.trim() : interest
      );

      // Replace "other" in useCases with custom use case text
      const useCasesToSave = data.useCases.map(useCase =>
        useCase === 'other' && customUseCase.trim() ? customUseCase.trim() : useCase
      );

      await apiClient.post(endpoint, { ...data, profession: professionToSave, interests: interestsToSave, useCases: useCasesToSave, currentStep });

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
        setPageState('completed');
        track('onboarding_completed', {
          // Bounded category only ('other' when custom) - never the free-text
          // role the user typed.
          profession: data.profession || null,
          use_case_count: useCasesToSave.length,
          interest_count: interestsToSave.length,
        });
        sessionStorage.setItem('lc_show_welcome_gift', '1');
        sessionStorage.setItem('lc_show_app_suggestions', '1');
        navigateToChat();
      }
    } catch (err: any) {
      setError(err?.message || t('saveError'));
    } finally {
      setSaving(false);
    }
  };

  // Skip
  const handleSkip = async () => {
    if (!data.displayName.trim() || !displayNameAvailable) {
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
      track('onboarding_skipped', { skipped_at_step: currentStep });
      sessionStorage.setItem('lc_show_welcome_gift', '1');
      sessionStorage.setItem('lc_show_app_suggestions', '1');
      navigateToChat();
    } catch (err: any) {
      setError(err?.message || t('skipError'));
    } finally {
      setSaving(false);
    }
  };

  // Navigation
  const nextStep = async () => {
    // Step 0 is handled by OTP auto-submit, no manual next
    if (currentStep === 0) return;

    if (currentStep === 1 && (!data.displayName.trim() || !displayNameAvailable)) {
      setError(t('displayNameRequired'));
      return;
    }

    const lastStep = firstStep + totalSteps - 1;
    if (currentStep < lastStep) {
      await saveProgress();
      setCurrentStep(prev => prev + 1);
    } else {
      await saveProgress(true);
    }
  };

  const prevStep = () => {
    const minStep = emailCodeFlowEnabled && emailVerified === false ? 0 : 1;
    if (currentStep > minStep) setCurrentStep(prev => prev - 1);
  };

  // Toggle selection
  const toggleSelection = (field: 'interests' | 'useCases', value: string) => {
    setData(prev => {
      const current = prev[field];
      const updated = current.includes(value)
        ? current.filter(v => v !== value)
        : [...current, value];
      return { ...prev, [field]: updated };
    });
  };

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
            <div className="mx-auto w-12 h-12 rounded-full bg-theme-secondary flex items-center justify-center">
              <User className="h-6 w-6 text-theme-secondary" />
            </div>
            <div>
              <CardTitle className="text-xl">{t('loginRequired')}</CardTitle>
              <CardDescription className="mt-1">{t('loginRequiredDescription')}</CardDescription>
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
            <div className="mx-auto w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
              <AlertCircle className="h-6 w-6 text-red-600 dark:text-red-400" />
            </div>
            <div>
              <CardTitle className="text-xl">{t('errorTitle')}</CardTitle>
              <CardDescription className="mt-1">{error || t('errorDescription')}</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex justify-center pb-6">
            <Button variant="outline" onClick={() => window.location.reload()}>
              {t('retry')}
            </Button>
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
                disabled={saving || !data.displayName.trim() || !displayNameAvailable}
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
                <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center">
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
                  <div className="w-16 h-16 rounded-full bg-emerald-100 dark:bg-emerald-900/30 flex items-center justify-center mb-4">
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
                <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center">
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
                      className={`px-3 py-1.5 text-sm rounded-full border transition-colors ${
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
                      className={`px-3 py-1.5 text-sm rounded-full border transition-colors ${
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
            </CardContent>
          </Card>
        )}

        {/* Step 2: Interests */}
        {currentStep === 2 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center">
                  <Sparkles className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{IS_CE ? t('ce.step2.title') : t('step2.title')}</CardTitle>
                  <CardDescription>{IS_CE ? t('ce.step2.description') : t('step2.description')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-2">
                {interests.map((interest) => (
                  <button
                    key={interest.value}
                    type="button"
                    onClick={() => {
                      toggleSelection('interests', interest.value);
                      // Clear custom interest if deselecting "other"
                      if (interest.value === 'other' && data.interests.includes('other')) {
                        setCustomInterest('');
                      }
                    }}
                    className={`px-4 py-2 text-sm rounded-full border transition-colors ${
                      data.interests.includes(interest.value)
                        ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
                        : 'border-theme hover:bg-theme-secondary text-theme-primary'
                    }`}
                  >
                    {t(interest.labelKey)}
                  </button>
                ))}
              </div>
              {/* Custom interest input when "Other" is selected */}
              {data.interests.includes('other') && (
                <div className="mt-3">
                  <Input
                    type="text"
                    value={customInterest}
                    onChange={(e) => setCustomInterest(e.target.value)}
                    placeholder={t('customInterestPlaceholder')}
                    className="max-w-xs"
                    maxLength={50}
                    autoFocus
                  />
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 3: Goals */}
        {currentStep === 3 && (
          <Card className="border-theme animate-fade-in">
            <CardHeader className="pb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center">
                  <Target className="h-5 w-5 text-theme-secondary" />
                </div>
                <div>
                  <CardTitle className="text-lg">{IS_CE ? t('ce.step3.title') : t('step3.title')}</CardTitle>
                  <CardDescription>{IS_CE ? t('ce.step3.description') : t('step3.description')}</CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              {/* Use Cases */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary">
                  {IS_CE ? t('ce.useCasesLabel') : t('useCasesLabel')}
                </label>
                <div className="flex flex-wrap gap-2">
                  {goalOptions.map((useCase) => (
                    <button
                      key={useCase.value}
                      type="button"
                      onClick={() => {
                        toggleSelection('useCases', useCase.value);
                        // Clear custom use case if deselecting "other"
                        if (useCase.value === 'other' && selectedGoalValues.includes('other')) {
                          setCustomUseCase('');
                        }
                      }}
                      className={`px-4 py-2 text-sm rounded-full border transition-colors ${
                        selectedGoalValues.includes(useCase.value)
                          ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
                          : 'border-theme hover:bg-theme-secondary text-theme-primary'
                      }`}
                    >
                      {t(useCase.labelKey)}
                    </button>
                  ))}
                </div>
                {/* Custom use case input when "Other" is selected */}
                {shouldShowCustomGoal && (
                  <div className="mt-3">
                    <Input
                      type="text"
                      value={customUseCase}
                      onChange={(e) => setCustomUseCase(e.target.value)}
                      placeholder={t('customUseCasePlaceholder')}
                      className="max-w-xs"
                      maxLength={50}
                      autoFocus
                    />
                  </div>
                )}
              </div>

              {/* Experience Level */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-theme-primary">
                  {IS_CE ? t('ce.experienceLevel') : t('experienceLevel')}
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {experienceLevels.map((level) => (
                    <button
                      key={level.value}
                      type="button"
                      onClick={() => setData(prev => ({ ...prev, experienceLevel: level.value }))}
                      className={`p-3 text-center rounded-xl border transition-colors ${
                        data.experienceLevel === level.value
                          ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] border-transparent'
                          : 'border-theme hover:bg-theme-secondary'
                      }`}
                    >
                      <div className="text-sm font-medium">{t(level.labelKey)}</div>
                      <div className={`text-xs mt-0.5 ${
                        data.experienceLevel === level.value
                          ? 'opacity-80'
                          : 'text-theme-muted'
                      }`}>
                        {t(level.descKey)}
                      </div>
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
              disabled={saving || (currentStep === 1 && (!data.displayName.trim() || !displayNameAvailable))}
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
