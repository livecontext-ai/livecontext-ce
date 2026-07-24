// Social-proof content for the public landing page. Everything here is DATA the
// marketing owner curates by hand; components render whatever these arrays hold
// and hide themselves when an array is empty.
//
// LEGAL CONSTRAINT: a customer name or logo may only be listed here with that
// customer's written authorization (an email approval is enough, but keep it).
// The entries shipped below are SAMPLE placeholders that demonstrate the layout.
// Deploying this file unedited is SAFE: the SAMPLE kill switch at the bottom
// keeps the corresponding sections hidden until every entry has been replaced
// with real, authorized data - replacing them is what makes the sections appear.

/** Customer logos shown in the "They automate with LiveContext" strip.
 *  `src` is an image under /public (SVG or transparent PNG); when omitted the
 *  name renders as a wordmark, which is fine for brands without a usable logo. */
export type CustomerLogo = { name: string; src?: string };

export const CUSTOMER_LOGOS: CustomerLogo[] = [
  // { name: 'Acme Corp', src: '/landing/customers/acme.svg' },
];

/** Aggregate platform counters (anonymous, no per-customer data, so no
 *  authorization needed). Keep the values honest: update them from real
 *  production numbers before each deploy, or wire them to a public stats
 *  endpoint later. */
export type LandingMetric = { value: string; label: string };

export const LANDING_METRICS: LandingMetric[] = [
  { value: 'SAMPLE 12,000+', label: 'automation runs executed' },
  { value: 'SAMPLE 800+', label: 'automations built by chat' },
  { value: 'SAMPLE 300+', label: 'apps shared with a link' },
];

/** Named testimonials. Only real quotes from real users, with their consent to
 *  be quoted by name and role. `company` is optional (role-only attribution is
 *  fine while a company has not authorized its name). */
export type Testimonial = {
  quote: string;
  name: string;
  role: string;
  company?: string;
};

export const TESTIMONIALS: Testimonial[] = [
  {
    quote:
      'SAMPLE - I described our client onboarding in one message and got a working pipeline with a page my team actually opens every morning. Replace this quote with a real customer quote.',
    name: 'Customer name',
    role: 'Operations lead',
  },
  {
    quote:
      'SAMPLE - The weekly report used to take me half a day. Now it builds itself and lands as a PDF in my inbox. Replace this quote with a real customer quote.',
    name: 'Customer name',
    role: 'Founder',
  },
  {
    quote:
      'SAMPLE - We shared the generated app with a customer the same afternoon we built it. Replace this quote with a real customer quote.',
    name: 'Customer name',
    role: 'Agency owner',
  },
];

/** Kill switch: sample data must never reach production. Components render the
 *  testimonials/metrics only when every entry has been replaced (no 'SAMPLE'
 *  marker left), so an unedited file ships a landing WITHOUT those sections
 *  rather than one with fake numbers. */
const SAMPLE_MARKER = 'SAMPLE';

export function metricsReady(metrics: LandingMetric[]): boolean {
  return metrics.length > 0 && metrics.every((m) => !m.value.includes(SAMPLE_MARKER));
}

export function testimonialsReady(testimonials: Testimonial[]): boolean {
  return (
    testimonials.length > 0 &&
    testimonials.every((t) => !t.quote.includes(SAMPLE_MARKER) && t.name !== 'Customer name')
  );
}
