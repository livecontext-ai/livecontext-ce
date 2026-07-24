/**
 * @vitest-environment jsdom
 *
 * Pins the one format control, shared by the interface editor modal and the workflow inspector
 * (both of which save to the interface entity).
 *
 * The load-bearing case is Auto: it emits NULL, not a preset. Null means "no declared shape",
 * which is what keeps the screenshot a FULL-PAGE capture. Defaulting to `classic` would flip
 * every existing tall interface to an exact 1280x800 frame and crop it, silently.
 */
import { describe, it, expect, vi, afterEach, beforeAll } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Radix Select needs these; jsdom has neither.
beforeAll(() => {
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = () => {};
  (Element.prototype as unknown as { hasPointerCapture: () => boolean }).hasPointerCapture = () => false;
  (Element.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => {};
  (Element.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => {};
});

import { InterfaceFormatSelect } from '../InterfaceFormatSelect';

afterEach(cleanup);

function openMenu() {
  fireEvent.click(screen.getByRole('combobox'));
}

describe('InterfaceFormatSelect', () => {
  it('uses the app menu (a listbox), not a bare native select', () => {
    // The control has to look like every other menu in the app, not an unstyled browser dropdown.
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} />);

    expect(screen.getByRole('combobox')).toBeTruthy();
    expect(document.querySelector('select')).toBeNull();
  });

  it('shows Auto as the SELECTED option when the interface declares no shape', () => {
    // Checking the trigger's text alone proves nothing here: the placeholder is also "formatAuto",
    // so it reads the same whether Auto is genuinely selected or the value fell through to the
    // placeholder. Radix forbids an empty item value, so Auto has to ride the app's sentinel; this
    // asserts the option is really checked.
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} />);

    expect(screen.getByRole('combobox').textContent).toContain('formatAuto');
    openMenu();
    // data-state, not aria-selected: Radix uses the latter for the highlighted item, not the
    // chosen one, so it reads "false" even on the selected option.
    expect(screen.getByRole('option', { name: /formatAuto/ }).getAttribute('data-state')).toBe('checked');
  });

  it('shows the stored preset', () => {
    render(<InterfaceFormatSelect value="vertical" onChange={() => undefined} />);

    expect(screen.getByRole('combobox').textContent).toContain('formatPreset_vertical');
  });

  it('emits the preset name when one is picked', () => {
    const onChange = vi.fn();
    render(<InterfaceFormatSelect value={null} onChange={onChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatPreset_vertical'));

    expect(onChange).toHaveBeenCalledWith('vertical');
  });

  it('emits NULL for Auto - never a preset, so the full-page capture is preserved', () => {
    const onChange = vi.fn();
    render(<InterfaceFormatSelect value="vertical" onChange={onChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatAuto'));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('normalises a custom WxH before emitting it', () => {
    const onChange = vi.fn();
    render(<InterfaceFormatSelect value={null} onChange={onChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.change(document.querySelector('input') as HTMLInputElement, {
      target: { value: '1080X1920' },
    });

    expect(onChange).toHaveBeenCalledWith('1080x1920');
  });

  it('keeps Custom selected under a controlled parent, and does not clear the shape on the way in', () => {
    // Regression: picking Custom used to emit the empty draft's null straight away. A controlled
    // parent feeds that back as value=null, the effect re-derives to Auto, and the input the user
    // just asked for disappears under the cursor - having wiped the stored format en route.
    const onChange = vi.fn();
    function Controlled() {
      const [format, setFormat] = React.useState<string | null>('vertical');
      return (
        <InterfaceFormatSelect
          value={format}
          onChange={(f) => {
            onChange(f);
            setFormat(f);
          }}
        />
      );
    }
    render(<Controlled />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));

    expect(document.querySelector('input')).toBeTruthy();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('does not rewrite the draft while typing under a controlled parent', () => {
    // The effect must ignore its own echo. Re-deriving from it would replace what was typed with
    // the normalised form mid-keystroke.
    function Controlled() {
      const [format, setFormat] = React.useState<string | null>(null);
      return <InterfaceFormatSelect value={format} onChange={setFormat} />;
    }
    render(<Controlled />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    const input = document.querySelector('input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '800X400' } });

    expect(input.value).toBe('800X400');
  });

  it('preselects Custom and prefills the draft for a stored WxH', () => {
    render(<InterfaceFormatSelect value="800x400" onChange={() => undefined} />);

    expect(screen.getByRole('combobox').textContent).toContain('formatCustom');
    expect((document.querySelector('input') as HTMLInputElement).value).toBe('800x400');
  });

  it('raises invalid for an out-of-range custom size, so the caller can block its save', () => {
    // The trap: an unusable draft normalises to null, and saving null would WIPE the shape -
    // the opposite of what was typed. The caller needs this signal to refuse the write.
    const onValidityChange = vi.fn();
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} onValidityChange={onValidityChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.change(document.querySelector('input') as HTMLInputElement, {
      target: { value: '9999x9999' },
    });

    expect(onValidityChange).toHaveBeenCalledWith(true);
  });

  it('treats a BLANK custom draft as invalid, so the caller cannot save the old format behind it', () => {
    // Picking Custom emits nothing until the draft is usable. Without this signal the caller
    // would happily save the PREVIOUS format while the UI shows an empty Custom box.
    const onValidityChange = vi.fn();
    render(<InterfaceFormatSelect value="vertical" onChange={() => undefined} onValidityChange={onValidityChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));

    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });

  it('clears invalid once the custom size becomes usable again', () => {
    const onValidityChange = vi.fn();
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} onValidityChange={onValidityChange} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    const input = document.querySelector('input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '9999x9999' } });
    onValidityChange.mockClear();
    fireEvent.change(input, { target: { value: '1080x1920' } });

    expect(onValidityChange).toHaveBeenLastCalledWith(false);
  });

  it('surfaces the error inline on blur for a BLANK draft too, not just a malformed one', () => {
    // A blank draft blocks the save exactly like a malformed one, so staying silent about it is
    // what turns the caller's button into an unexplained dead end.
    render(<InterfaceFormatSelect value="vertical" onChange={() => undefined} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.blur(document.querySelector('input') as HTMLInputElement);

    expect(screen.getByText('formatCustomInvalid')).toBeTruthy();
  });

  it('shows the help paragraph by default', () => {
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} />);

    expect(screen.getByText('formatHelp')).toBeTruthy();
  });

  it('drops the help paragraph on request, for callers whose column is too narrow', () => {
    // The Auto option keeps its own description, so the essential "whole page at 1280" fact is
    // still on screen without the paragraph.
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} showHelp={false} />);

    expect(screen.queryByText('formatHelp')).toBeNull();
  });

  it('uses a caller-supplied id so two of these can co-render', () => {
    // The builder shows the inspector and the create-interface modal at once; duplicate ids are
    // invalid HTML and make the modal's label activate the trigger behind the dialog.
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} id="custom-id" />);

    expect(document.querySelector('#custom-id')).toBeTruthy();
    expect(document.querySelector('#interface-format-select')).toBeNull();
  });

  it('surfaces the error inline on blur', () => {
    render(<InterfaceFormatSelect value={null} onChange={() => undefined} />);

    openMenu();
    fireEvent.click(screen.getByText('formatCustom'));
    const input = document.querySelector('input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '9999x9999' } });
    fireEvent.blur(input);

    expect(screen.getByText('formatCustomInvalid')).toBeTruthy();
  });
});
