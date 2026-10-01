// @vitest-environment jsdom
/**
 * The public form page (/f/{token}, and /s/{token} share links) read select options as plain
 * strings and rendered `{opt}` as a React child. The builder writes options as
 * [{id, label, value}] objects, so every select built in the editor or by add_node crashed the
 * page ("Objects are not valid as a React child"). The page now reads the canonical shape, and
 * pre-fills defaults whatever spelling the stored copy carries (2026-09-29).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('@/components/sharing/PublicHeader', () => ({ default: () => null }));

import PublicForm from '../PublicForm';

function mockConfig(formConfig: unknown[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ name: 'Demande', isActive: true, formConfig }),
    })),
  );
}

describe('PublicForm', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('renders select options stored as [{id, label, value}] objects (was: page crash)', async () => {
    mockConfig([
      {
        name: 'tier',
        label: 'Tier',
        type: 'select',
        options: [
          { id: 'opt-0', label: 'Free', value: 'free' },
          { id: 'opt-1', label: 'Pro', value: 'pro' },
        ],
      },
    ]);

    render(<PublicForm token="tok" />);

    const pro = (await screen.findByRole('option', { name: 'Pro' })) as HTMLOptionElement;
    expect(pro.value).toBe('pro');
    expect(screen.getByRole('option', { name: 'Free' })).toBeTruthy();
  });

  it('still renders string-shorthand options', async () => {
    mockConfig([{ name: 'tier', label: 'Tier', type: 'select', options: ['free', 'pro'] }]);

    render(<PublicForm token="tok" />);

    const pro = (await screen.findByRole('option', { name: 'pro' })) as HTMLOptionElement;
    expect(pro.value).toBe('pro');
  });

  it('skips an entry that is not a named field instead of drawing an unnamed input', async () => {
    mockConfig(['email', { label: 'No name', type: 'text' }, { name: 'theme', label: 'Theme', type: 'text' }]);

    render(<PublicForm token="tok" />);

    expect(await screen.findByText('Theme')).toBeTruthy();
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
  });

  it('pre-fills a checkbox from a boolean default and a textarea from a text default', async () => {
    mockConfig([
      { name: 'agree', label: 'Agree', type: 'checkbox', defaultValue: true },
      { name: 'optin', label: 'Opt in', type: 'checkbox', defaultValue: false },
      { name: 'bio', label: 'Bio', type: 'textarea', defaultValue: 'Hello' },
    ]);

    render(<PublicForm token="tok" />);

    expect(await screen.findByDisplayValue('Hello')).toBeTruthy();
    const [agree, optin] = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(agree.checked).toBe(true);
    expect(optin.checked).toBe(false);
  });

  it('pre-fills defaults: defaultValue, a legacy `default`, and a numeric default', async () => {
    mockConfig([
      { name: 'theme', label: 'Theme', type: 'text', defaultValue: 'Innovation' },
      { name: 'author', label: 'Author', type: 'text', default: 'Ada' },
      { name: 'n', label: 'Count', type: 'number', defaultValue: 2000 },
      {
        name: 'tier',
        label: 'Tier',
        type: 'select',
        defaultValue: 'pro',
        options: [
          { id: 'opt-0', label: 'Free', value: 'free' },
          { id: 'opt-1', label: 'Pro', value: 'pro' },
        ],
      },
    ]);

    render(<PublicForm token="tok" />);

    expect(await screen.findByDisplayValue('Innovation')).toBeTruthy();
    expect(screen.getByDisplayValue('Ada')).toBeTruthy();
    expect(screen.getByDisplayValue('2000')).toBeTruthy();
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('pro');
  });
});
