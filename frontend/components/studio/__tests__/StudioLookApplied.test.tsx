// @vitest-environment jsdom
/**
 * Whether the studio look is actually APPLIED, which is the one thing about a theme that code can
 * get wrong.
 *
 * <p>The colours themselves are CSS and are not worth asserting: a test that re-states a hex value
 * only pins the value to itself. What a test can prove is that the class carrying them lands on
 * every layout the studio has, lands on the menus that render OUTSIDE those layouts, and lands
 * nowhere at all when the reader has not chosen it.
 *
 * <p>The portal half is the half that was wrong: a popover renders on the document rather than
 * inside the element that opened it, so it reads the app's tokens however deeply nested its trigger
 * was, and every menu came back as a bright panel over a dark page.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { StudioLookProvider } from '@/hooks/useStudioLook';
import { StudioPopoverContent } from '../StudioPopoverContent';
import { Popover, PopoverTrigger } from '@/components/ui/popover';
import { StudioSelectContent } from '../StudioSelectContent';
import { StudioDialogContent } from '../StudioDialogContent';
import { Dialog } from '@/components/ui/dialog';
import { Select, SelectItem, SelectTrigger } from '@/components/ui/select';

const STORAGE_KEY = 'lc.studio.look';

// The surface drags in the whole conversation/generation stack; none of it decides a class name.
vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: undefined, isError: false, isLoading: false }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], availability: 'empty', isLoading: false }),
}));
vi.mock('@/hooks/useStudioTurn', () => ({
  useStudioTurn: () => ({ isRunning: false, error: null, run: vi.fn(), clearError: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/components/studio/StudioComposer', () => ({
  StudioComposer: ({ lookSwitch }: { lookSwitch?: React.ReactNode }) => (
    <div data-testid="composer">{lookSwitch}</div>
  ),
}));
vi.mock('@/components/studio/StudioApps', () => ({ StudioApps: () => <div /> }));
vi.mock('@/components/generation/GenerationHistoryList', () => ({
  GenerationHistoryList: () => <div />,
}));
vi.mock('@/components/studio/StudioDynamicTitle', () => ({ StudioDynamicTitle: () => <div /> }));
vi.mock('@/components/chat/HomeModeSwitch', () => ({ HomeModeSwitch: () => <div /> }));
// Which layout the surface draws is decided by this, so it is the control this suite turns.
const narrow = vi.hoisted(() => ({ value: false }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => narrow.value }));

import { StudioSurface } from '../StudioSurface';

/** The class the treatment travels under. Asserted by name because that IS the contract. */
const LOOK = 'studio-darkroom';

function surfaceRoot(container: HTMLElement): HTMLElement {
  return container.firstElementChild as HTMLElement;
}

beforeEach(() => {
  window.localStorage.clear();
  narrow.value = false;
});
afterEach(cleanup);

describe('the studio look on the surface', () => {
  it('is absent on the app look, so a reader who never chose sees no change', () => {
    const { container } = render(<StudioSurface conversationId={null} />);
    expect(surfaceRoot(container).className).not.toContain(LOOK);
  });

  it('lands on the empty desktop layout', () => {
    window.localStorage.setItem(STORAGE_KEY, 'darkroom');
    const { container } = render(<StudioSurface conversationId={null} />);
    expect(surfaceRoot(container).className).toContain(LOOK);
  });

  it('lands on the narrow layout, which is a different tree', () => {
    // Three layouts, three roots: a class applied to one of them is a treatment that vanishes when
    // the reader turns their phone.
    narrow.value = true;
    window.localStorage.setItem(STORAGE_KEY, 'darkroom');
    const { container } = render(<StudioSurface conversationId={null} />);
    expect(surfaceRoot(container).className).toContain(LOOK);
  });

  it('lands on the thread layout', () => {
    window.localStorage.setItem(STORAGE_KEY, 'darkroom');
    // A conversation id is what sends the surface down its third branch.
    const { container } = render(<StudioSurface conversationId="c-1" />);
    expect(surfaceRoot(container).className).toContain(LOOK);
  });

  it('puts the SWITCH in every layout, not merely something', () => {
    // This rendered one layout and asserted `firstElementChild` was not null, against a composer
    // mocked down to `<div>{lookSwitch}</div>`: any element at all satisfied it, and the other two
    // layouts - the ones the claim is about - were never rendered for this assertion.
    //
    // The switch is icon-only, so its accessible NAME is the only thing identifying it, and the
    // name is what a reader who cannot see the icon has. Asserting the name also catches the
    // failure where the control renders with nothing to announce.
    //
    // The name reads as a key path because this suite stubs next-intl (it is about class names,
    // not words); the real dictionary is exercised by StudioLook.test.tsx, which renders the
    // switch under the actual messages.
    for (const conversationId of [null, 'c-1']) {
      for (const isNarrow of [false, true]) {
        narrow.value = isNarrow;
        cleanup();
        render(<StudioSurface conversationId={conversationId} />);

        const composer = screen.getByTestId('composer');
        const found = within(composer).getByRole('button', { name: /look\.to/ });
        expect(found, `layout conversationId=${conversationId} narrow=${isNarrow}`).toBeVisible();
      }
    }
    narrow.value = false;
  });
});

describe('the studio look on a menu, which renders outside the surface', () => {
  function openMenu(look: 'app' | 'darkroom') {
    render(
      <StudioLookProvider value={look}>
        <Popover open>
          <PopoverTrigger>open</PopoverTrigger>
          <StudioPopoverContent className="menu-own-class">
            <div data-testid="menu-item" />
          </StudioPopoverContent>
        </Popover>
      </StudioLookProvider>,
    );
    return screen.getByTestId('menu-item').parentElement as HTMLElement;
  }

  it('carries the look onto a portalled menu', () => {
    expect(openMenu('darkroom').className).toContain(LOOK);
  });

  it('adds nothing on the app look', () => {
    expect(openMenu('app').className).not.toContain(LOOK);
  });

  it('keeps the menu its own classes', () => {
    expect(openMenu('darkroom').className).toContain('menu-own-class');
  });

  it('is the app look for a menu rendered with no provider at all', () => {
    // A studio component mounted outside the surface (a test, a preview) is exactly what it was.
    render(
      <Popover open>
        <PopoverTrigger>open</PopoverTrigger>
        <StudioPopoverContent><div data-testid="loose-item" /></StudioPopoverContent>
      </Popover>,
    );
    const content = screen.getByTestId('loose-item').parentElement as HTMLElement;
    expect(content.className).not.toContain(LOOK);
  });
});

/**
 * The OTHER portal, which the popover fix did not reach.
 *
 * <p>A select list is portalled exactly like a popover, and the studio opens one: the credential
 * section's "which of my keys", inside the model picker's menu. So on the darkroom ground it was a
 * bright panel INSIDE a correctly dark menu, which reads worse than the original bug did.
 *
 * <p>The section that renders it is shared with the workflow inspector, so the important half is
 * the last test here: off a studio surface there is no provider, the class is empty, and the
 * inspector renders what it always rendered.
 */
describe('the studio look on a select list, which is portalled too', () => {
  beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });

  function openList(look?: 'app' | 'darkroom') {
    const list = (
      <Select open>
        <SelectTrigger>pick</SelectTrigger>
        <StudioSelectContent className="list-own-class">
          <SelectItem value="1"><span data-testid="option" /></SelectItem>
        </StudioSelectContent>
      </Select>
    );
    render(look ? <StudioLookProvider value={look}>{list}</StudioLookProvider> : list);
    // The item's nearest ancestor carrying classes is the viewport; the class lands on the
    // content element above it, so walk up until something claims it.
    let node = screen.getByTestId('option').parentElement as HTMLElement;
    while (node && !node.className.includes('list-own-class') && node.parentElement) {
      node = node.parentElement;
    }
    return node;
  }

  it('carries the look onto a portalled select list', () => {
    expect(openList('darkroom').className).toContain(LOOK);
  });

  it('adds nothing on the app look', () => {
    expect(openList('app').className).not.toContain(LOOK);
  });

  it('is the app look with no provider at all, which is the workflow inspector', () => {
    expect(openList().className).not.toContain(LOOK);
  });
});

/**
 * The THIRD portal, which the first two fixes called "the one other place it happens".
 *
 * <p>It was not. The credential wizard is a Radix Dialog in a portal of its own, reached from the
 * studio's model picker by pressing "add my own key": on the darkroom ground that opened a bright,
 * application-themed panel over a dark page, out of a menu that was correctly dark.
 *
 * <p>The wizard is mounted by the workflow inspector and the settings pages too, so the last test
 * is the one that matters most: off a studio surface there is no provider, the class is empty, and
 * those surfaces render exactly what they rendered before.
 */
describe('the studio look on a dialog, which is a portal of its own', () => {
  function openDialog(look?: 'app' | 'darkroom') {
    const dialog = (
      <Dialog open>
        <StudioDialogContent className="dialog-own-class">
          <div data-testid="dialog-body" />
        </StudioDialogContent>
      </Dialog>
    );
    render(look ? <StudioLookProvider value={look}>{dialog}</StudioLookProvider> : dialog);
    let node = screen.getByTestId('dialog-body').parentElement as HTMLElement;
    while (node && !node.className.includes('dialog-own-class') && node.parentElement) {
      node = node.parentElement;
    }
    return node;
  }

  it('carries the look onto a portalled dialog', () => {
    expect(openDialog('darkroom').className).toContain(LOOK);
  });

  it('adds nothing on the app look', () => {
    expect(openDialog('app').className).not.toContain(LOOK);
  });

  it('is the app look with no provider at all, which is settings and the inspector', () => {
    expect(openDialog().className).not.toContain(LOOK);
  });
});
