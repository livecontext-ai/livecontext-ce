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
 *  fine while a company has not authorized its name).
 *
 *  The entries below are SAMPLES and are written to be worth copying: each names a
 *  real task, real tools and a number, because that is what separates a quote a
 *  reader believes from one that reads as filler. Send them to a customer as the
 *  shape of the answer you are after, then REPLACE them with what comes back. They
 *  keep the SAMPLE marker precisely because they are convincing: the kill switch
 *  below is what stops a good-looking sample from being published as a real person.
 *
 *  A quote here is a statement that a named person said this. Writing a plausible
 *  one is not a shortcut, it is a fabricated testimonial: illegal as a commercial
 *  practice in the EU, and the first thing an audience checks. The section renders
 *  the capability cards until this array holds real, authorized entries, so the
 *  landing never has a hole and never has an invented customer either. */
export type Testimonial = {
  quote: string;
  name: string;
  role: string;
  company?: string;
  /** Path under /public, e.g. '/avatars/customers/jane.jpg'. Falls back to initials. */
  avatar?: string;
  /** Slugs of `WELL_KNOWN_INTEGRATIONS`, drawn as brand marks on the card. */
  integrations?: readonly string[];
};

export const TESTIMONIALS: Testimonial[] = [
  {
    quote:
      'SAMPLE - Every Monday I spent about three hours moving Typeform leads into HubSpot and chasing the ones nobody had replied to. It is a six-node workflow now, and the chasing is the part I did not expect it to handle.',
    name: 'Customer name',
    role: 'Operations lead',
    integrations: ['hubspot', 'slack'],
  },
  {
    quote:
      'SAMPLE - The investor update used to eat my Friday. It pulls Stripe, the metrics sheet and the support queue, drafts the whole thing, and I spend twenty minutes editing instead of four hours writing.',
    name: 'Customer name',
    role: 'Founder',
    integrations: ['stripe', 'google-sheets'],
  },
  {
    quote:
      'SAMPLE - We publish around forty client posts a week across Instagram and LinkedIn. The approval step is what sold it: nothing goes out until someone signs off, and that is the same screen on my phone.',
    name: 'Customer name',
    role: 'Agency owner',
    integrations: ['instagram', 'linkedin'],
  },
  {
    quote:
      'SAMPLE - Two hundred CVs a week land in a Gmail label. They get parsed, scored against the brief, and the hiring manager opens one page instead of an inbox.',
    name: 'Customer name',
    role: 'Talent lead',
    integrations: ['gmail', 'airtable'],
  },
  {
    quote:
      'SAMPLE - Every ticket gets classified and routed, and the reply is drafted before anyone opens it. Our first response time went from about four hours to under one.',
    name: 'Customer name',
    role: 'Support lead',
    integrations: ['zendesk', 'slack'],
  },
  {
    quote:
      'SAMPLE - I gave it our release checklist and it just ran. The loop nodes took me two days to get my head around, but I have not touched the workflow since.',
    name: 'Customer name',
    role: 'Engineering lead',
    integrations: ['github', 'discord'],
  },
  {
    quote:
      'SAMPLE - A Shopify order checks stock before anything else, and when a line is short the supplier gets the email and the restock date lands back on the order. We stopped finding out at packing time.',
    name: 'Customer name',
    role: 'Ecommerce operator',
    integrations: ['shopify', 'gmail'],
  },
  {
    quote:
      'SAMPLE - Supplier invoices used to be typed into the sheet by hand, about sixty a month. They get read out of the Drive folder now, and anything outside the usual range is flagged for me rather than posted quietly.',
    name: 'Customer name',
    role: 'Finance manager',
    integrations: ['google-drive', 'google-sheets'],
  },
  {
    quote:
      'SAMPLE - Every Friday the issues closed that week become a digest grouped by theme, in the team channel. It took me one afternoon to build and it has replaced the status meeting.',
    name: 'Customer name',
    role: 'Product manager',
    integrations: ['linear', 'slack'],
  },
  {
    quote:
      'SAMPLE - A booking in the calendar pulls the client brief together before the call, and the invoice goes out after it. I stopped losing a day a month to admin I kept postponing.',
    name: 'Customer name',
    role: 'Consultant',
    integrations: ['google-calendar', 'stripe'],
  },
  {
    quote:
      'SAMPLE - Form submissions get enriched and dropped into the right Mailchimp segment. It is not clever, it is just the thing I never got round to doing properly for two years.',
    name: 'Customer name',
    role: 'Growth lead',
    integrations: ['mailchimp', 'hubspot'],
  },
  {
    quote:
      'SAMPLE - A new upload is transcribed, cut into short clips and turned into a draft post waiting for review. I still rewrite the drafts, but starting from something beats starting from nothing.',
    name: 'Customer name',
    role: 'Content lead',
    integrations: ['youtube-data-api', 'notion'],
  },
];

/**
 * What to ask for, so a collected quote is usable as it stands.
 *
 * <p>The difference between a quote that convinces and one that reads as filler is
 * detail, not adjectives. "Great tool, saved us time" says nothing; "I spent 3h
 * every Monday recopying Typeform leads into HubSpot, now it is a four-node
 * workflow I never look at" is checkable, and a reader recognises their own Monday
 * in it. Ask for the task, the tool names, a number, and what changed. A reservation
 * ("the loop nodes took me two days") makes the rest more credible, not less.
 *
 * <p>Get the permission in writing at the same time: name, role, company and photo
 * are each a separate yes.
 */
export const TESTIMONIAL_BRIEF =
  'What did you automate, which tools does it touch, and what did it replace? A number helps.';

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
