import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  CUSTOMER_LOGOS,
  LANDING_METRICS,
  TESTIMONIALS,
  metricsReady,
  testimonialsReady,
  type LandingMetric,
  type Testimonial,
} from '../socialProof';

// The SAMPLE kill switch is what keeps placeholder marketing data from ever
// reaching production: an unedited socialProof.ts must ship a landing WITHOUT
// the metrics/testimonials sections, never one with fake numbers or quotes.

describe('socialProof SAMPLE kill switch', () => {
  it('metricsReady rejects any metric still carrying the SAMPLE marker', () => {
    const metrics: LandingMetric[] = [
      { value: '12,000+', label: 'runs' },
      { value: 'SAMPLE 800+', label: 'automations' },
    ];
    expect(metricsReady(metrics)).toBe(false);
  });

  it('metricsReady rejects an empty list (nothing to show)', () => {
    expect(metricsReady([])).toBe(false);
  });

  it('metricsReady accepts a fully replaced list', () => {
    const metrics: LandingMetric[] = [
      { value: '12,000+', label: 'runs' },
      { value: '800+', label: 'automations' },
    ];
    expect(metricsReady(metrics)).toBe(true);
  });

  it('testimonialsReady rejects quotes still carrying the SAMPLE marker or the placeholder name', () => {
    const sampleQuote: Testimonial[] = [
      { quote: 'SAMPLE - replace me', name: 'Jane Doe', role: 'Founder' },
    ];
    const placeholderName: Testimonial[] = [
      { quote: 'Real words from a real user.', name: 'Customer name', role: 'Founder' },
    ];
    expect(testimonialsReady(sampleQuote)).toBe(false);
    expect(testimonialsReady(placeholderName)).toBe(false);
    expect(testimonialsReady([])).toBe(false);
  });

  it('testimonialsReady accepts real, fully attributed quotes', () => {
    const real: Testimonial[] = [
      { quote: 'It builds the report for me every Monday.', name: 'Jane Doe', role: 'Ops lead', company: 'Acme' },
    ];
    expect(testimonialsReady(real)).toBe(true);
  });

  it('the shipped file is still in its SAMPLE state OR fully replaced - never half-edited', () => {
    // Either the marketing owner has not touched the file (sections hidden) or
    // every entry was replaced (sections shown). A mix would silently hide real
    // content, so fail loudly on it.
    const metricsAllSample = LANDING_METRICS.every((m) => m.value.includes('SAMPLE'));
    const testimonialsAllSample = TESTIMONIALS.every((t) => t.quote.includes('SAMPLE'));
    expect(metricsAllSample || metricsReady(LANDING_METRICS)).toBe(true);
    expect(testimonialsAllSample || testimonialsReady(TESTIMONIALS)).toBe(true);
    // Logos have no marker mechanism: entries are only added once authorized.
    expect(Array.isArray(CUSTOMER_LOGOS)).toBe(true);
  });
});

// The kill switch only protects production if the PAGE actually consults it.
// Lock the wiring at the source level (same pattern as landingChromeTheme.test):
// a refactor that renders the metrics/testimonials unconditionally would ship
// fake quotes while every pure-function test above stays green.
describe('landing page consults the kill switch', () => {
  const pageSrc = readFileSync(
    path.resolve(__dirname, '../../page.tsx'),
    'utf8',
  );

  it('gates the social-proof strip on metricsReady (and logo presence)', () => {
    expect(pageSrc).toMatch(/const hasMetrics = metricsReady\(LANDING_METRICS\)/);
    expect(pageSrc).toMatch(/if \(!hasLogos && !hasMetrics\) return null/);
  });

  it('gates the testimonials section on testimonialsReady', () => {
    expect(pageSrc).toMatch(/if \(!testimonialsReady\(TESTIMONIALS\)\) return null/);
  });
});
