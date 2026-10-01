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

/**
 * The illustrative profiles on the /partners page have no account behind them: they show an
 * explicit image instead of the avatar endpoint, and still fall back to initials if it fails.
 */
describe('PublisherAvatar - explicit image', () => {
  it('shows the given image, without any user id', () => {
    const { container } = render(<PublisherAvatar userId={null} name="Maya Chen" src="/avatars/avatar-5.svg" />);
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/avatars/avatar-5.svg');
  });

  it('the explicit image wins over the user endpoint', () => {
    const { container } = render(<PublisherAvatar userId="42" name="Maya Chen" src="/partners/examples/maya.webp" />);
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/partners/examples/maya.webp');
  });

  it('without an image or an id, the initials are the placeholder', () => {
    const { container } = render(<PublisherAvatar userId={null} name="Maya Chen" />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toBe('MC');
  });
});
