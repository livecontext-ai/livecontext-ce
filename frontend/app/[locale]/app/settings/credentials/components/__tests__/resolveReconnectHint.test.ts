import { describe, it, expect } from 'vitest';

import { resolveReconnectHint } from '../MyCredentialsList';
import type { Credential } from '@/lib/api/orchestrator/types';

/**
 * Each branch of resolveReconnectHint maps a diagnostic reason surfaced by
 * auth-service (via Credential.withoutSecrets allowlist) to the i18n key the
 * tooltip should render. The test passes a fake `t` that returns its input
 * key so we can assert the routing without binding to the actual translations.
 */
const t = (key: string) => key;

function build(credentialData: Record<string, unknown> | undefined): Credential {
  return {
    id: 1,
    tenant_id: 'tenant-1',
    name: 'Gmail',
    integration: 'gmail',
    type: 'OAuth2',
    environment: 'Production',
    status: 'needs_reauth',
    credential_data: credentialData ?? {},
    scopes: ['email', 'profile'],
    tags: [],
    is_default: false,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-05-06T00:00:00Z',
  };
}

describe('resolveReconnectHint', () => {
  it('routes BYOK platform_credential delete to the byok-deleted hint - the user just deleted their own custom OAuth app, the explanation must name that cause', () => {
    const cred = build({ byok_revoke_reason: 'platform_credential_deleted' });
    expect(resolveReconnectHint(cred, t)).toBe('reconnectHintByokDeleted');
  });

  it('routes refresh_error_reason=terminal_user to the token-revoked hint - provider rejected the refresh token (RFC 6749 invalid_grant)', () => {
    const cred = build({ refresh_error_reason: 'terminal_user' });
    expect(resolveReconnectHint(cred, t)).toBe('reconnectHintTokenRevoked');
  });

  it('routes refresh_error_reason=terminal_config to the admin-config hint - Reconnect alone will not fix it, the user must NOT keep retrying', () => {
    const cred = build({ refresh_error_reason: 'terminal_config' });
    expect(resolveReconnectHint(cred, t)).toBe('reconnectHintConfigError');
  });

  it('falls back to the generic hint when no diagnostic reason is present (older needs_reauth rows or unknown causes)', () => {
    expect(resolveReconnectHint(build({}), t)).toBe('reconnectRequiredHint');
    expect(resolveReconnectHint(build(undefined), t)).toBe('reconnectRequiredHint');
  });

  it('prefers byok_revoke_reason when both byok and refresh_error reasons coexist - BYOK delete is the more specific, more actionable cause', () => {
    const cred = build({
      byok_revoke_reason: 'platform_credential_deleted',
      refresh_error_reason: 'terminal_user',
    });
    expect(resolveReconnectHint(cred, t)).toBe('reconnectHintByokDeleted');
  });

  it('ignores non-string diagnostic values (defensive: malformed JSONB should not crash the tooltip)', () => {
    // Accidentally-typed payload - the auth-service writes strings, but the
    // Record<string,unknown> shape on the frontend means we must not assume.
    const cred = build({ byok_revoke_reason: 42, refresh_error_reason: { wrong: 'shape' } });
    expect(resolveReconnectHint(cred, t)).toBe('reconnectRequiredHint');
  });

  it('regression: a credential with N granted scopes still routes by reason - scopes do not influence the hint, only the diagnostic reason does', () => {
    // The user's confusion was "why does a 4-scope credential need reconnecting?".
    // The answer is the BYOK delete; the count of scopes is irrelevant to the hint.
    const cred = build({ byok_revoke_reason: 'platform_credential_deleted' });
    cred.scopes = ['gmail.send', 'gmail.read', 'profile', 'email']; // 4 scopes
    expect(resolveReconnectHint(cred, t)).toBe('reconnectHintByokDeleted');
  });
});
