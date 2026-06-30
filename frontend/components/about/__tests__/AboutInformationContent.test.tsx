// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import AboutInformationContent from '../AboutInformationContent';

// The "About Us" block is shared by the public /about page and the in-app
// Settings > Information page. These tests lock the company social-link set so a
// removed or mis-targeted link (e.g. the new Discord community invite) fails fast.
describe('AboutInformationContent social links', () => {
  afterEach(cleanup);

  it('exposes every company social link with the expected href, including Discord', () => {
    render(<AboutInformationContent />);

    const expected: Record<string, string> = {
      LinkedIn: 'https://www.linkedin.com/company/livecontext/',
      X: 'https://x.com/livecontextai',
      Instagram: 'https://www.instagram.com/livecontext.ai/',
      GitHub: 'https://github.com/livecontext-ai',
      TikTok: 'https://www.tiktok.com/@livecontextai',
      Discord: 'https://discord.gg/5gTuUwhkJ',
    };

    for (const [name, href] of Object.entries(expected)) {
      const link = screen.getByRole('link', { name });
      expect(link).toHaveAttribute('href', href);
      // Social links open in a new tab with a safe rel.
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
  });
});
