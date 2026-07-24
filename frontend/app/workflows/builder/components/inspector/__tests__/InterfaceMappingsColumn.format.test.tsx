/**
 * @vitest-environment jsdom
 *
 * The format belongs to the INTERFACE, not to the node that renders it, so the inspector edits it
 * in the same zone as the html/css/js it constrains and saves it to the same entity.
 *
 * This pins that wiring: the stored format is read back into the control, the Save writes it to
 * the interface, and an unusable custom size blocks the Save instead of clearing the shape.
 */
import { describe, it, expect, vi, afterEach, beforeEach, beforeAll } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';

const updateInterface = vi.fn().mockResolvedValue({ htmlTemplate: '<div>x</div>', format: 'vertical' });
const refetch = vi.fn().mockResolvedValue({});
const interfaceDetails: { current: Record<string, unknown> } = { current: {} };

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    updateInterface: (...args: unknown[]) => updateInterface(...args),
  },
}));
// refetch is REQUIRED: the component calls it after a successful save. Without it the save path
// throws into a catch that swallows the error, so every post-save assertion silently tests nothing.
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: interfaceDetails.current, isLoading: false, refetch }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('reactflow', () => ({ useNodes: () => [], useEdges: () => [] }));
vi.mock('@/hooks/useFeatureCapabilities', () => ({
  useFeatureCapabilities: () => ({ capabilities: { renderer: true }, isLoading: false }),
}));
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <textarea data-testid="editor-stub" value={value ?? ''} onChange={(e) => onChange(e.target.value)} />
  ),
}));

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

import { InterfaceMappingsColumn } from '../InterfaceMappingsColumn';

/** The column as the builder renders it: no `key` at the call site, so selecting another node
 *  REUSES this instance instead of remounting it. Tests that switch interfaces must rerender this
 *  element rather than unmount/mount, or they silently test a remount that never happens. */
function inspectorElement({
  onUpdate = () => undefined,
  interfaceId = 'iface-1',
  nodeId = 'n1',
}: { onUpdate?: (d: unknown) => void; interfaceId?: string; nodeId?: string } = {}) {
  const data = {
    label: 'My Interface',
    interfaceData: { interfaceId, editorExpression: '<div>x</div>' },
  } as never;
  return (
    <InterfaceMappingsColumn
      node={{ id: nodeId, data }}
      data={data}
      onUpdate={onUpdate as never}
      connections={[]}
      draggingFromHandle={null}
      hoveredTargetHandle={null}
      handleHandleClick={() => undefined}
      handleHandleMouseDown={() => undefined}
      handleHandleMouseUp={() => undefined}
      handleSetHandleRef={() => undefined}
      findUnknownVariables={() => []}
      getEditorExpression={() => '<div>x</div>'}
      handleEditorExpressionChange={() => undefined}
    />
  );
}

function mountInspector(opts: Parameters<typeof inspectorElement>[0] = {}) {
  return render(inspectorElement(opts));
}

function formatTrigger() {
  return document.querySelector('#inspector-interface-format-select') as HTMLElement;
}

function enterEditMode() {
  fireEvent.click(screen.getByText('edit'));
}

describe('InterfaceMappingsColumn - interface format', () => {
  beforeEach(() => {
    updateInterface.mockClear();
    updateInterface.mockResolvedValue({ htmlTemplate: '<div>x</div>', format: 'vertical' });
    refetch.mockClear();
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: null };
  });
  afterEach(cleanup);

  it('completes the save path (refetches) rather than throwing into a swallowed catch', async () => {
    // Guards the mock itself: if the component's post-save collaborators go unmocked, every
    // assertion below would pass against a silently aborted save.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatPreset_square'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('offers the format where the html/css/js are edited, since it is what they are written for', async () => {
    mountInspector();

    await waitFor(() => expect(formatTrigger()).toBeTruthy());
  });

  it('reads the stored format back from the interface', async () => {
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();

    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_vertical'));
  });

  it('shows Auto for an interface that declares no shape', async () => {
    mountInspector();

    await waitFor(() => expect(formatTrigger().textContent).toContain('formatAuto'));
  });

  it('saves the picked format onto the interface, alongside the templates', async () => {
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatPreset_vertical'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() =>
      expect(updateInterface).toHaveBeenCalledWith('iface-1', expect.objectContaining({ format: 'vertical' })),
    );
  });

  it('always sends the format key, including null, so the shape can be cleared back to Auto', async () => {
    // The REST layer keys the clear off the KEY's presence. Omit it and an interface can never
    // return to Auto - it keeps its declared shape forever.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatAuto'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(updateInterface).toHaveBeenCalled());
    const payload = updateInterface.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('format');
    expect(payload.format).toBeNull();
  });

  it('restores the format on Cancel, so an abandoned pick is not written by a later save', async () => {
    // Regression: editedFormat survived Cancel while html/css/js were restored. The abandoned
    // pick then rode along on the next unrelated save and silently changed the shape.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());

    enterEditMode();
    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatPreset_square'));
    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_square'));
    fireEvent.click(screen.getByText('cancel'));

    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_vertical'));

    // ...and the abandoned value must not resurface in the next save.
    enterEditMode();
    fireEvent.click(screen.getByText('save'));
    await waitFor(() => expect(updateInterface).toHaveBeenCalled());
    expect(updateInterface.mock.calls[0][1]).toMatchObject({ format: 'vertical' });
  });

  it('fully resets the control on Cancel, keeping the invalid-size guard alive afterwards', async () => {
    // Regression: Cancel restored editedFormat but the control kept its own "Custom" selection and
    // blank draft (it ignores an echoed value by design), while the parent force-cleared the
    // invalid flag. The flag could then never be re-raised - so a later unusable size left Save
    // ENABLED and wrote the OLD format while the box showed something else.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    // Pick Custom and leave the draft blank: nothing is emitted, so the parent's value never moves.
    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    expect(screen.queryByTestId('interface-format-custom')).toBeTruthy();

    fireEvent.click(screen.getByText('cancel'));

    // The control must show the stored format again, not a stale empty Custom box.
    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_vertical'));
    expect(screen.queryByTestId('interface-format-custom')).toBeNull();

    // ...and the guard must still fire for a genuinely unusable size.
    enterEditMode();
    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.change(screen.getByTestId('interface-format-custom'), { target: { value: '9999x9999' } });
    fireEvent.click(screen.getByText('save'));

    expect(updateInterface).not.toHaveBeenCalled();
  });

  it('marks the node dirty for a format-only edit, like an html/css/js edit', async () => {
    // hasChanges feeds hasUnsavedInterfaceChanges, which drives the unsaved indicator and the
    // validation service. Omitting the format there leaves a real edit looking pristine.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: null };
    const onUpdate = vi.fn();
    mountInspector({ onUpdate });
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();
    onUpdate.mockClear();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatPreset_vertical'));

    await waitFor(() =>
      expect(onUpdate).toHaveBeenCalledWith(expect.objectContaining({ hasUnsavedInterfaceChanges: true })),
    );
  });

  it('keeps the format control read-only outside edit mode', async () => {
    // originalFormatOnEdit is captured on load, never re-captured on Edit, so it is only a valid
    // restore point while the control cannot be touched outside edit mode.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();

    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    expect(formatTrigger().getAttribute('disabled')).not.toBeNull();
  });

  it('never saves the OLD format behind a blank Custom box', async () => {
    // Picking Custom emits nothing until the draft is usable, so without the guard the Save would
    // write the previous format while the box sits there empty.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.click(screen.getByText('save'));

    expect(updateInterface).not.toHaveBeenCalled();
  });

  it('does not strand a disabled Save when the section is collapsed mid-edit', async () => {
    // Regression: the control lives INSIDE the collapsible section, the Save button OUTSIDE it.
    // Collapsing with a blank Custom draft unmounted the control, so it could never report itself
    // valid again: Save stayed greyed out with nothing on screen to explain why.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    expect((screen.getByText('save').closest('button') as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByText('interfaceTemplateSection'));
    await waitFor(() => expect(formatTrigger()).toBeNull());

    expect((screen.getByText('save').closest('button') as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not carry a half-typed size onto another interface that shares its format', async () => {
    // Regression: the column is reused across nodes (no key at its call site) and the control
    // ignores an echoed value. With both interfaces on `vertical`, `value` never changes, so a
    // blank Custom draft from the first survived onto the second - stale box, Save stuck disabled,
    // no error. Keying the control per interface is what forces the reset.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    const { rerender } = render(inspectorElement({ interfaceId: 'iface-1' }));
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();
    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    expect(screen.queryByTestId('interface-format-custom')).toBeTruthy();

    // Selecting another node REUSES this column (no key at the call site), so rerender rather than
    // remount: a remount would reset the control by itself and prove nothing.
    interfaceDetails.current = { id: 'iface-2', htmlTemplate: '<div>y</div>', format: 'vertical' };
    rerender(inspectorElement({ interfaceId: 'iface-2', nodeId: 'n2' }));

    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_vertical'));
    expect(screen.queryByTestId('interface-format-custom')).toBeNull();
  });

  it('gives its select a distinct DOM id from the editor modal\'s', async () => {
    // Both can be on screen at once in the builder. Duplicate ids are invalid HTML and make the
    // modal's <label htmlFor> activate this trigger behind the dialog.
    mountInspector();

    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    expect(document.querySelector('#interface-format-select')).toBeNull();
  });

  it('reads the saved format back from the response after a save', async () => {
    // The response is the source of truth for what was stored; the control has to follow it or it
    // shows one shape while the interface has another.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: null };
    updateInterface.mockResolvedValue({ htmlTemplate: '<div>x</div>', format: 'square' });
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatPreset_vertical'));
    fireEvent.click(screen.getByText('save'));

    await waitFor(() => expect(formatTrigger().textContent).toContain('formatPreset_square'));
  });

  it('blocks the save on an unusable custom size instead of clearing the shape', async () => {
    // An out-of-range draft normalises to null, and null is a real value meaning "no declared
    // shape". Saving it would wipe the format the user was in the middle of typing.
    interfaceDetails.current = { id: 'iface-1', htmlTemplate: '<div>x</div>', format: 'vertical' };
    mountInspector();
    await waitFor(() => expect(formatTrigger()).toBeTruthy());
    enterEditMode();

    fireEvent.click(formatTrigger());
    fireEvent.click(screen.getByText('formatCustom'));
    fireEvent.change(screen.getByTestId('interface-format-custom'), { target: { value: '9999x9999' } });
    fireEvent.click(screen.getByText('save'));

    expect(updateInterface).not.toHaveBeenCalled();
  });
});
