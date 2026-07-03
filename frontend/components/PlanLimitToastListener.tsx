'use client';

/**
 * Global listener for plan-limit-exceeded events emitted by apiClient when
 * the backend returns HTTP 409 with error="PLAN_RESOURCE_LIMIT_EXCEEDED".
 *
 * Mounted once at the smart-providers root, which is OUTSIDE NextIntlClientProvider -
 * so this component must NOT use useTranslations. It picks the message language
 * from document.documentElement.lang and uses inline string tables.
 */

import React, { useEffect } from 'react';
import ToastContainer from './ToastContainer';
import { useToast } from './Toast';

interface PlanLimitDetail {
  error: string;
  resourceType: 'WORKFLOW' | 'AGENT' | 'DATASOURCE' | 'INTERFACE' | 'APPLICATION' | 'PUBLICATION' | 'WORKFLOW_VARIABLE';
  planCode: string;
  currentCount: number;
  limit: number;
  upgradeHint?: string;
}

const STRINGS = {
  en: {
    title: (r: string) => `${r} limit reached`,
    message: (r: string, limit: number, plan: string, hint: string) =>
      `Your ${plan} plan allows ${limit} ${r}(s). ${hint}`,
    defaultHint: 'Upgrade your plan to create more.',
    resources: { workflow: 'workflow', agent: 'agent', datasource: 'table', interface: 'interface', application: 'application', publication: 'publication', workflow_variable: 'workflow variable' },
  },
  fr: {
    title: (r: string) => `Limite de ${r} atteinte`,
    message: (r: string, limit: number, plan: string, hint: string) =>
      `Votre plan ${plan} autorise ${limit} ${r}(s). ${hint}`,
    defaultHint: 'Passez à un plan supérieur pour en créer davantage.',
    resources: { workflow: 'workflow', agent: 'agent', datasource: 'table', interface: 'interface', application: 'application', publication: 'publication', workflow_variable: 'variable de workflow' },
  },
} as const;

function pickLang(): 'en' | 'fr' {
  if (typeof document === 'undefined') return 'en';
  const lang = (document.documentElement.lang || '').toLowerCase();
  return lang.startsWith('fr') ? 'fr' : 'en';
}

export default function PlanLimitToastListener() {
  const { toasts, addToast, removeToast } = useToast();

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<PlanLimitDetail>).detail;
      if (!detail || detail.error !== 'PLAN_RESOURCE_LIMIT_EXCEEDED') return;

      const lang = pickLang();
      const s = STRINGS[lang];
      const resourceKey = detail.resourceType.toLowerCase() as keyof typeof s.resources;
      const resourceLabel = s.resources[resourceKey] || detail.resourceType;
      const hint = detail.upgradeHint || s.defaultHint;

      addToast({
        type: 'warning',
        title: s.title(resourceLabel),
        message: s.message(resourceLabel, detail.limit, detail.planCode, hint),
        duration: 8000,
      });
    };

    window.addEventListener('plan-limit-exceeded', handler);
    return () => window.removeEventListener('plan-limit-exceeded', handler);
  }, [addToast]);

  return <ToastContainer toasts={toasts} onRemoveToast={removeToast} />;
}
