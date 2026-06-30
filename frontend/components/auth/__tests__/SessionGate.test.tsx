// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { SessionGate } from '../SessionGate';
import { AUTH_GATE_STRINGS } from '@/lib/i18n/authGateMessages';

const en = AUTH_GATE_STRINGS.en;
const fr = AUTH_GATE_STRINGS.fr;
const zh = AUTH_GATE_STRINGS.zh;

describe('SessionGate - first-visit vs real expiry copy', () => {
  it('shows ONLY the neutral sign-in copy when the user was never signed in', () => {
    render(<SessionGate sessionExpired={false} onSignIn={() => {}} locale="en" />);

    // The bug being fixed: a cold first connect must NOT claim a session expired.
    expect(screen.queryByText(en.sessionExpired, { exact: false })).toBeNull();
    expect(screen.getByText(en.subtitle)).toBeTruthy();
    expect(screen.getByRole('heading', { name: en.title })).toBeTruthy();
  });

  it('prepends the "session has expired" sentence when a real session ended', () => {
    render(<SessionGate sessionExpired={true} onSignIn={() => {}} locale="en" />);

    expect(screen.getByText(`${en.sessionExpired} ${en.subtitle}`)).toBeTruthy();
    expect(screen.getByRole('heading', { name: en.title })).toBeTruthy();
  });

  it('honors the sessionExpired prop independently (guards against a hard-wired flag)', () => {
    const { rerender } = render(
      <SessionGate sessionExpired={false} onSignIn={() => {}} locale="en" />,
    );
    expect(screen.queryByText(en.sessionExpired, { exact: false })).toBeNull();

    rerender(<SessionGate sessionExpired={true} onSignIn={() => {}} locale="en" />);
    expect(screen.getByText(`${en.sessionExpired} ${en.subtitle}`)).toBeTruthy();
  });
});

describe('SessionGate - localization (renders above NextIntlClientProvider)', () => {
  it('renders French copy and button for the fr locale', () => {
    render(<SessionGate sessionExpired={true} onSignIn={() => {}} locale="fr" />);

    expect(screen.getByRole('heading', { name: fr.title })).toBeTruthy();
    expect(screen.getByText(`${fr.sessionExpired} ${fr.subtitle}`)).toBeTruthy();
    expect(screen.getByRole('button', { name: new RegExp(fr.submit) })).toBeTruthy();
  });

  it('joins the zh expiry body without an ASCII space after full-width punctuation', () => {
    render(<SessionGate sessionExpired={true} onSignIn={() => {}} locale="zh" />);

    // zh sentences end with "。" and must not be separated by a space.
    expect(screen.getByText(`${zh.sessionExpired}${zh.subtitle}`)).toBeTruthy();
    expect(screen.queryByText(`${zh.sessionExpired} ${zh.subtitle}`)).toBeNull();
  });

  it('falls back to English for an unknown locale', () => {
    render(<SessionGate sessionExpired={false} onSignIn={() => {}} locale="xx" />);
    expect(screen.getByText(en.subtitle)).toBeTruthy();
  });
});

describe('SessionGate - sign-in action', () => {
  it('invokes onSignIn when the button is clicked', () => {
    const onSignIn = vi.fn();
    render(<SessionGate sessionExpired={false} onSignIn={onSignIn} locale="en" />);

    fireEvent.click(screen.getByRole('button', { name: new RegExp(en.submit) }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });
});
