/**
 * The automations shown in the landing's "What you can build" band.
 *
 * <p><strong>These are capability examples, and the section says so.</strong> They are NOT
 * testimonials, not case studies, and not anonymised customers: nothing here claims that a
 * particular company exists or said anything. That distinction is the whole reason the file
 * exists separately from `socialProof.ts`, which holds real, authorized customer material
 * behind a kill switch and stays empty until there is some. A landing page may describe what
 * its product does; it may not invent the people who use it, and putting the two kinds of
 * content in one file is how they get confused.
 *
 * <p>Every entry is therefore held to one rule: <strong>it must be buildable today with the
 * nodes and integrations that ship.</strong> Each `role` is a job to be done rather than a
 * named customer, each `automation` names the real trigger and the real steps, and each
 * `integrations` list is verified as slugs of `WELL_KNOWN_INTEGRATIONS` by
 * `automationExamples.test.ts`, so a card cannot advertise a connector the catalogue does
 * not carry.
 *
 * <p>Specificity is deliberate, not decoration. A band of "boost your productivity" cards
 * tells a visitor nothing and reads as filler; "every Monday at 8, pull six dashboards into
 * one PDF" tells them whether their own Monday is in scope. Keep numbers and tool names when
 * editing these, and keep each `automation` to a trigger, a middle and a result.
 */

export interface AutomationExample {
  /** The job to be done, not a customer. Never a company name. */
  role: string;
  /** Trigger, middle, result, in one sentence a visitor can check against their own week. */
  automation: string;
  /** Slugs of `WELL_KNOWN_INTEGRATIONS`, rendered as brand marks on the card. */
  integrations: readonly string[];
}

export const AUTOMATION_EXAMPLES: readonly AutomationExample[] = [
  {
    role: 'Social media agency',
    automation:
      'A row lands in a content sheet, the agent writes the post and the visual, a human approves it, then it publishes to Instagram and LinkedIn.',
    integrations: ['google-sheets', 'instagram', 'linkedin'],
  },
  {
    role: 'Solo founder',
    automation:
      'A Stripe payment creates the customer row in Notion, opens the onboarding checklist and posts the amount to Slack.',
    integrations: ['stripe', 'notion', 'slack'],
  },
  {
    role: 'Recruiter',
    automation:
      'CVs arriving in a Gmail label are parsed into a table, scored against the brief, and the shortlist becomes a page the hiring manager opens.',
    integrations: ['gmail', 'airtable'],
  },
  {
    role: 'Support lead',
    automation:
      'Every new Zendesk ticket is classified, routed to the right queue, and answered with a draft that waits for a human before it sends.',
    integrations: ['zendesk', 'slack'],
  },
  {
    role: 'Operations manager',
    automation:
      'Every Monday at 8, six dashboards are pulled into one PDF and mailed to the leadership list before the weekly meeting.',
    integrations: ['google-sheets', 'gmail'],
  },
  {
    role: 'Sales team',
    automation:
      'A deal moving stage in HubSpot enriches the company, drafts the follow-up, and alerts the account owner in Slack.',
    integrations: ['hubspot', 'slack'],
  },
  {
    role: 'Ecommerce operator',
    automation:
      'A Shopify order checks stock, emails the supplier when a line is short, and writes the restock date back onto the order.',
    integrations: ['shopify', 'gmail'],
  },
  {
    role: 'Content team',
    automation:
      'A new YouTube upload is transcribed, cut into short clips, and turned into a blog draft waiting for review.',
    integrations: ['youtube-data-api', 'notion'],
  },
  {
    role: 'Finance',
    automation:
      'Invoices dropped in a Drive folder are read into a spreadsheet, and anything outside the usual range is flagged for a human.',
    integrations: ['google-drive', 'google-sheets'],
  },
  {
    role: 'Product manager',
    automation:
      'Linear issues closed this week become a digest, grouped by theme, posted to the team channel every Friday.',
    integrations: ['linear', 'slack'],
  },
  {
    role: 'Developer',
    automation:
      'A pull request opened on GitHub runs the review checklist, comments the findings, and pages the on-call in Discord if it touches deploy code.',
    integrations: ['github', 'discord'],
  },
  {
    role: 'Consultant',
    automation:
      'A booking in Google Calendar prepares the client brief, and after the call the notes are filed and the invoice goes out.',
    integrations: ['google-calendar', 'stripe'],
  },
];
