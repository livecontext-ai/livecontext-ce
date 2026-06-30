// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { post: postMock } }));

import { GoogleDrivePickerField } from '../GoogleDrivePickerField';

/** Installs a minimal window.gapi + window.google.picker, capturing the picker callback. */
function installGoogleSDK(captureCallback: (cb: (data: any) => void) => void) {
  (window as any).gapi = {
    load: (_name: string, opts: { callback: () => void }) => opts.callback(),
  };
  (window as any).google = {
    picker: {
      DocsView: class {
        setMimeTypes() {
          return this;
        }
      },
      PickerBuilder: class {
        setOAuthToken() {
          return this;
        }
        setDeveloperKey() {
          return this;
        }
        addView() {
          return this;
        }
        setCallback(cb: (data: any) => void) {
          captureCallback(cb);
          return this;
        }
        build() {
          return { setVisible: () => undefined };
        }
      },
      Response: { ACTION: 'action', DOCUMENTS: 'documents' },
      Action: { PICKED: 'picked' },
      Document: { ID: 'id' },
    },
  };
}

beforeEach(() => {
  postMock.mockReset();
  // Pre-inject the gapi script so loadScript() resolves immediately (jsdom never fires onload).
  const s = document.createElement('script');
  s.src = 'https://apis.google.com/js/api.js';
  document.head.appendChild(s);
});

afterEach(() => {
  cleanup();
  delete (window as any).gapi;
  delete (window as any).google;
  delete process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY;
  delete process.env.NEXT_PUBLIC_GOOGLE_API_KEY;
});

describe('GoogleDrivePickerField', () => {
  it('renders the value input and a Pick-from-Drive button', () => {
    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value="existing-id"
        onChange={vi.fn()}
        mimeType="application/vnd.google-apps.spreadsheet"
      />,
    );
    expect((screen.getByTestId('param-picker-value-spreadsheetId') as HTMLInputElement).value).toBe('existing-id');
    expect(screen.queryByTestId('param-pick-spreadsheetId')).not.toBeNull();
  });

  it('renders a "Use expression" action beside the picker when onUseExpression is provided, and calls it on click', () => {
    const onUseExpression = vi.fn();
    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value=""
        onChange={vi.fn()}
        mimeType="application/vnd.google-apps.spreadsheet"
        onUseExpression={onUseExpression}
      />,
    );
    const expr = screen.getByTestId('param-use-expression-spreadsheetId');
    expect(expr).not.toBeNull();
    fireEvent.click(expr);
    expect(onUseExpression).toHaveBeenCalledTimes(1);
  });

  it('omits the "Use expression" action when onUseExpression is not provided', () => {
    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value=""
        onChange={vi.fn()}
        mimeType="application/vnd.google-apps.spreadsheet"
      />,
    );
    expect(screen.queryByTestId('param-use-expression-spreadsheetId')).toBeNull();
    expect(screen.queryByTestId('param-pick-spreadsheetId')).not.toBeNull();
  });

  it('fetches a token for the integration derived from the mimeType, then a PICKED file sets the value', async () => {
    process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY = 'browser-key';
    postMock.mockResolvedValue({ access_token: 'ya29.fresh' });
    let pickerCallback: ((data: any) => void) | undefined;
    installGoogleSDK((cb) => {
      pickerCallback = cb;
    });

    const onChange = vi.fn();
    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value=""
        onChange={onChange}
        mimeType="application/vnd.google-apps.spreadsheet"
      />,
    );

    fireEvent.click(screen.getByTestId('param-pick-spreadsheetId'));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/credentials/oauth2/picker-token', {
        integration: 'Google Sheets',
        credential_name: 'googlesheets',
      }),
    );
    await waitFor(() => expect(typeof pickerCallback).toBe('function'));

    pickerCallback!({ action: 'picked', documents: [{ id: 'FILE_123' }] });
    expect(onChange).toHaveBeenCalledWith('FILE_123');
  });

  it('maps Docs and Slides mimeTypes to their integration when requesting the token', async () => {
    process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY = 'k';
    postMock.mockResolvedValue({ access_token: 't' });
    installGoogleSDK(() => undefined);

    render(
      <GoogleDrivePickerField
        paramName="documentId"
        value=""
        onChange={vi.fn()}
        mimeType="application/vnd.google-apps.document"
      />,
    );
    fireEvent.click(screen.getByTestId('param-pick-documentId'));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/credentials/oauth2/picker-token', {
        integration: 'Google Docs',
        credential_name: 'googledocs',
      }),
    );
  });

  it('maps the Slides mimeType to Google Slides when requesting the token', async () => {
    process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY = 'k';
    postMock.mockResolvedValue({ access_token: 't' });
    installGoogleSDK(() => undefined);

    render(
      <GoogleDrivePickerField
        paramName="presentationId"
        value=""
        onChange={vi.fn()}
        mimeType="application/vnd.google-apps.presentation"
      />,
    );
    fireEvent.click(screen.getByTestId('param-pick-presentationId'));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/credentials/oauth2/picker-token', {
        integration: 'Google Slides',
        credential_name: 'googleslides',
      }),
    );
  });

  it('surfaces an error and does not pick when the backend returns no access token', async () => {
    process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY = 'k';
    postMock.mockResolvedValue({}); // no access_token
    installGoogleSDK(() => undefined);
    const onChange = vi.fn();

    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value=""
        onChange={onChange}
        mimeType="application/vnd.google-apps.spreadsheet"
      />,
    );
    fireEvent.click(screen.getByTestId('param-pick-spreadsheetId'));

    await waitFor(() => expect(screen.queryByTestId('param-pick-error-spreadsheetId')).not.toBeNull());
    expect(onChange).not.toHaveBeenCalled();
  });

  it('shows an error and never requests a token when no Picker API key is configured', async () => {
    // no NEXT_PUBLIC_GOOGLE_PICKER_API_KEY / NEXT_PUBLIC_GOOGLE_API_KEY set
    const onChange = vi.fn();
    render(
      <GoogleDrivePickerField
        paramName="spreadsheetId"
        value=""
        onChange={onChange}
        mimeType="application/vnd.google-apps.spreadsheet"
      />,
    );
    fireEvent.click(screen.getByTestId('param-pick-spreadsheetId'));

    await waitFor(() => expect(screen.queryByTestId('param-pick-error-spreadsheetId')).not.toBeNull());
    expect(postMock).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
  });
});
