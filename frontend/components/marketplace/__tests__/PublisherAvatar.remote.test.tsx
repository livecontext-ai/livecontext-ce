// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup } from '@testing-library/react';
import { PublisherAvatar } from '../PublisherAvatar';

afterEach(() => cleanup());

/**
 * CE-cloud parity: a cloud-linked CE renders cloud publications whose publisher
 * is a CLOUD user - absent from the local auth-service. The avatar <img> must
 * therefore hit the cloud proxy when `remote`, else it 404s and degrades to
 * initials. Locally (default) it keeps the stable local avatar endpoint.
 */
describe('PublisherAvatar - CE-cloud avatar src routing', () => {
  it('uses the LOCAL avatar endpoint by default', () => {
    const { container } = render(<PublisherAvatar userId="42" name="Ada Lovelace" />);
    const img = container.querySelector('img');
    expect(img?.getAttribute('src')).toBe('/api/proxy/users/42/avatar');
  });

  it('routes through the cloud proxy when remote', () => {
    const { container } = render(<PublisherAvatar userId="42" name="Ada Lovelace" remote />);
    const img = container.querySelector('img');
    expect(img?.getAttribute('src')).toBe('/api/proxy/publications/remote/users/42/avatar');
  });

  it('still renders an <img> (not initials) for the happy path so the cloud avatar can load', () => {
    const { container } = render(<PublisherAvatar userId="7" name="Grace Hopper" remote />);
    // No id-less / failed state - the backend image is attempted first.
    expect(container.querySelector('img')).toBeTruthy();
  });
});
