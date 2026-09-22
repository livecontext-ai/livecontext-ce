'use client';

import { useQuery } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import type { CredentialTemplate } from '@/lib/api/orchestrator/types';
import {
  resolveByokConfig,
  resolveByokOnlyScopeList,
  resolvePlatformScopeList,
} from '@/components/credentials/CredentialWizard';

export interface ByokCapability {
  /** Scopes the platform's shared OAuth client never asks for. */
  byokOnlyScopes: string[];
  /** Scopes the platform's shared OAuth client does ask for. */
  platformScopes: string[];
  /**
   * Whether bringing your own OAuth client is offered for this integration at all.
   *
   * <p>False for roughly every OAuth2 API in the catalog: the template's `byok.surface` is
   * `hidden` by default, and offering the form anyway sends a user to register an
   * application the product never intended them to register.
   */
  byokOffered: boolean;
}

const NOTHING_KNOWN: ByokCapability = {
  byokOnlyScopes: [],
  platformScopes: [],
  byokOffered: false,
};

/**
 * What a given integration's OAuth client can and cannot be asked to grant.
 *
 * <p>The answer lives in the catalog credential template, and the three functions that read
 * it are the wizard's own, so the chat card and the workflow inspector decide "can a standard
 * reconnect ever grant this" from the same rule. Only the lookup differs, and legitimately:
 * the inspector already holds a bulk of templates fetched for the whole node, while a chat
 * card knows one integration name.
 *
 * <p>Returns nothing-known while loading and on failure, which makes the banner offer the
 * ordinary reconnect and no BYOK form. That is the safe direction: a reconnect that turns out
 * to be futile costs a click, whereas telling someone to go and register an OAuth application
 * they do not need costs an afternoon.
 */
export function useByokCapability(integrationName: string | undefined | null): ByokCapability {
  const term = (integrationName ?? '').trim().toLowerCase();

  const { data } = useQuery({
    queryKey: ['byok-capability', term],
    enabled: term.length > 0,
    staleTime: 5 * 60 * 1000,
    queryFn: async (): Promise<CredentialTemplate | null> => {
      const res = await orchestratorApi.getCredentialTemplates({
        search: term,
        pageSize: 10,
        includeInactive: true,
      });
      const templates = res.credentials || [];
      return (
        templates.find(t => (t.credential_name || '').toLowerCase() === term) ??
        templates.find(t => (t.display_name || '').toLowerCase() === term) ??
        null
      );
    },
  });

  if (!data) return NOTHING_KNOWN;

  const byokOnlyScopes = resolveByokOnlyScopeList(data);
  return {
    byokOnlyScopes,
    platformScopes: resolvePlatformScopeList(data),
    // A template declaring byokOnlyScopes means some scope is obtainable ONLY that way, so
    // the form has to be reachable whatever the surface flag says. Same rule the inspector
    // applies, and the reason Gmail read access is reachable at all.
    byokOffered: resolveByokConfig(data).surface !== 'hidden' || byokOnlyScopes.length > 0,
  };
}
