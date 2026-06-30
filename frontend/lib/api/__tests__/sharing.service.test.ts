import { describe, it, expect } from 'vitest';
import { shareLinkPath, shareLinkUrl } from '../sharing.service';

/**
 * Regression: a FORM shared link must resolve through the unified `/s/` route, NOT `/f/`.
 * `/f/` resolves a form's OWN `fm_` token; a shared link carries the `sl_` SHARE token, so
 * the old FORM special-case built `/f/{sl_token}` which 404'd ("form not found"). CHAT /
 * CONVERSATION / APPLICATION already used `/s/` - this pins that FORM now matches them.
 */
describe('shareLinkPath / shareLinkUrl', () => {
  it('routes a FORM share token through /s/ (was /f/{sl_token} -> 404)', () => {
    expect(shareLinkPath('sl_00de60a8445c4c719efe5dfbd8d487f5')).toBe('/s/sl_00de60a8445c4c719efe5dfbd8d487f5');
  });

  it('routes every resource type through the same /s/ resolver', () => {
    for (const token of ['sl_form', 'sl_chat', 'sl_conversation', 'sl_application']) {
      expect(shareLinkPath(token)).toBe(`/s/${token}`);
    }
  });

  it('shareLinkUrl yields a /s/ URL and never an /f/ one', () => {
    const url = shareLinkUrl('sl_x');
    expect(url.endsWith('/s/sl_x')).toBe(true);
    expect(url).not.toContain('/f/');
  });
});
