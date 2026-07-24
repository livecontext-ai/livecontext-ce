/**
 * @vitest-environment jsdom
 *
 * Pins the interface editor's Format control - the surface where an interface's shape is now
 * authored, next to the HTML it constrains.
 *
 * Scope: the MODAL's contract, driven through the real InterfaceFormatSelect (no stub, so a
 * regression in either half shows up here). The control's own behaviour is pinned separately in
 * components/interfaces/__tests__/InterfaceFormatSelect.test.tsx.
 *
 * The load-bearing case is Auto: it saves NULL, not a preset. Null means "no declared shape",
 * which is what makes the screenshot a FULL-PAGE capture. Defaulting to `classic` would flip
 * every existing tall interface to an exact 1280x800 frame and crop it, silently.
 */
import { describe, it, expect, vi, afterEach, beforeEach, beforeAll } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import * as React from 'react';

const updateInterface = vi.fn().mockResolvedValue({});
const createInterface = vi.fn().mockResolvedValue({});

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    updateInterface: (...args: unknown[]) => updateInterface(...args),
    createInterface: (...args: unknown[]) => createInterface(...args),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The editor and the thumbnail are not under test here; stub them so the modal mounts in jsdom.
// The stub records fullHeight because that prop is load-bearing for the editors' size (below).
const editorProps: Record<string, unknown>[] = [];
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: (props: { value: string; onChange: (v: string) => void }) => {
    editorProps.push(props);
    return <textarea data-testid="editor-stub" value={props.value} onChange={(e) => props.onChange(e.target.value)} />;
  },
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: () => <div data-testid="thumb-stub" />,
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

import { CreateInterfaceModal } from '../CreateInterfaceModal';

function openEditor(interfaceData: Record<string, unknown>) {
  return render(
    <CreateInterfaceModal
      onClose={() => undefined}
      onInterfaceCreated={() => undefined}
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      interfaceData={interfaceData as any}
    />,
  );
}

const BASE = { id: 'iface-1', name: 'Card', htmlTemplate: '<div>x</div>' };

/** Picks an option in the format menu by its visible label. */
function pickFormat(label: string) {
  fireEvent.click(document.querySelector('#interface-format-select') as HTMLElement);
  fireEvent.click(screen.getByText(label));
}

function typeCustom(size: string) {
  fireEvent.change(screen.getByTestId('interface-format-custom'), { target: { value: size } });
}

async function save() {
  // Edit mode: the confirm button is labelled 'update'.
  await fireEvent.click(screen.getByText('update'));
  await Promise.resolve();
}

describe('CreateInterfaceModal - Format control', () => {
  beforeEach(() => {
    updateInterface.mockClear();
    createInterface.mockClear();
    editorProps.length = 0;
  });
  afterEach(cleanup);

  it('gives every code editor fullHeight inside a sized parent, so they are not tiny', () => {
    // ExpressionEditor hugs its content and hard-caps at max-h-[200px] unless `fullHeight` is set,
    // which makes it fill its parent instead. A min-h class on the editor is overridden by that
    // cap, so `fullHeight` + a fixed-height wrapper is what actually makes the box tall.
    openEditor(BASE);

    expect(editorProps.length).toBeGreaterThanOrEqual(3); // html + css + js
    for (const p of editorProps) expect(p.fullHeight).toBe(true);

    // Measure each editor's OWN wrapper (fullHeight fills the parent, so that parent is what sizes
    // it). Scanning the whole modal would let an unrelated tall div mask a shrunken editor.
    const heights = [...document.querySelectorAll('[data-testid="editor-stub"]')].map((stub) => {
      const wrapper = stub.closest('div[class*="h-["]');
      const px = wrapper?.className.match(/h-\[(\d+)px\]/);
      return px ? Number(px[1]) : 0;
    });
    expect(heights).toHaveLength(3);
    expect(Math.max(...heights)).toBeGreaterThanOrEqual(300); // the HTML editor
    expect(Math.min(...heights)).toBeGreaterThanOrEqual(200); // CSS and JS
  });

  it('shows the app menu for the format, not a bare native select', () => {
    // The user asked for the existing menu style; a native <select> is the thing being replaced.
    openEditor(BASE);

    expect(document.querySelector('#interface-format-select')).toBeTruthy();
    expect(document.querySelector('select')).toBeNull();
  });

  it('defaults to Auto (no declared shape) for an interface that declares no format', () => {
    // Never a preset: Auto is what keeps the full-page capture for legacy tall interfaces.
    openEditor(BASE);

    expect((document.querySelector('#interface-format-select') as HTMLElement).textContent).toContain(
      'formatAuto',
    );
  });

  it('preselects the stored preset when the interface declares one', () => {
    openEditor({ ...BASE, format: 'vertical' });

    expect((document.querySelector('#interface-format-select') as HTMLElement).textContent).toContain(
      'formatPreset_vertical',
    );
  });

  it('saves the chosen preset', async () => {
    openEditor(BASE);
    pickFormat('formatPreset_vertical');

    await save();

    expect(updateInterface).toHaveBeenCalledWith('iface-1', expect.objectContaining({ format: 'vertical' }));
  });

  it('sends format: null on Auto - and always sends the key, so the shape can be cleared', async () => {
    // The REST layer keys the clear off the KEY's presence, so an omitted format would make
    // "back to Auto" unreachable: the interface would keep its old shape forever.
    openEditor({ ...BASE, format: 'vertical' });
    pickFormat('formatAuto');

    await save();

    const payload = updateInterface.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('format');
    expect(payload.format).toBeNull();
  });

  it('normalises a custom WxH before saving', async () => {
    openEditor(BASE);
    pickFormat('formatCustom');
    typeCustom('1080X1920');

    await save();

    expect(updateInterface).toHaveBeenCalledWith('iface-1', expect.objectContaining({ format: '1080x1920' }));
  });

  it('preselects Custom and prefills the draft for a stored WxH', () => {
    openEditor({ ...BASE, format: '800x400' });

    expect((document.querySelector('#interface-format-select') as HTMLElement).textContent).toContain(
      'formatCustom',
    );
    expect((screen.getByTestId('interface-format-custom') as HTMLInputElement).value).toBe('800x400');
  });

  it('cannot be made to save an invalid custom size, which would clear the shape', async () => {
    // The trap: an unusable draft normalises to null, and null is a REAL value meaning "no
    // declared shape". Saving it would wipe the interface's format - the opposite of what the
    // user typed. The button carries the block, so that is what this asserts; handleSave's own
    // guard is unreachable belt-and-braces for the day someone drops the disabled attribute.
    openEditor({ ...BASE, format: 'vertical' });
    pickFormat('formatCustom');
    typeCustom('9999x9999');

    expect((screen.getByText('update').closest('button') as HTMLButtonElement).disabled).toBe(true);

    await save();

    expect(updateInterface).not.toHaveBeenCalled();
  });

  it('disables the button for a blank Custom draft instead of a silent no-op', async () => {
    // Regression: the save refused the write (correct) but the button stayed enabled and nothing
    // was shown, so clicking it did nothing, repeatedly, with no explanation.
    openEditor({ ...BASE, format: 'vertical' });
    pickFormat('formatCustom');

    const button = screen.getByText('update').closest('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('explains a blank Custom draft on blur rather than just blocking', async () => {
    openEditor({ ...BASE, format: 'vertical' });
    pickFormat('formatCustom');
    fireEvent.blur(screen.getByTestId('interface-format-custom'));

    expect(screen.getByText('formatCustomInvalid')).toBeTruthy();
  });

  it('never saves the OLD format behind a blank Custom box', async () => {
    // The dangerous shape of the same bug: the box shows Custom+empty while `vertical` is what
    // would be written, so the user sees one thing and the interface stores another.
    openEditor({ ...BASE, format: 'vertical' });
    pickFormat('formatCustom');

    await save();

    expect(updateInterface).not.toHaveBeenCalled();
  });

  it('saves normally once the invalid custom size is corrected', async () => {
    // Guards the block above from becoming a dead end: the save must actually go through again,
    // with the corrected size, once the draft is usable.
    openEditor(BASE);
    pickFormat('formatCustom');
    typeCustom('9999x9999');
    typeCustom('1080x1920');

    await save();

    expect(updateInterface).toHaveBeenCalledWith('iface-1', expect.objectContaining({ format: '1080x1920' }));
  });
});
