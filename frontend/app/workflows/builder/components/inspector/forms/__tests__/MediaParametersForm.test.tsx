// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - keep the form renderable without dragging in real i18n/Radix portals.
// The i18n key IS the rendered text so labels can be asserted directly.
// ---------------------------------------------------------------------------
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

// ExpressionEditor -> plain textarea (value/onChange/placeholder are all we need).
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange, placeholder, readOnly }: any) => (
    <textarea value={value} placeholder={placeholder} readOnly={readOnly} onChange={(e) => onChange?.(e.target.value)} />
  ),
}));

// Radix Select -> native <select> so options are inspectable and changeable in jsdom.
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, disabled, children }: any) => (
    <select value={value ?? ''} disabled={disabled} onChange={(e) => onValueChange?.(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));

vi.mock('@/components/ui/input', () => ({ Input: (p: any) => <input {...p} /> }));
vi.mock('@/components/ui/slider', () => ({ Slider: () => null }));
vi.mock('@/components/ui/switch', () => ({
  Switch: ({ checked, onCheckedChange, disabled }: any) => (
    <button type="button" role="switch" aria-checked={checked} disabled={disabled} onClick={() => onCheckedChange?.(!checked)} />
  ),
}));

// Collapsibles render OPEN so the options/advanced sections are always in the DOM.
vi.mock('@/components/ui/collapsible', () => ({
  Collapsible: ({ children }: any) => <div>{children}</div>,
  CollapsibleTrigger: ({ children }: any) => <button type="button">{children}</button>,
  CollapsibleContent: ({ children }: any) => <div>{children}</div>,
}));

vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <div>{children}</div>,
  PopoverContent: () => null,
}));

import { MediaParametersForm } from '../MediaParametersForm';

afterEach(cleanup);

const connectionProps = {
  connections: [],
  draggingFromHandle: null,
  hoveredTargetHandle: null,
  handleHandleClick: vi.fn(),
  handleHandleMouseDown: vi.fn(),
  handleHandleMouseUp: vi.fn(),
  handleSetHandleRef: vi.fn(),
} as any;

function renderForm(data: any, onUpdate = vi.fn()) {
  const utils = render(
    <MediaParametersForm
      node={{ id: 'node-media-1', data } as any}
      data={data}
      onUpdate={onUpdate}
      connectionProps={connectionProps}
      findUnknownVariables={() => []}
    />,
  );
  return { onUpdate, ...utils };
}

/**
 * The per-operation options now live inside the platform-standard
 * OptionalSection (real component, closed by default) - open it before
 * asserting anything inside.
 */
function openOptions() {
  fireEvent.click(screen.getByText(/Optional parameters/));
}

function mixData(tracks: any[], extraParams: Record<string, any> = {}) {
  return { mediaOperation: 'mix', mediaParams: { tracks, ...extraParams } } as any;
}

function removeTrackAt(index: number) {
  fireEvent.click(screen.getAllByLabelText('media.removeTrack')[index]);
}

function updatedTracks(onUpdate: ReturnType<typeof vi.fn>): any[] {
  expect(onUpdate).toHaveBeenCalledTimes(1);
  return onUpdate.mock.calls[0][0].mediaParams.tracks;
}

/** The duck_under selects are the only ones offering the __none__ sentinel option. */
function duckSelects(container: HTMLElement): HTMLSelectElement[] {
  return Array.from(container.querySelectorAll('select')).filter((sel) =>
    Array.from(sel.options).some((o) => o.value === '__none__'),
  );
}

describe('MediaParametersForm - mix track removal keeps duck_under references sane', () => {
  it('removing a ducked-under track CLEARS the referencing track duck_under and its duck_* companions', () => {
    const { onUpdate } = renderForm(mixData([
      { source: '{{a}}' },
      { source: '{{b}}', duck_under: 'track_1', duck_amount_db: 6, duck_attack_ms: 10, duck_release_ms: 150 },
    ]));

    removeTrackAt(0);

    // The survivor no longer ducks under anything: reference AND companions are gone.
    expect(updatedTracks(onUpdate)).toEqual([{ source: '{{b}}' }]);
  });

  it('removing an earlier track REWRITES a duck_under that pointed at a later track positional default id (track_3 -> track_2)', () => {
    const { onUpdate } = renderForm(mixData([
      { source: '{{a}}' },
      { source: '{{b}}', duck_under: 'track_3' },
      { source: '{{c}}' },
    ]));

    removeTrackAt(0);

    // {{c}} renumbers track_3 -> track_2; the reference follows it (no explicit id materialized).
    expect(updatedTracks(onUpdate)).toEqual([
      { source: '{{b}}', duck_under: 'track_2' },
      { source: '{{c}}' },
    ]);
  });

  it('a duck_under pointing at a surviving EXPLICIT id survives removal untouched', () => {
    const { onUpdate } = renderForm(mixData([
      { source: '{{a}}' },
      { source: '{{b}}', duck_under: 'music', duck_amount_db: 9 },
      { source: '{{c}}', id: 'music' },
    ]));

    removeTrackAt(0);

    // Explicit ids do not renumber: the reference (and its tuning) is preserved verbatim.
    expect(updatedTracks(onUpdate)).toEqual([
      { source: '{{b}}', duck_under: 'music', duck_amount_db: 9 },
      { source: '{{c}}', id: 'music' },
    ]);
  });
});

describe('MediaParametersForm - existing behaviors pinned', () => {
  it('toggling loop ON clears trim_start_seconds and trim_end_seconds (loop + trim is invalid per the contract)', () => {
    const { onUpdate } = renderForm({
      mediaOperation: 'mux_audio',
      mediaParams: { video: '{{v}}', audio: '{{a}}', trim_start_seconds: 3, trim_end_seconds: 8 },
    } as any);

    openOptions();
    const loopRow = screen.getByText('media.loop').closest('div') as HTMLElement;
    fireEvent.click(within(loopRow).getByRole('switch'));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const next = onUpdate.mock.calls[0][0].mediaParams;
    expect(next.loop).toBe(true);
    expect('trim_start_seconds' in next).toBe(false);
    expect('trim_end_seconds' in next).toBe(false);
  });

  it('the duck_under select for a track lists ONLY the OTHER tracks effective ids, not its own', () => {
    const { container } = renderForm(mixData([
      { source: '{{a}}' },
      { source: '{{b}}', id: 'voice' },
      { source: '{{c}}' },
    ]));

    const selects = duckSelects(container);
    expect(selects).toHaveLength(3);

    // Track 1 (positional id track_1) offers none + voice + track_3, never track_1.
    const firstTrackOptions = Array.from(selects[0].options).map((o) => o.value);
    expect(firstTrackOptions).toEqual(['__none__', 'voice', 'track_3']);

    // Track 2 (explicit id voice) offers the two positional ids, never voice.
    const secondTrackOptions = Array.from(selects[1].options).map((o) => o.value);
    expect(secondTrackOptions).toEqual(['__none__', 'track_1', 'track_3']);
  });
});

function concatData(inputs: any[], extraParams: Record<string, any> = {}) {
  return { mediaOperation: 'concat', mediaParams: { inputs, ...extraParams } } as any;
}

function updatedInputs(onUpdate: ReturnType<typeof vi.fn>): any[] {
  expect(onUpdate).toHaveBeenCalledTimes(1);
  return onUpdate.mock.calls[0][0].mediaParams.inputs;
}

describe('MediaParametersForm - v2 operation switch renders each new form', () => {
  it('the operation select offers all 7 operations', () => {
    const { container } = renderForm({ mediaOperation: 'probe', mediaParams: { input: '{{f}}' } } as any);
    const opSelect = Array.from(container.querySelectorAll('select')).find((sel) =>
      Array.from(sel.options).some((o) => o.value === 'probe'),
    ) as HTMLSelectElement;
    expect(Array.from(opSelect.options).map((o) => o.value)).toEqual(
      ['probe', 'mux_audio', 'mix', 'extract_audio', 'concat', 'frame', 'overlay'],
    );
  });

  it('concat renders the clips editor, the transition select, and the concat options', () => {
    renderForm(concatData([{ source: '{{a}}' }]));
    expect(screen.getByText('media.clips')).toBeTruthy();
    expect(screen.getByText('media.clip')).toBeTruthy();
    openOptions();
    expect(screen.getByText('media.transition')).toBeTruthy();
    expect(screen.getByText('media.targetWidth')).toBeTruthy();
    expect(screen.getByText('media.targetHeight')).toBeTruthy();
    expect(screen.getByText('media.targetFps')).toBeTruthy();
    expect(screen.getByText('media.normalize')).toBeTruthy();
    expect(screen.getByText('media.audioBitrate')).toBeTruthy();
  });

  it('frame renders input, timestamp (with the default-to-middle hint), image format, and width', () => {
    renderForm({ mediaOperation: 'frame', mediaParams: {} } as any);
    expect(screen.getByText('media.input')).toBeTruthy();
    openOptions();
    expect(screen.getByText('media.atSeconds')).toBeTruthy();
    expect(screen.getByText('media.atSecondsHint')).toBeTruthy();
    expect(screen.getByText('media.imageFormat')).toBeTruthy();
    expect(screen.getByText('media.frameWidth')).toBeTruthy();
  });

  it('overlay renders video, image, position, and the overlay options', () => {
    renderForm({ mediaOperation: 'overlay', mediaParams: {} } as any);
    expect(screen.getByText('media.video')).toBeTruthy();
    expect(screen.getByText('media.image')).toBeTruthy();
    openOptions();
    expect(screen.getByText('media.position')).toBeTruthy();
    expect(screen.getByText('media.marginPx')).toBeTruthy();
    expect(screen.getByText('media.widthPercent')).toBeTruthy();
    expect(screen.getByText('media.opacity')).toBeTruthy();
    expect(screen.getByText('media.startSeconds')).toBeTruthy();
    expect(screen.getByText('media.endSeconds')).toBeTruthy();
  });
});

describe('MediaParametersForm - platform-standard OptionalSection', () => {
  /** Root element of the rendered OptionalSection (header button + open content live inside it). */
  function optionalSectionRoot(): HTMLElement {
    return screen.getByText(/Optional parameters/).closest('div') as HTMLElement;
  }

  it('mux: required video/audio stay top-level while every option hides inside the CLOSED OptionalSection', () => {
    renderForm({ mediaOperation: 'mux_audio', mediaParams: { video: '{{v}}', audio: '{{a}}' } } as any);

    expect(screen.getByText('media.video')).toBeTruthy();
    expect(screen.getByText('media.audio')).toBeTruthy();
    // Closed by default: no option field is in the DOM.
    expect(screen.queryByText('media.volume')).toBeNull();
    expect(screen.queryByText('media.loop')).toBeNull();
    expect(screen.queryByText('media.normalize')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.volume')).toBeTruthy();
    expect(within(section).getByText('media.loop')).toBeTruthy();
    expect(within(section).getByText('media.normalize')).toBeTruthy();
    // Required fields did NOT move into the section.
    expect(section.contains(screen.getByText('media.video'))).toBe(false);
    expect(section.contains(screen.getByText('media.audio'))).toBe(false);
  });

  it('concat: clips stay top-level; transition and the other options render INSIDE the OptionalSection', () => {
    renderForm(concatData([{ source: '{{a}}' }]));

    expect(screen.queryByText('media.transition')).toBeNull();
    expect(screen.queryByText('media.targetWidth')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.transition')).toBeTruthy();
    expect(within(section).getByText('media.targetWidth')).toBeTruthy();
    expect(section.contains(screen.getByText('media.clips'))).toBe(false);
  });

  it('the section header shows the SET-options count', () => {
    renderForm(concatData([{ source: '{{a}}' }], { transition: 'crossfade', target_fps: 30 }));
    expect(screen.getByText('Optional parameters (2)')).toBeTruthy();
  });

  it('extract_audio: input stays top-level; format/bitrate/trims moved into the OptionalSection', () => {
    renderForm({ mediaOperation: 'extract_audio', mediaParams: { input: '{{f}}' } } as any);

    expect(screen.getByText('media.input')).toBeTruthy();
    expect(screen.queryByText('media.outputFormat')).toBeNull();
    expect(screen.queryByText('media.trimStartSeconds')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.outputFormat')).toBeTruthy();
    expect(within(section).getByText('media.audioBitrate')).toBeTruthy();
    expect(within(section).getByText('media.trimStartSeconds')).toBeTruthy();
    expect(section.contains(screen.getByText('media.input'))).toBe(false);
  });

  it('mix: the optional background video joins the OptionalSection; the tracks list stays top-level', () => {
    renderForm(mixData([{ source: '{{a}}' }]));

    expect(screen.getByText('media.tracks')).toBeTruthy();
    expect(screen.queryByText('media.video')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.video')).toBeTruthy();
    expect(section.contains(screen.getByText('media.tracks'))).toBe(false);
  });

  it('frame: input stays top-level; timestamp/format/width render INSIDE the OptionalSection', () => {
    renderForm({ mediaOperation: 'frame', mediaParams: {} } as any);

    expect(screen.getByText('media.input')).toBeTruthy();
    expect(screen.queryByText('media.atSeconds')).toBeNull();
    expect(screen.queryByText('media.imageFormat')).toBeNull();
    expect(screen.queryByText('media.frameWidth')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.atSeconds')).toBeTruthy();
    expect(within(section).getByText('media.imageFormat')).toBeTruthy();
    expect(within(section).getByText('media.frameWidth')).toBeTruthy();
    expect(section.contains(screen.getByText('media.input'))).toBe(false);
  });

  it('overlay: required video/image stay top-level; position and the tuning options render INSIDE the OptionalSection', () => {
    renderForm({ mediaOperation: 'overlay', mediaParams: {} } as any);

    expect(screen.getByText('media.video')).toBeTruthy();
    expect(screen.getByText('media.image')).toBeTruthy();
    expect(screen.queryByText('media.position')).toBeNull();
    expect(screen.queryByText('media.opacity')).toBeNull();

    openOptions();

    const section = optionalSectionRoot();
    expect(within(section).getByText('media.position')).toBeTruthy();
    expect(within(section).getByText('media.marginPx')).toBeTruthy();
    expect(within(section).getByText('media.opacity')).toBeTruthy();
    expect(section.contains(screen.getByText('media.video'))).toBe(false);
    expect(section.contains(screen.getByText('media.image'))).toBe(false);
  });

  it('probe renders NO OptionalSection (input is its only field)', () => {
    renderForm({ mediaOperation: 'probe', mediaParams: { input: '{{f}}' } } as any);
    expect(screen.queryByText(/Optional parameters/)).toBeNull();
  });
});

describe('MediaParametersForm - concat transition', () => {
  it('transition_seconds is hidden for cut (default) and shown only for crossfade', () => {
    const { unmount } = renderForm(concatData([{ source: '{{a}}' }, { source: '{{b}}' }]));
    openOptions();
    expect(screen.queryByText('media.transitionSeconds')).toBeNull();
    unmount();

    renderForm(concatData([{ source: '{{a}}' }, { source: '{{b}}' }], { transition: 'crossfade' }));
    openOptions();
    expect(screen.getByText('media.transitionSeconds')).toBeTruthy();
  });
});

describe('MediaParametersForm - concat clips add/remove/reorder', () => {
  it('the add button appends an empty clip and disables at the 8-clip cap', () => {
    const { onUpdate, unmount } = renderForm(concatData([{ source: '{{a}}' }]));
    fireEvent.click(screen.getByText('media.addClip'));
    expect(updatedInputs(onUpdate)).toEqual([{ source: '{{a}}' }, { source: '' }]);
    unmount();

    const eight = Array.from({ length: 8 }, (_, i) => ({ source: `{{c${i}}}` }));
    renderForm(concatData(eight));
    expect((screen.getByText('media.addClip').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('removing a MIDDLE clip keeps the other clips intact (order and per-item fields preserved)', () => {
    const { onUpdate } = renderForm(concatData([
      { source: '{{a}}', speed: 1.5 },
      { source: '{{b}}', trim_start_seconds: 2 },
      { source: '{{c}}', trim_end_seconds: 9 },
    ]));

    fireEvent.click(screen.getAllByLabelText('media.removeClip')[1]);

    expect(updatedInputs(onUpdate)).toEqual([
      { source: '{{a}}', speed: 1.5 },
      { source: '{{c}}', trim_end_seconds: 9 },
    ]);
  });

  it('move down swaps a clip with its successor; the arrows are disabled at the ends', () => {
    const { onUpdate } = renderForm(concatData([
      { source: '{{a}}' },
      { source: '{{b}}' },
      { source: '{{c}}' },
    ]));

    expect((screen.getAllByLabelText('media.moveClipUp')[0] as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getAllByLabelText('media.moveClipDown')[2] as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getAllByLabelText('media.moveClipDown')[0]);

    expect(updatedInputs(onUpdate)).toEqual([
      { source: '{{b}}' },
      { source: '{{a}}' },
      { source: '{{c}}' },
    ]);
  });
});

describe('MediaParametersForm - literal FileRef chip', () => {
  const clip = { _type: 'file', path: '1/general/files/x_clip.mp4', name: 'clip.mp4', mimeType: 'video/mp4', size: 9 };
  const logo = { _type: 'file', path: '1/general/files/l_logo.png', name: 'logo.png', mimeType: 'image/png', size: 3 };

  it('a literal FileRef in inputs[].source renders as a named chip, and removing it clears the source (not the whole item)', () => {
    const { onUpdate } = renderForm(concatData([
      { source: clip, speed: 1.5 },
      { source: '{{b}}' },
    ]));

    expect(screen.getByText('clip.mp4')).toBeTruthy();
    expect(screen.getByTitle('media.removeLiteralFile')).toBeTruthy();

    fireEvent.click(screen.getByTitle('media.removeLiteralFile'));

    expect(updatedInputs(onUpdate)).toEqual([
      { source: '', speed: 1.5 },
      { source: '{{b}}' },
    ]);
  });

  it('a literal FileRef in the overlay image renders as a chip and removal empties the image param', () => {
    const { onUpdate } = renderForm({ mediaOperation: 'overlay', mediaParams: { video: '{{v}}', image: logo } } as any);

    expect(screen.getByText('logo.png')).toBeTruthy();

    fireEvent.click(screen.getByTitle('media.removeLiteralFile'));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    expect(onUpdate.mock.calls[0][0].mediaParams.image).toBe('');
  });
});
