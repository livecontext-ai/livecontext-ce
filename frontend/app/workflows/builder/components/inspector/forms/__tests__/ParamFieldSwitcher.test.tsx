// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

// Translation stub - surfaces the key as the value so we can assert button labels.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// ExpressionEditor pulls in heavy editor + portal logic. Stub it as a textarea so
// we can assert mode + value flow at the integration boundary.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: (props: any) => (
    <textarea
      data-testid="expr-editor"
      value={props.value ?? ''}
      onChange={(e) => props.onChange(e.target.value)}
      readOnly={props.readOnly}
    />
  ),
}));

// Radix Select needs jsdom hacks. Stub with a thin wrapper that preserves the
// controlled-value contract and forwards the data-testid we use to detect mode.
// Radix Select needs jsdom hacks. Mock with a thin wrapper that:
//   - forwards `value` and `onValueChange` via React context so SelectItem can fire it on click.
//   - exposes `data-select-item-value` on each item so tests can assert presence/absence.
// This lets us simulate a Radix selection event from a unit test without rendering the full Radix tree.
const SelectCtx = React.createContext<{ value?: string; onValueChange?: (v: string) => void }>({});
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange }: any) => (
    <SelectCtx.Provider value={{ value, onValueChange }}>
      <div data-testid="select-root" data-select-value={value ?? ''}>{children}</div>
    </SelectCtx.Provider>
  ),
  SelectContent: ({ children }: any) => <div>{children}</div>,
  SelectItem: ({ value, children }: any) => {
    const ctx = React.useContext(SelectCtx);
    return (
      <div
        data-select-item-value={value}
        data-select-item-selected={ctx.value === value ? 'true' : 'false'}
        onClick={() => ctx.onValueChange?.(value)}
      >
        {children}
      </div>
    );
  },
  SelectTrigger: ({ children, ...rest }: any) => <div {...rest}>{children}</div>,
  SelectValue: () => null,
}));

// GoogleDrivePickerField pulls in apiClient + the Google JS SDK. Stub it so we can assert that
// ParamFieldSwitcher ROUTES to the picker (and forwards the mimeType) without the SDK.
vi.mock('../GoogleDrivePickerField', () => ({
  GoogleDrivePickerField: (props: any) => (
    <div data-testid="drive-picker" data-mime={props.mimeType}>
      {props.value}
    </div>
  ),
}));

import { ParamFieldSwitcher } from '../ParamFieldSwitcher';

afterEach(() => cleanup());

function setup(overrides: Partial<React.ComponentProps<typeof ParamFieldSwitcher>> = {}) {
  const onChange = vi.fn();
  const props: React.ComponentProps<typeof ParamFieldSwitcher> = {
    paramName: 'model',
    paramType: 'string',
    defaultValue: null,
    allowedValues: null,
    value: '',
    onChange,
    isRequired: false,
    ...overrides,
  };
  render(<ParamFieldSwitcher {...props} />);
  return { onChange, props };
}

describe('ParamFieldSwitcher', () => {
  describe('mode selection', () => {
    it('renders Select when allowedValues is non-empty for a scalar type', () => {
      setup({
        allowedValues: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1'],
        defaultValue: 'gpt-4o',
      });

      expect(screen.queryByTestId('param-select-model')).not.toBeNull();
      expect(screen.queryByTestId('expr-editor')).toBeNull();
    });

    it('renders Input when only a scalar default is provided', () => {
      setup({ defaultValue: '1', paramType: 'number' });

      expect(screen.queryByTestId('param-input-model')).not.toBeNull();
      expect(screen.queryByTestId('expr-editor')).toBeNull();
    });

    it('renders ExpressionEditor when neither default nor allowedValues are provided', () => {
      setup({});

      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      expect(screen.queryByTestId('param-select-model')).toBeNull();
      expect(screen.queryByTestId('param-input-model')).toBeNull();
    });

    it('falls back to ExpressionEditor for non-scalar types regardless of allowedValues', () => {
      // The dropdown UX makes no sense for object/array params - even if a
      // misguided agent populated allowedValues on a complex param, we still
      // render the expression editor so users can wire upstream data.
      setup({ paramType: 'object', allowedValues: ['{}', '[]'] });

      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      expect(screen.queryByTestId('param-select-model')).toBeNull();
    });

    it('renders the Drive picker (and forwards the mimeType) when a google-drive picker hint is present', () => {
      setup({
        picker: { provider: 'google-drive', mimeType: 'application/vnd.google-apps.spreadsheet' },
      });

      const picker = screen.queryByTestId('drive-picker');
      expect(picker).not.toBeNull();
      expect(picker?.getAttribute('data-mime')).toBe('application/vnd.google-apps.spreadsheet');
      expect(screen.queryByTestId('expr-editor')).toBeNull();
    });

    it('picker hint loses to expression mode when the value is already an expression', () => {
      setup({
        picker: { provider: 'google-drive', mimeType: 'application/vnd.google-apps.document' },
        value: '{{trigger.fileId}}',
      });

      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      expect(screen.queryByTestId('drive-picker')).toBeNull();
    });

    it('ignores an unknown picker provider (renders the normal field)', () => {
      setup({ picker: { provider: 'dropbox', mimeType: 'x' } });

      expect(screen.queryByTestId('drive-picker')).toBeNull();
      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
    });

    it('switches from picker mode to the expression editor on demand', () => {
      setup({
        picker: { provider: 'google-drive', mimeType: 'application/vnd.google-apps.spreadsheet' },
      });
      expect(screen.queryByTestId('drive-picker')).not.toBeNull();

      fireEvent.click(screen.getByTestId('param-switch-expression-model'));

      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      expect(screen.queryByTestId('drive-picker')).toBeNull();
    });

    it('auto-routes to ExpressionEditor when the saved value contains an expression', () => {
      // A workflow loaded from DB with `{{trigger.body.model}}` must keep
      // expression mode even though the param has allowedValues - otherwise
      // we'd lose the user's wiring on every render.
      setup({
        allowedValues: ['gpt-4o', 'gpt-4o-mini'],
        value: '{{trigger.body.model}}',
      });

      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      expect(screen.queryByTestId('param-select-model')).toBeNull();
    });
  });

  describe('user toggles', () => {
    it('switching from Select to Expression mode keeps the current value as literal', () => {
      const { onChange } = setup({
        allowedValues: ['gpt-4o', 'gpt-4o-mini'],
        defaultValue: 'gpt-4o',
        value: 'gpt-4o-mini',
      });

      fireEvent.click(screen.getByTestId('param-switch-expression-model'));

      // After switching, the expression editor shows up - value is preserved.
      expect(screen.queryByTestId('expr-editor')).not.toBeNull();
      // No mutation happens on the user's value when switching to expression mode:
      // the value flows through; onChange only fires on actual edits.
      expect(onChange).not.toHaveBeenCalled();
    });

    it('switching back from Expression to Select keeps the value if it matches an allowed option', () => {
      // Render starts in expression mode by stubbing a literal that happens
      // to match an allowed value - clicking "use preset value" should not
      // clobber it.
      const { onChange } = setup({
        allowedValues: ['gpt-4o', 'gpt-4o-mini'],
        defaultValue: 'gpt-4o',
        value: 'gpt-4o',
      });

      // Force into expression mode first
      fireEvent.click(screen.getByTestId('param-switch-expression-model'));
      expect(screen.queryByTestId('expr-editor')).not.toBeNull();

      // Now switch back - value matches an allowedValue, so it's preserved.
      fireEvent.click(screen.getByTestId('param-switch-picker-model'));

      // No onChange call needed - value is already valid for the picker.
      expect(onChange).not.toHaveBeenCalled();
    });

    it('switching back from Expression to Picker resets to default when the literal does not match', () => {
      // Pre-fix bug scenario: user typed a free string in expression mode that
      // does not match any allowedValue. Going back to picker without resetting
      // would leave the Select showing nothing (broken UI), so we reset to default.
      const { onChange } = setup({
        allowedValues: ['gpt-4o', 'gpt-4o-mini'],
        defaultValue: 'gpt-4o',
        value: 'something-else',
      });

      fireEvent.click(screen.getByTestId('param-switch-expression-model'));
      fireEvent.click(screen.getByTestId('param-switch-picker-model'));

      expect(onChange).toHaveBeenCalledWith('gpt-4o');
    });
  });

  describe('respects readOnly', () => {
    it('hides toggles in run mode', () => {
      setup({
        allowedValues: ['gpt-4o'],
        defaultValue: 'gpt-4o',
        readOnly: true,
      });

      expect(screen.queryByTestId('param-switch-expression-model')).toBeNull();
    });
  });

  describe('(none) option for optional enum params', () => {
    it('renders a (none) SelectItem at the top when isRequired is false', () => {
      setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: false,
      });

      const noneItem = document.querySelector('[data-select-item-value="__none__"]');
      expect(noneItem).not.toBeNull();
    });

    it('does NOT render a (none) SelectItem when isRequired is true', () => {
      setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: true,
      });

      const noneItem = document.querySelector('[data-select-item-value="__none__"]');
      expect(noneItem).toBeNull();
    });

    it('renders (none) as selected by default when value is empty and isRequired is false', () => {
      // The whole UX bet: nothing-mapped is rendered as `(none)` selected, not as
      // an ambiguous placeholder. Reload of a saved-with-omitted-param node must
      // visually confirm the user's prior choice - `(none)`.
      setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: false,
        value: '',
      });

      const noneItem = document.querySelector('[data-select-item-value="__none__"]');
      expect(noneItem?.getAttribute('data-select-item-selected')).toBe('true');
    });

    it('clicking (none) writes empty string upstream so planHelpers strips the key', () => {
      // The omit contract end-to-end: __none__ sentinel maps to '' at the parent
      // level, which `convertParamExpressionsToInputs` strips → the param key is
      // omitted from the workflow plan → the API receives the request without it.
      const { onChange } = setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: false,
        value: 'Markdown',
      });

      const noneItem = document.querySelector('[data-select-item-value="__none__"]') as HTMLElement;
      expect(noneItem).not.toBeNull();
      fireEvent.click(noneItem);

      expect(onChange).toHaveBeenCalledWith('');
    });

    it('selecting an allowedValue forwards the value verbatim (sentinel only intercepts __none__)', () => {
      const { onChange } = setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: false,
        value: '',
      });

      const htmlItem = document.querySelector('[data-select-item-value="HTML"]') as HTMLElement;
      expect(htmlItem).not.toBeNull();
      fireEvent.click(htmlItem);

      expect(onChange).toHaveBeenCalledWith('HTML');
    });

    it('renders the explicit allowedValue as selected when value matches one', () => {
      setup({
        allowedValues: ['MarkdownV2', 'HTML', 'Markdown'],
        isRequired: false,
        value: 'HTML',
      });

      const htmlItem = document.querySelector('[data-select-item-value="HTML"]');
      const noneItem = document.querySelector('[data-select-item-value="__none__"]');
      expect(htmlItem?.getAttribute('data-select-item-selected')).toBe('true');
      expect(noneItem?.getAttribute('data-select-item-selected')).toBe('false');
    });
  });
});
