/**
 * The integrations named in the public footer.
 *
 * <p><strong>Chosen, not ranked, and that is the point.</strong> The column used to render
 * the catalogue's most-RUN integrations. What that produced in production on 2026-09-06 was
 * "xAI, Instagram, Telegram, TikTok, YouTube Data API, Seedance, Gmail, Apify": a true
 * ranking of what the platform executes, and a poor answer to the only question a visitor
 * asks here, which is whether it connects to the tools their COMPANY runs. The usage ledger
 * is dominated by whatever the heaviest workflows happen to call, so it will keep surfacing
 * media and model APIs over the office stack. Instagram earns its place on recognition;
 * everything after it is the professional set.
 *
 * <p><strong>Why hardcoding is safe HERE and was rightly refused before.</strong> The
 * objection on `FooterIntegrations` is exact: a hand-written slug is a URL nobody verified,
 * and the failure mode is eight 404s in the footer of every page. That is answered by
 * verification, not by avoidance: `wellKnownIntegrations.test.ts` resolves every slug AND
 * every label against the API-migration seed corpus, so a rename that would break a link
 * fails a test instead of shipping.
 *
 * <p>It also removes a gateway read from every public page. The ranked version could not be
 * read at build time (the CI builder cannot reach the gateway) and timed out when a render
 * landed mid-rollout, which is how production served a column containing nothing but the
 * "All integrations" link for over an hour.
 *
 * <p>Names and slugs were read from the live catalogue
 * (`GET /api/public/integrations?q=...`) rather than guessed from the seed filenames, which
 * do not match: the slug is derived from the API's display name, so `google_sheets.json` is
 * served as `google-sheets`.
 */
export interface WellKnownIntegration {
  /** The catalogue slug, i.e. the last segment of `/integrations/{slug}`. */
  slug: string;
  /** The catalogue's display name, shown as the link text. */
  name: string;
  /**
   * Key into `/icons/services/{iconSlug}.svg`, which is NOT the slug: the slug comes from
   * the display name while the icon key drops its separators, so `google-sheets` is drawn
   * by `googlesheets`. Read from the catalogue with the rest, never derived.
   */
  iconSlug: string;
}

/**
 * The first {@link FOOTER_INTEGRATION_COUNT} are the footer column, in its reading order:
 * Instagram leads on recognition, then the tools a company runs its day on - mail, chat,
 * docs, code, spreadsheet, CRM, payments. The plain `instagram` entry is deliberate over
 * `instagram-instagram-login`, the same product under a login-flow label that means
 * nothing in a footer.
 *
 * <p>The remainder extends the same VERIFIED list for the landing's logo strip, which wants
 * more marks than a footer column can hold. They are ordered by how likely a visitor is to
 * recognise them, not by how often the platform runs them: the strip answers "does this
 * connect to what my company uses", and the usage ledger answers a different question (it
 * put xAI, Telegram and Seedance on top, which is true and unhelpful here).
 *
 * <p>Each added entry costs the same verification as the footer's: slug, name AND icon key
 * are resolved against the seed corpus by `wellKnownIntegrations.test.ts`, so a rename
 * fails a test rather than shipping a 404 or a blank brand mark onto the landing page.
 */
export const WELL_KNOWN_INTEGRATIONS: readonly WellKnownIntegration[] = [
  { slug: 'instagram', name: 'Instagram', iconSlug: 'instagram' },
  { slug: 'gmail', name: 'Gmail', iconSlug: 'gmail' },
  { slug: 'slack', name: 'Slack', iconSlug: 'slack' },
  { slug: 'notion', name: 'Notion', iconSlug: 'notion' },
  { slug: 'github', name: 'GitHub', iconSlug: 'github' },
  { slug: 'google-sheets', name: 'Google Sheets', iconSlug: 'googlesheets' },
  { slug: 'hubspot', name: 'HubSpot', iconSlug: 'hubspot' },
  { slug: 'stripe', name: 'Stripe', iconSlug: 'stripe' },
  { slug: 'openai', name: 'OpenAI', iconSlug: 'openai' },
  { slug: 'salesforce', name: 'Salesforce', iconSlug: 'salesforce' },
  { slug: 'shopify', name: 'Shopify', iconSlug: 'shopify' },
  { slug: 'google-drive', name: 'Google Drive', iconSlug: 'googledrive' },
  { slug: 'google-calendar', name: 'Google Calendar', iconSlug: 'googlecalendar' },
  { slug: 'airtable', name: 'Airtable', iconSlug: 'airtable' },
  { slug: 'discord', name: 'Discord', iconSlug: 'discord' },
  { slug: 'jira', name: 'Jira', iconSlug: 'jira' },
  { slug: 'linear', name: 'Linear', iconSlug: 'linear' },
  { slug: 'figma', name: 'Figma', iconSlug: 'figma' },
  { slug: 'linkedin', name: 'LinkedIn', iconSlug: 'linkedin' },
  { slug: 'trello', name: 'Trello', iconSlug: 'trello' },
  { slug: 'youtube-data-api', name: 'YouTube Data API', iconSlug: 'youtube' },
  { slug: 'dropbox', name: 'Dropbox', iconSlug: 'dropbox' },
  { slug: 'twilio', name: 'Twilio', iconSlug: 'twilio' },
  { slug: 'mailchimp', name: 'Mailchimp', iconSlug: 'mailchimp' },
  { slug: 'zendesk', name: 'Zendesk', iconSlug: 'zendesk' },
  { slug: 'reddit', name: 'Reddit', iconSlug: 'reddit' },
  { slug: 'tiktok', name: 'TikTok', iconSlug: 'tiktok' },
  { slug: 'facebook', name: 'Facebook', iconSlug: 'facebook' },
  { slug: 'pinterest', name: 'Pinterest', iconSlug: 'pinterest' },
  { slug: 'threads', name: 'Threads', iconSlug: 'threads' },
  { slug: 'twitter-x', name: 'Twitter / X', iconSlug: 'twitter' },
];

/**
 * How many of the list above the footer column renders, and therefore where the ordering
 * above stops being the footer's business: the rest exists for the landing's logo strip.
 */
export const FOOTER_INTEGRATION_COUNT = 8;
