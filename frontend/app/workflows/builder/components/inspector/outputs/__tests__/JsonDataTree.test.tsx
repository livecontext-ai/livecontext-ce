// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="spinner" />,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  // The REAL predicate's shape, not a looser one. Mocking it as "_type === 'file'"
  // routed an INCOMPLETE ref to the file card in tests while production sends it to
  // the generic rows, which is precisely where the marker and the rows disagreed.
  isFileRef: (value: unknown) => {
    if (!value || typeof value !== 'object') return false;
    const c = value as Record<string, unknown>;
    // BOTH branches of the real predicate. Implementing only the canonical one
    // would send the flat DB format down the generic path here and down the file
    // path in production, which is the class of divergence this mock exists to stop.
    const hasPathOrKey = typeof c.path === 'string' || typeof c.key === 'string';
    if (
      c._type === 'file' &&
      hasPathOrKey &&
      typeof c.name === 'string' &&
      typeof c.mimeType === 'string' &&
      typeof c.size === 'number'
    ) {
      return true;
    }
    return (
      typeof c.file_url === 'string' &&
      typeof c.file_name === 'string' &&
      typeof c.content_type === 'string' &&
      typeof c.file_size === 'number' &&
      !('_status' in c)
    );
  },
  normalizeFileRef: (value: Record<string, unknown>) =>
    value && value.file_url
      ? { _type: 'file', path: value.file_url, name: value.file_name, mimeType: 'application/pdf', size: 10 }
      : value,
  getFilePath: (value: Record<string, unknown>) => value.path,
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));

import { JsonValueTree, PrimitiveValue } from '../JsonDataTree';
import { LONG_STRING_CHARS } from '../runValueUtils';

function Tree({ data, labelForKey }: { data: unknown; labelForKey?: (k: string) => string }) {
  const [expanded, setExpanded] = React.useState<Set<string>>(new Set());
  return (
    <JsonValueTree
      data={data}
      path={[]}
      expandedPaths={expanded}
      onToggleExpand={(p) =>
        setExpanded((prev) => {
          const next = new Set(prev);
          if (next.has(p)) next.delete(p);
          else next.add(p);
          return next;
        })
      }
      labelForKey={labelForKey}
    />
  );
}

describe('PrimitiveValue', () => {
  it('calls out a value the engine failed to resolve instead of showing it as data', () => {
    render(<PrimitiveValue value="INVALID_TEMPLATE: nope" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
    expect(screen.getByTestId('run-value-unresolved').textContent).toContain('INVALID_TEMPLATE');
  });

  it('leaves an ordinary string alone', () => {
    render(<PrimitiveValue value="https://example.com" />);
    expect(screen.queryByTestId('run-value-unresolved-badge')).toBeNull();
  });

  it('offers a JSON string as an expandable tree rather than one escaped line', () => {
    render(<PrimitiveValue value='{"status":"ok","items":[1,2]}' />);
    const toggle = screen.getByTestId('run-value-json-toggle');
    expect(toggle).toBeTruthy();

    fireEvent.click(toggle);
    expect(screen.getByText('status')).toBeTruthy();
    expect(screen.getByText('"ok"')).toBeTruthy();
  });

  it('shows a configured expression as the value it is, with no JSON view and no alarm', () => {
    // Several nodes echo their expression on purpose (FilterNode reports its
    // "input" that way); badging it as unresolved would cry wolf.
    render(<PrimitiveValue value="{{core:x.output}}" />);
    expect(screen.queryByTestId('run-value-json-toggle')).toBeNull();
    expect(screen.queryByTestId('run-value-unresolved-badge')).toBeNull();
  });

  it('calls out the engine variable-not-found marker, which IS a failure', () => {
    render(<PrimitiveValue value="{{__UNRESOLVED__:mcp:step.output.url}}" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
  });

  it('collapses a long string by HEIGHT and keeps every character of it', () => {
    // It used to be CUT: the first LONG_STRING_CHARS characters plus an ellipsis,
    // with the rest absent from the document. A resolved parameter is exactly the
    // thing a reader opened the panel to read in full - and a cut value also
    // defeats find-in-page, select-all and copy, which is how a long value gets
    // out of the panel. Bounded box, whole text.
    const long = 'x'.repeat(LONG_STRING_CHARS + 50);
    render(<PrimitiveValue value={long} />);
    const toggle = screen.getByTestId('run-value-show-more');
    const text = screen.getByTestId('run-value-string');

    expect(text.textContent, 'the whole value is in the document while collapsed').toContain(long);
    expect(text.dataset.collapsed).toBe('true');
    expect(text.parentElement?.className).toContain('overflow-hidden');

    fireEvent.click(toggle);
    expect(screen.getByTestId('run-value-string').dataset.collapsed).toBe('false');
    expect(text.parentElement?.className).not.toContain('overflow-hidden');
  });

  it('shows short strings whole, with no expand control', () => {
    render(<PrimitiveValue value="short" />);
    expect(screen.queryByTestId('run-value-show-more')).toBeNull();
  });
});

describe('JsonValueTree', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('relabels TOP-LEVEL keys only, so a nested key still matches its drag path', () => {
    render(
      <Tree
        data={{ duration: 1000, nested: { duration: 5 } }}
        labelForKey={(key) => (key === 'duration' ? 'Duration (ms)' : key)}
      />,
    );
    expect(screen.getByText('Duration (ms)')).toBeTruthy();

    fireEvent.click(screen.getByText('nested'));
    // The nested key keeps its raw name.
    expect(screen.getByText('duration')).toBeTruthy();
  });

  it('copies a row value as text, not as a quoted JSON string', async () => {
    render(<Tree data={{ url: 'https://example.com' }} />);
    const copyButtons = screen.getAllByRole('button', { name: 'copyValue' });
    fireEvent.click(copyButtons[0]);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('https://example.com');
  });

  it('copies an object row as pretty JSON', () => {
    render(<Tree data={{ payload: { a: 1 } }} />);
    const copyButtons = screen.getAllByRole('button', { name: 'copyValue' });
    fireEvent.click(copyButtons[0]);
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('{\n  "a": 1\n}');
  });

});

describe('JsonValueTree - file references', () => {
  const FILE = { _type: 'file', path: 'tenant/a.pdf', name: 'a.pdf', mimeType: 'application/pdf', size: 1024 };

  it('renders a file VALUE with its view and download actions instead of a plain object', () => {
    render(<Tree data={{ report: FILE }} />);
    expect(screen.getByRole('button', { name: /viewFile/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /downloadFile/i })).toBeTruthy();
  });

  it('expands a file value into the properties a downstream node reads', () => {
    render(<Tree data={{ report: FILE }} />);
    fireEvent.click(screen.getByText('report'));
    expect(screen.getByText('path')).toBeTruthy();
    expect(screen.getByText('mimeType')).toBeTruthy();
    expect(screen.getByText('"tenant/a.pdf"')).toBeTruthy();
  });

  it('renders a payload that IS a file reference, with no key to hang it on', () => {
    render(<Tree data={FILE} />);
    expect(screen.getByText('file')).toBeTruthy();
    expect(screen.getByRole('button', { name: /downloadFile/i })).toBeTruthy();
  });

  it('puts a preview card above the fields of a flat file-carrying step output', () => {
    render(
      <Tree
        data={{ _status: 'COMPLETED', file_url: '/files/a.pdf', file_name: 'a.pdf', extra: 1 }}
      />,
    );
    // The card names the file, and the ordinary fields are still listed below it.
    expect(screen.getAllByText('a.pdf').length).toBeGreaterThan(0);
    expect(screen.getByText('extra')).toBeTruthy();
  });
});

describe('JsonValueTree - empty payloads', () => {
  it('renders an empty object as itself', () => {
    const { container } = render(<Tree data={{}} />);
    expect(container.textContent).toContain('{}');
  });

  it('renders an empty array as itself', () => {
    const { container } = render(<Tree data={[]} />);
    expect(container.textContent).toContain('[]');
  });

  it('renders a null payload as null rather than as nothing', () => {
    const { container } = render(<Tree data={null} />);
    expect(container.textContent).toContain('null');
  });
});

describe('PrimitiveValue - the other unresolved kinds', () => {
  it('calls out a value that IS the stringified object', () => {
    render(<PrimitiveValue value="[object Object]" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
    expect(screen.getByTestId('run-value-unresolved').textContent).toContain('[object Object]');
  });

  it('badges a value that CONCATENATED an object into text', () => {
    // Characterization of the long-standing `includes` matcher, pinned because a
    // round of this work briefly narrowed it to equality. The shape a run actually
    // produces is a set assignment whose expression resolved to a map; rendering
    // it as ordinary text is how the reader ends up debugging the wrong node.
    render(<PrimitiveValue value="Owner: [object Object]" />);
    expect(screen.getByTestId('run-value-unresolved-badge')).toBeTruthy();
  });

  it('bounds a huge broken template the same way, without losing any of it', () => {
    const body = 'x'.repeat(LONG_STRING_CHARS + 20);
    render(<PrimitiveValue value={`INVALID_TEMPLATE: ${body}`} />);
    const text = screen.getByTestId('run-value-unresolved');

    expect(screen.getByTestId('run-value-show-more')).toBeTruthy();
    expect(text.textContent, 'a broken template is read to diagnose it, so all of it stays').toContain(body);
    expect(text.parentElement?.className).toContain('overflow-hidden');
  });
});

describe('the collapse control costs the value no text', () => {
  const LONG = 'x'.repeat(LONG_STRING_CHARS + 50);

  it('is in flow, so it covers no text', () => {
    // It used to float over the value's right edge. That was the right trade while
    // it was hidden until hover - an in-flow control that is usually invisible
    // still reserves its box, so EVERY row paid for it. It is no longer that
    // shape: it is rendered only when the box is measurably hiding something, so
    // in flow it costs nothing to the rows that do not have one, and it overlaps
    // no text on the rows that do.
    render(<PrimitiveValue value={LONG} />);
    expect(screen.getByTestId('run-value-show-more').className).not.toContain('absolute');
  });

  it('sits on its OWN line, clear of the corner the copy button owns', () => {
    // The row's copy button is pinned at the top-right and revealed on hover. A
    // control at the end of the value's line lands underneath it as soon as the
    // value is expanded: the copy button paints on top and takes the click, so
    // the value can no longer be collapsed with the mouse. `basis-full` makes the
    // control a line rather than a neighbour, below the value either way.
    render(<PrimitiveValue value={LONG} />);
    const line = screen.getByTestId('run-value-control-line');
    expect(line.className).toContain('basis-full');
    expect(line.className).toContain('justify-end');
    expect(line.contains(screen.getByTestId('run-value-show-more'))).toBe(true);
  });

  it.each([
    ['a plain string', LONG],
    ['an unresolved template', `INVALID_TEMPLATE: ${LONG}`],
  ])('gives %s a container that WRAPS, without which basis-full is not a line', (_case, value) => {
    // Half the mechanism lives on the parent: `basis-full` in a non-wrapping flex
    // container is just a base size that shrinks, and the chevron slides back onto
    // the value's line and under the copy button - silently, with every other
    // assertion in this file still green. Both branches render in the same rows
    // under the same copy button, so both need the assertion.
    render(<PrimitiveValue value={value} />);
    const line = screen.getByTestId('run-value-control-line');
    expect(line.parentElement!.className).toContain('flex-wrap');
  });

  it('stays on its own line once expanded, where the control has to be reachable', () => {
    render(<PrimitiveValue value={LONG} />);
    fireEvent.click(screen.getByTestId('run-value-show-more'));
    expect(screen.getByTestId('run-value-control-line').className).toContain('basis-full');
  });

  it('is never hidden behind a hover', () => {
    // A bounded value with no visible way to continue it reads as a truncated one,
    // and was reported as one.
    render(<PrimitiveValue value={LONG} />);
    const toggle = screen.getByTestId('run-value-show-more');
    expect(toggle.className).not.toContain('opacity-0');
    expect(toggle.className).not.toContain('group-hover/row:opacity-100');
  });
});

/**
 * The invariant, stated once over a matrix instead of case by case.
 *
 * Three markers used to be computed by three separate rules in this one component,
 * and its tests only ever read a collapsed marker or counted rows, never both, which
 * is how the rules could disagree for as long as they did.
 *
 * Driven through the embedded-string entry point because it is the only one that
 * always renders a marker AND a toggle for the same value. What it expands is the
 * ordinary tree, so JsonNode, JsonValueTree and the file view are all exercised here.
 */
describe('a {n} marker always equals the rows expanding it renders', () => {
  const SHAPES: Array<[string, Record<string, unknown> | unknown[]]> = [
    ['plain object', { a: 1, b: 2 }],
    ['object carrying a discriminator', { _type: 'media', url: 'https://x', title: 'y' }],
    ['object whose only key is the discriminator', { _type: 'media' }],
    ['array', [1, 2, 3]],
    ['array of objects with a discriminator', [{ _type: 'media', url: 'u' }]],
    ['complete file ref', {
      _type: 'file', path: '/a.png', name: 'a.png', mimeType: 'image/png', size: 10,
    }],
    ['complete file ref carrying an id', {
      _type: 'file', path: '/a.png', name: 'a.png', mimeType: 'image/png', size: 10, id: 'abc',
    }],
    ['incomplete file ref', { _type: 'file', path: '/a.png' }],
    ['incomplete file ref with a name', { _type: 'file', path: '/a.png', name: 'a.png' }],
    ['key-only canonical ref', {
      _type: 'file', key: 'k/a.png', name: 'a.png', mimeType: 'image/png', size: 10,
    }],
    ['flat DB file format', {
      file_url: '/f?key=k', file_name: 'a.png', content_type: 'image/png', file_size: 10,
    }],
    ['flat _status format', {
      _status: 'ok', file_url: '/f?key=k', file_name: 'a.png',
    }],
    ['empty object', {}],
    ['empty array', []],
  ];

  function markerOf(container: HTMLElement): number {
    const marker = Array.from(container.querySelectorAll('span'))
      .map((el) => el.textContent ?? '')
      .find((text) => /^[{[][0-9]+[}\]]$/.test(text));
    expect(marker, 'no {n} marker rendered').toBeTruthy();
    return Number(marker!.replace(/[^0-9]/g, ''));
  }

  function rowCount(container: HTMLElement): number {
    return container.querySelectorAll('[data-testid="json-row-key"]').length;
  }

  /**
   * A real value under a key. This is the path the reported symptom came from, and
   * the ONLY path that reads the markers at JsonNode and FileObjectNode: reverting
   * either of those to its buggy rule left the whole suite green until this existed.
   */
  it.each(SHAPES)('as a real value under a key: %s', (_label, shape) => {
    const { container } = render(<Tree data={{ v: shape }} />);

    const declared = markerOf(container);
    const before = rowCount(container);
    // A file reference is toggled by its card; every other row by its key.
    const fileToggle = screen.queryByTestId('json-file-toggle');
    fireEvent.click(fileToggle ?? screen.getByText('v'));

    expect(rowCount(container) - before, `marker said ${declared}`).toBe(declared);
  });

  it.each(SHAPES)('as an embedded JSON string: %s', (_label, shape) => {
    const { container } = render(<PrimitiveValue value={JSON.stringify(shape)} />);

    const declared = markerOf(container);
    fireEvent.click(screen.getByTestId('run-value-json-toggle'));
    // A recognised file reference opens onto its card, which is itself collapsed: the
    // second click is what "opened" means for that shape, and it is asserted rather
    // than assumed, because it is the one place the count is not one click away.
    const fileToggle = screen.queryByTestId('json-file-toggle');
    if (fileToggle) {
      expect(rowCount(container), 'a file card opens collapsed').toBe(0);
      fireEvent.click(fileToggle);
    }

    expect(rowCount(container), `marker said ${declared}`).toBe(declared);
  });
});

describe('embedded JSON renders as the structure it is', () => {
  it('shows an object marker rather than a format badge', () => {
    render(<PrimitiveValue value={JSON.stringify({ a: 1, b: 2 })} />);
    expect(screen.getByText('{2}')).toBeTruthy();
    expect(screen.queryByText('jsonBadge')).toBeNull();
  });

  it('shows an array marker for an embedded array', () => {
    render(<PrimitiveValue value={JSON.stringify([1, 2, 3])} />);
    expect(screen.getByText('[3]')).toBeTruthy();
  });

  it('counts an incomplete file ref as the plain object it is drawn as', () => {
    // {_type:'file', path} is NOT a recognised ref (no name / mimeType / size), so it
    // is drawn as ordinary rows. It used to be counted 1 and drawn as 2.
    render(<PrimitiveValue value={JSON.stringify({ _type: 'file', path: '/a.png' })} />);
    expect(screen.getByText('{2}')).toBeTruthy();
  });

  it('keeps _type visible, because on a malformed ref it is the informative part', () => {
    // Hiding it would take away the only thing on screen saying this was MEANT to be a
    // file, which is what a reader needs when a downstream fileRef parameter refuses
    // it. It would also make a user's own _type field invisible in a webhook body.
    render(<PrimitiveValue value={JSON.stringify({ _type: 'file', path: '/a.png', name: 'a.png' })} />);

    fireEvent.click(screen.getByTestId('run-value-json-toggle'));
    expect(screen.getByText('_type')).toBeTruthy();
    expect(screen.getByText('"file"')).toBeTruthy();
  });

  it('counts a RECOGNISED file ref as the four fields the file view draws', () => {
    // The file view renders a fixed four fields whatever else the ref carries, and
    // every file produced since the opaque-URL cutover carries an `id`. Counting the
    // raw keys made the string row say {5} above a file row saying {4}.
    render(
      <PrimitiveValue
        value={JSON.stringify({
          _type: 'file', path: '/a.png', name: 'a.png',
          mimeType: 'image/png', size: 10, id: 'abc',
        })}
      />,
    );
    expect(screen.getByText('{4}')).toBeTruthy();
  });
});

describe('an embedded JSON string is indistinguishable from a real object', () => {
  const PAYLOAD = { title: 'Twenty one miles #Shorts', description: 'a'.repeat(400), tags: ['a'] };

  it('shows the marker alone, with no raw-text preview beside it', () => {
    render(<PrimitiveValue value={JSON.stringify(PAYLOAD)} />);

    expect(screen.getByText('{3}')).toBeTruthy();
    // The whole difference used to be here: a one-line dump of the very structure
    // the marker offers to open, which no real object row has ever shown.
    expect(screen.queryByText(/Twenty one miles/)).toBeNull();
    expect(screen.queryByText(/^\{"title"/)).toBeNull();
  });

  it('is collapsed until asked, then shows the same tree a real object shows', () => {
    render(<PrimitiveValue value={JSON.stringify(PAYLOAD)} />);

    expect(screen.queryByText('title')).toBeNull();
    fireEvent.click(screen.getByTestId('run-value-json-toggle'));
    expect(screen.getByText('title')).toBeTruthy();
  });

  it('renders the marker with the same class an object row uses for its own', () => {
    // Not a screenshot test: the point is that the two markers cannot drift apart
    // in colour or font, which is what makes them read as the same kind of thing.
    render(<PrimitiveValue value={JSON.stringify(PAYLOAD)} />);
    const marker = screen.getByText('{3}');
    expect(marker.className).toContain('font-mono');
    expect(marker.className).toContain('text-orange-600');
    expect(marker.className).toContain('dark:text-orange-400');
  });
});
