// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));

import { AvatarPicker, getAvatarPreset, getPresetDefaultName, AVATAR_PRESETS } from '../AvatarPicker';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// The avatar studio = presets grid + per-preset color customization + upload + AI
// generation. These pin the picker's new contract: customized values keep the
// 'preset:' prefix (so backend snapshot filters keep them), AI SVGs persist through
// the SAME onUpload path as manual uploads (single storage/URL story).
describe('AvatarPicker - color customization', () => {
  it('emits a preset:<name>?c1=..&c2=.. value when a color is changed', () => {
    const onChange = vi.fn();
    render(<AvatarPicker value="preset:purple" onChange={onChange} />);

    const primary = screen.getByLabelText('avatarPicker.primaryColor') as HTMLInputElement;
    fireEvent.change(primary, { target: { value: '#ff0000' } });

    expect(onChange).toHaveBeenCalledWith('preset:purple?c1=FF0000&c2=4338CA');
  });

  it('reset returns to the bare preset value', () => {
    const onChange = vi.fn();
    render(<AvatarPicker value="preset:purple?c1=FF0000&c2=4338CA" onChange={onChange} />);

    fireEvent.click(screen.getByText('avatarPicker.reset'));

    expect(onChange).toHaveBeenCalledWith('preset:purple');
  });

  it('keeps the customized preset visually selected in the grid', () => {
    render(<AvatarPicker value="preset:teal?c1=112233&c2=445566" onChange={vi.fn()} />);
    // The teal tile carries the selected ring; resolved through the same helper the app uses.
    expect(getAvatarPreset('preset:teal?c1=112233&c2=445566')?.id).toBe('preset:teal');
    expect(getPresetDefaultName('preset:teal?c1=112233&c2=445566')).toBe('Helix');
  });
});

describe('AvatarPicker - AI generation', () => {
  const SVG = '<svg xmlns="http://www.w3.org/2000/svg"><circle cx="50" cy="50" r="50" fill="#123456"/></svg>';

  function setup(overrides?: { onGenerate?: (p: string) => Promise<string>; onUpload?: (f: File) => Promise<string> }) {
    const onChange = vi.fn();
    const onGenerate = overrides?.onGenerate ?? vi.fn().mockResolvedValue(SVG);
    const onUpload = overrides?.onUpload ?? vi.fn().mockResolvedValue('/api/proxy/files/avatar/new-id');
    render(<AvatarPicker value="preset:purple" onChange={onChange} onUpload={onUpload} onGenerate={onGenerate} />);
    return { onChange, onGenerate, onUpload };
  }

  it('generates a preview then persists through onUpload as an SVG file on accept', async () => {
    const { onChange, onGenerate, onUpload } = setup();

    fireEvent.click(screen.getByTitle('avatarPicker.aiTitle'));
    fireEvent.change(screen.getByPlaceholderText('avatarPicker.aiPromptPlaceholder'), {
      target: { value: 'a robot fox' },
    });
    fireEvent.click(screen.getByText('avatarPicker.aiGenerate'));

    await waitFor(() => expect(screen.getByAltText('avatarPicker.aiPreviewAlt')).toBeInTheDocument());
    expect(onGenerate).toHaveBeenCalledWith('a robot fox');

    fireEvent.click(screen.getByText('avatarPicker.aiUse'));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith('/api/proxy/files/avatar/new-id'));
    const uploaded = (onUpload as ReturnType<typeof vi.fn>).mock.calls[0][0] as File;
    expect(uploaded.type).toBe('image/svg+xml');
    expect(uploaded.name).toBe('ai-avatar.svg');
  });

  it('surfaces a generation failure without touching the selection', async () => {
    const { onChange } = setup({ onGenerate: vi.fn().mockRejectedValue(new Error('boom')) });

    fireEvent.click(screen.getByTitle('avatarPicker.aiTitle'));
    fireEvent.change(screen.getByPlaceholderText('avatarPicker.aiPromptPlaceholder'), {
      target: { value: 'x' },
    });
    fireEvent.click(screen.getByText('avatarPicker.aiGenerate'));

    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryByText('avatarPicker.aiUse')).not.toBeInTheDocument();
  });

  it('does not offer the AI tile without an onGenerate handler', () => {
    render(<AvatarPicker value="preset:purple" onChange={vi.fn()} onUpload={vi.fn()} />);
    expect(screen.queryByTitle('avatarPicker.aiTitle')).not.toBeInTheDocument();
  });
});

describe('AvatarPicker - preset helpers stay compatible', () => {
  it('bare preset ids still resolve (regression: existing agents)', () => {
    expect(getAvatarPreset(AVATAR_PRESETS[0].id)?.image).toBe('/avatars/avatar-1.svg');
    expect(getPresetDefaultName('preset:purple')).toBe('Nova');
    expect(getAvatarPreset('https://x.png')).toBeNull();
  });
});
