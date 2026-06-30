// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The Drive picker field is tested on its own; here we only assert ExpressionField
// routes to it. Mock it to a recognizable marker carrying its props.
vi.mock('../forms/GoogleDrivePickerField', () => ({
  GoogleDrivePickerField: ({
    paramName,
    mimeType,
    onUseExpression,
  }: {
    paramName: string;
    mimeType: string;
    onUseExpression?: () => void;
  }) => (
    <div data-testid={`mock-drive-picker-${paramName}`}>
      {mimeType}
      {onUseExpression && (
        <button data-testid={`mock-use-expr-${paramName}`} onClick={onUseExpression}>
          expr
        </button>
      )}
    </div>
  ),
}));

// ExpressionEditor is heavy (CodeMirror); stub it to a textarea marker.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value }: { value: string }) => (
    <div data-testid="mock-expression-editor">{value}</div>
  ),
}));

import { ExpressionField } from '../ExpressionField';

const SHEET_MIME = 'application/vnd.google-apps.spreadsheet';
const base = {
  label: 'spreadsheetId',
  nodeId: 'node-1',
  fieldName: 'param-spreadsheetId',
  typeHint: 'string',
  onChange: vi.fn(),
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ExpressionField - Drive picker mode', () => {
  it('renders the Drive picker when the param carries a google-drive picker hint', () => {
    render(
      <ExpressionField
        {...base}
        value=""
        picker={{ provider: 'google-drive', mimeType: SHEET_MIME }}
      />,
    );
    const picker = screen.getByTestId('mock-drive-picker-spreadsheetId');
    expect(picker).not.toBeNull();
    expect(picker.textContent).toContain(SHEET_MIME); // mimeType threaded through
    // not the bare expression editor
    expect(screen.queryByTestId('mock-expression-editor')).toBeNull();
  });

  it('ignores a non-google-drive picker provider (no Drive picker)', () => {
    render(
      <ExpressionField
        {...base}
        value=""
        picker={{ provider: 'dropbox', mimeType: 'x' }}
      />,
    );
    expect(screen.queryByTestId('mock-drive-picker-spreadsheetId')).toBeNull();
  });

  it('routes to expression mode (not picker) when the value is already an expression, and offers a switch-back-to-picker toggle', () => {
    render(
      <ExpressionField
        {...base}
        value="{{mcp:create.output.spreadsheetId}}"
        picker={{ provider: 'google-drive', mimeType: SHEET_MIME }}
      />,
    );
    expect(screen.getByTestId('mock-expression-editor')).not.toBeNull();
    expect(screen.queryByTestId('mock-drive-picker-spreadsheetId')).toBeNull();
    // the "use picker" switch-back affordance is present for a picker param
    expect(screen.queryByTestId('expr-switch-picker-param-spreadsheetId')).not.toBeNull();
  });

  it('switches from picker mode to the expression editor on demand', () => {
    render(
      <ExpressionField
        {...base}
        value=""
        picker={{ provider: 'google-drive', mimeType: SHEET_MIME }}
      />,
    );
    expect(screen.getByTestId('mock-drive-picker-spreadsheetId')).not.toBeNull();
    // the "Use expression" action is rendered inline by the picker field (same row as "Pick from Drive")
    fireEvent.click(screen.getByTestId('mock-use-expr-spreadsheetId'));
    expect(screen.getByTestId('mock-expression-editor')).not.toBeNull();
    expect(screen.queryByTestId('mock-drive-picker-spreadsheetId')).toBeNull();
  });

  it('does not render the Drive picker for a non-scalar (object) param even with a hint', () => {
    render(
      <ExpressionField
        {...base}
        typeHint="object"
        value=""
        picker={{ provider: 'google-drive', mimeType: SHEET_MIME }}
      />,
    );
    expect(screen.queryByTestId('mock-drive-picker-spreadsheetId')).toBeNull();
    expect(screen.getByTestId('mock-expression-editor')).not.toBeNull();
  });
});
