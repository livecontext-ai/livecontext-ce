/**
 * @vitest-environment jsdom
 *
 * One rule, at the prop boundary: the modal feeds the format control the state it will SAVE, never
 * the `interfaceData` prop it was opened with.
 *
 * This has to be asserted on the prop rather than on rendered output. InterfaceFormatSelect is only
 * semi-controlled (it ignores an echoed `value` so it can't rewrite the draft mid-typing), so with
 * the wrong source the UI still LOOKS right and every behavioural test passes. It only breaks the
 * day `interfaceData` refreshes while the modal is open: the control keeps displaying the picked
 * format while the modal saves the stale prop. The behavioural suite lives in
 * CreateInterfaceModal.formatControl.test.tsx.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import * as React from 'react';

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: { updateInterface: vi.fn().mockResolvedValue({}), createInterface: vi.fn().mockResolvedValue({}) },
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: () => <textarea data-testid="editor-stub" />,
}));
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: () => <div data-testid="thumb-stub" />,
}));

// Capture what the modal hands the control, and expose its onChange so the test can drive it.
const formatSelectProps: { value?: string | null; onChange?: (f: string | null) => void }[] = [];
vi.mock('@/components/interfaces/InterfaceFormatSelect', () => ({
  InterfaceFormatSelect: (props: { value?: string | null; onChange?: (f: string | null) => void }) => {
    formatSelectProps.push(props);
    return <div data-testid="format-select-stub" />;
  },
}));

import { CreateInterfaceModal } from '../CreateInterfaceModal';

const BASE = { id: 'iface-1', name: 'Card', htmlTemplate: '<div>x</div>', format: 'vertical' };

describe('CreateInterfaceModal - format value source', () => {
  beforeEach(() => {
    formatSelectProps.length = 0;
  });
  afterEach(cleanup);

  it('passes the interface\'s stored format on open', () => {
    render(
      <CreateInterfaceModal
        onClose={() => undefined}
        onInterfaceCreated={() => undefined}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        interfaceData={BASE as any}
      />,
    );

    expect(formatSelectProps.at(-1)?.value).toBe('vertical');
  });

  it('passes back the state it will save once the format changes, not the original prop', () => {
    render(
      <CreateInterfaceModal
        onClose={() => undefined}
        onInterfaceCreated={() => undefined}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        interfaceData={BASE as any}
      />,
    );

    act(() => formatSelectProps.at(-1)?.onChange?.('square'));

    // With value={interfaceData?.format} this would still read 'vertical' while 'square' is saved.
    expect(formatSelectProps.at(-1)?.value).toBe('square');
  });

  it('passes null (Auto) back, never coalescing it to the prop or a preset', () => {
    render(
      <CreateInterfaceModal
        onClose={() => undefined}
        onInterfaceCreated={() => undefined}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        interfaceData={BASE as any}
      />,
    );

    act(() => formatSelectProps.at(-1)?.onChange?.(null));

    expect(formatSelectProps.at(-1)?.value).toBeNull();
  });
});
