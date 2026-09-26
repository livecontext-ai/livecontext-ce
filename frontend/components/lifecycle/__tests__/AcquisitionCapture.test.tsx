/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import AcquisitionCapture from '../AcquisitionCapture';
import {
  ACQUISITION_STORAGE_KEY,
  resetLandingSnapshotForTests,
} from '@/lib/lifecycle/acquisition';
import { CONSENT_CHANGE_EVENT } from '@/lib/analytics/consent';

const CONSENT_KEY = 'lc.cookieConsent';

function grantConsent() {
  window.localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'accepted', version: 1 }));
  window.dispatchEvent(new CustomEvent(CONSENT_CHANGE_EVENT, { detail: 'accepted' }));
}

describe('AcquisitionCapture follows the cookie banner', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetLandingSnapshotForTests();
    window.history.replaceState({}, '', '/fr?utm_source=newsletter&utm_campaign=sept');
  });

  it('writes nothing to storage before analytics consent is granted', () => {
    render(<AcquisitionCapture />);

    expect(window.localStorage.getItem(ACQUISITION_STORAGE_KEY)).toBeNull();
  });

  it('persists the LANDING utm once consent arrives after a client-side navigation', () => {
    render(<AcquisitionCapture />);
    window.history.replaceState({}, '', '/fr/app');

    act(() => grantConsent());

    const stored = JSON.parse(window.localStorage.getItem(ACQUISITION_STORAGE_KEY) ?? 'null');
    expect(stored.utmSource).toBe('newsletter');
    expect(stored.utmCampaign).toBe('sept');
    expect(stored.landingPath).toBe('/fr');
  });

  it('persists immediately when consent was already granted on a previous visit', () => {
    window.localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'accepted', version: 1 }));

    render(<AcquisitionCapture />);

    expect(window.localStorage.getItem(ACQUISITION_STORAGE_KEY)).not.toBeNull();
  });

  it('stays unrecorded when the visitor rejects the banner', () => {
    render(<AcquisitionCapture />);

    act(() => {
      window.localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'rejected', version: 1 }));
      window.dispatchEvent(new CustomEvent(CONSENT_CHANGE_EVENT, { detail: 'rejected' }));
    });

    expect(window.localStorage.getItem(ACQUISITION_STORAGE_KEY)).toBeNull();
  });
});
