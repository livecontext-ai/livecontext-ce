// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import { MissingScopesBanner } from '../MissingScopesBanner';

const messages = {
  workflow: {
    node: {
      missingScopes: {
        title: 'Additional permissions needed for {integration}',
        body: 'This action needs the following OAuth scopes that haven\'t been granted yet:',
        restrictedBody: 'These scopes are restricted by the provider. Connect your own OAuth app:',
        cta: 'Reconnect (Standard)',
        switchToAdvanced: 'Use a custom OAuth connection',
        advancedHint: 'If Reconnect doesn\'t grant the missing scope, this scope is restricted by Google.',
      },
    },
  },
};

function renderWithIntl(ui: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      {ui}
    </NextIntlClientProvider>,
  );
}

describe('MissingScopesBanner', () => {
  it('renders nothing when requiredScopes is empty', () => {
    const { container } = renderWithIntl(
      <MissingScopesBanner
        requiredScopes={[]}
        grantedScopes={[]}
        credentialType="OAuth2"
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when credential type is not OAuth2 (case-sensitive)', () => {
    const { container } = renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={null}
        credentialType="API Key"
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when lower-case "oauth2" is passed (matches actual enum casing only)', () => {
    const { container } = renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={[]}
        credentialType="oauth2"
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when granted covers all required', () => {
    const { container } = renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.labels']}
        grantedScopes={['gmail.labels', 'gmail.readonly']}
        credentialType="OAuth2"
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders the missing scopes when granted is missing one', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send', 'gmail.modify']}
        grantedScopes={['gmail.modify']}
        credentialType="OAuth2"
        integrationDisplayName="Gmail"
      />,
    );
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText(/gmail\.send/)).toBeTruthy();
    expect(screen.queryByText('gmail.modify')).toBeNull();
  });

  it('renders the reconnect CTA when onReconnect is provided', () => {
    const onReconnect = vi.fn();
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={[]}
        credentialType="OAuth2"
        onReconnect={onReconnect}
      />,
    );
    const cta = screen.getByRole('button', { name: /reconnect/i });
    cta.click();
    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it('treats null grantedScopes as empty (legacy credential)', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.labels']}
        grantedScopes={null}
        credentialType="OAuth2"
      />,
    );
    expect(screen.getByText('gmail.labels')).toBeTruthy();
  });

  it('renders the "Use a custom OAuth connection" CTA when onSwitchToAdvanced is provided', () => {
    const onSwitchToAdvanced = vi.fn();
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        onSwitchToAdvanced={onSwitchToAdvanced}
      />,
    );
    const cta = screen.getByRole('button', { name: /custom oauth/i });
    cta.click();
    expect(onSwitchToAdvanced).toHaveBeenCalledTimes(1);
  });

  it('does NOT render the custom OAuth CTA when onSwitchToAdvanced is omitted', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        onReconnect={() => {}}
      />,
    );
    expect(screen.queryByRole('button', { name: /custom oauth/i })).toBeNull();
  });

  it('hides Standard reconnect and offers BYOK directly when a missing scope is byok-only', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.readonly']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        byokOnlyScopes={['gmail.readonly', 'gmail.modify']}
        onReconnect={() => {}}
        onSwitchToAdvanced={() => {}}
      />,
    );
    // Standard reconnect can't grant a restricted scope → suppressed.
    expect(screen.queryByRole('button', { name: /reconnect/i })).toBeNull();
    // BYOK is the only offered path, with restricted-specific copy.
    expect(screen.getByRole('button', { name: /custom oauth/i })).toBeTruthy();
    expect(screen.getByText(/restricted by the provider/i)).toBeTruthy();
  });

  it('keeps Standard reconnect when the missing scope is NOT byok-only', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        byokOnlyScopes={['gmail.readonly']}
        onReconnect={() => {}}
        onSwitchToAdvanced={() => {}}
      />,
    );
    // gmail.send is platform-grantable → Standard reconnect stays.
    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy();
  });

  it('falls back to Standard reconnect when a byok-only scope is missing but no BYOK path is available', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.readonly']}
        grantedScopes={[]}
        credentialType="OAuth2"
        byokOnlyScopes={['gmail.readonly']}
        onReconnect={() => {}}
      />,
    );
    // No onSwitchToAdvanced → never render a dead end; keep reconnect.
    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy();
  });

  it('routes to BYOK for ANY scope the platform connect does not request - even one in neither list (platformScopes signal)', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.undeclared']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        platformScopes={['gmail.labels', 'gmail.send']}
        byokOnlyScopes={[]}
        onReconnect={() => {}}
        onSwitchToAdvanced={() => {}}
      />,
    );
    // gmail.undeclared ∉ platformScopes → Standard can't grant it → BYOK only,
    // despite not being declared in byokOnlyScopes.
    expect(screen.queryByRole('button', { name: /reconnect/i })).toBeNull();
    expect(screen.getByRole('button', { name: /custom oauth/i })).toBeTruthy();
  });

  it('keeps Standard when the missing scope IS in the platform connect set (reconnect can grant it)', () => {
    renderWithIntl(
      <MissingScopesBanner
        requiredScopes={['gmail.send']}
        grantedScopes={['gmail.labels']}
        credentialType="OAuth2"
        platformScopes={['gmail.labels', 'gmail.send']}
        byokOnlyScopes={['gmail.readonly']}
        onReconnect={() => {}}
        onSwitchToAdvanced={() => {}}
      />,
    );
    // gmail.send ∈ platformScopes → a Standard reconnect re-requests it → keep it.
    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy();
  });
});
