/**
 * Tests for `normalizeIconSlug` - the canonical icon-slug collapse used by
 * credential lookups (wizard template fetch, missing-credentials extractor,
 * `hasExactIntegrationMatch`). Mirrors the backend `IconSlugNormalizer.normalize`
 * (catalog-service); the two MUST stay aligned because the canonical slug is
 * the join key between the workflow plan's tool data and `catalog.credentials`.
 */
import { describe, it, expect } from 'vitest';
import { normalizeIconSlug, isUnresolvedIconSlug, resolveIconSlug } from '../iconSlug';

describe('normalizeIconSlug', () => {
  it('Regression - apiSlug "google-gemini" collapses to canonical "googlegemini"', () => {
    // The exact bug from the multi-credential wizard: tools surfacing apiSlug
    // (hyphenated) must hit the same key as the credential template stored
    // under icon_slug "googlegemini". Pre-fix: hyphen survived → no match →
    // "Service configuration not found".
    expect(normalizeIconSlug('google-gemini')).toBe('googlegemini');
  });

  it('Collapses every separator the backend strips (space, hyphen, underscore)', () => {
    expect(normalizeIconSlug('Google Sheets')).toBe('googlesheets');
    expect(normalizeIconSlug('google_sheets')).toBe('googlesheets');
    expect(normalizeIconSlug('google-sheets')).toBe('googlesheets');
  });

  it('Lowercases and strips diacritics', () => {
    expect(normalizeIconSlug('Café')).toBe('cafe');
    expect(normalizeIconSlug('OPENAI')).toBe('openai');
  });

  it('Strips trailing -api suffix (matches backend behaviour for "gmail-api")', () => {
    expect(normalizeIconSlug('gmail-api')).toBe('gmail');
    expect(normalizeIconSlug('Gmail-API')).toBe('gmail');
  });

  it('Returns empty string for null / undefined / blank input', () => {
    expect(normalizeIconSlug(null)).toBe('');
    expect(normalizeIconSlug(undefined)).toBe('');
    expect(normalizeIconSlug('')).toBe('');
    expect(normalizeIconSlug('   ')).toBe('');
  });

  it('Is idempotent on already-canonical input', () => {
    expect(normalizeIconSlug('openai')).toBe('openai');
    expect(normalizeIconSlug('googlegemini')).toBe('googlegemini');
  });

  it('Does NOT apply KNOWN_ICON_MAPPINGS (frontend uses normalizeForKey semantics)', () => {
    // Backend `IconSlugNormalizer.normalize` collapses brand families
    // (`awss3 → amazonaws`, `googlecloudstorage → googlecloud`) for icon
    // paths, but `normalizeForKey` skips that table because credential keys
    // must be unique per API. The frontend uses this helper for credential
    // lookup, so we deliberately mirror `normalizeForKey`. Pinning the
    // behaviour here guards against a well-meaning future port of the
    // mapping table that would silently collapse distinct credentials.
    expect(normalizeIconSlug('awss3')).toBe('awss3');
    expect(normalizeIconSlug('googlecloudstorage')).toBe('googlecloudstorage');
    expect(normalizeIconSlug('dalle')).toBe('dalle');
  });
});

describe('isUnresolvedIconSlug / resolveIconSlug', () => {
  // Regression: every WorkflowInspectorService query selects
  // COALESCE(a.icon_slug, 'mcp'), so an API with no icon of its own reports the
  // literal slug "mcp". Because that is TRUTHY, the old `data.iconSlug ||
  // data.apiSlug` chains never fell through to the real slug and the node
  // rendered /icons/services/mcp.svg - a generic "API" circle - instead of the
  // brand logo, on the canvas and on every marketplace card.
  it('Treats the catalog "mcp" sentinel as unresolved', () => {
    expect(isUnresolvedIconSlug('mcp')).toBe(true);
    expect(isUnresolvedIconSlug('MCP')).toBe(true);
    expect(isUnresolvedIconSlug('  mcp  ')).toBe(true);
  });

  it('Treats a real brand slug as resolved', () => {
    expect(isUnresolvedIconSlug('slack')).toBe(false);
    expect(isUnresolvedIconSlug('googlesheets')).toBe(false);
    // Guards against an over-eager substring match killing a real API whose
    // slug merely contains "mcp".
    expect(isUnresolvedIconSlug('mcpserver')).toBe(false);
  });

  it('Blank input is unresolved', () => {
    expect(isUnresolvedIconSlug(null)).toBe(true);
    expect(isUnresolvedIconSlug(undefined)).toBe(true);
    expect(isUnresolvedIconSlug('')).toBe(true);
  });

  it('Regression - resolveIconSlug falls past the sentinel to the real apiSlug', () => {
    expect(resolveIconSlug('mcp', 'slack', 'slack/post_message')).toBe('slack');
  });

  it('Prefers an explicit slug when it is real', () => {
    expect(resolveIconSlug('googlesheets', 'google', 'x')).toBe('googlesheets');
  });

  it('Skips blanks, and returns undefined when nothing resolves', () => {
    expect(resolveIconSlug(null, '', '   ', 'notion')).toBe('notion');
    expect(resolveIconSlug('mcp', null, undefined)).toBeUndefined();
    expect(resolveIconSlug()).toBeUndefined();
  });
});
