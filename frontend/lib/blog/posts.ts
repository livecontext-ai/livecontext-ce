// Blog post registry for the public marketing blog (`app/blog`).
//
// Each post body is a Markdown string module under `./content/<slug>.ts`;
// metadata lives in this typed index next to its import. Adding a post is: drop
// a `content/<slug>.ts` file, add its import + one `BLOG_POSTS` entry. Plain
// static imports (no bundler loader, no `.md` file) so they resolve identically
// under Turbopack, webpack and vitest, and are traced into the standalone build
// with no runtime filesystem read.
//
// The pure helpers live in `postUtils.ts` (unit-tested there); this module only
// holds the data and the content imports.

import type { BlogPost } from './postUtils';
import { sortPostsByDateDesc, findPostBySlug } from './postUtils';
import theNicheDataAdvantage from './content/the-niche-data-advantage';
import chatToWorkflowNoCode from './content/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from './content/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from './content/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from './content/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from './content/size-an-ai-agent-budget';
import aiAgentAuditTrail from './content/ai-agent-audit-trail';
import aiAgentAuditLogRetention from './content/ai-agent-audit-log-retention';

export type { BlogPost } from './postUtils';
export { estimateReadingMinutes, formatBlogDate, formatAuthors } from './postUtils';

// Authoring order here does not matter (getAllPosts sorts newest first). Keep a
// `slug` stable once published, it is the permalink.
const BLOG_POSTS: BlogPost[] = [
  {
    slug: 'the-niche-data-advantage',
    title: 'The niche data advantage, priced',
    date: '2026-07-07',
    excerpt:
      'The anti-moat evidence is stronger than the pro. So this prices the niche-data thesis instead of praising it: the strongest case against first, then one parameter to measure, a seven-row scorecard, and a build-buy-or-do-nothing break-even.',
    authors: ['theo p.', 'noah_schmidt'],
    tags: ['niche data', 'strategy'],
    cover: '/blog/the-niche-data-advantage.jpg',
    coverAlt: 'A laptop showing an analytics dashboard with charts, a map and metrics',
    content: theNicheDataAdvantage,
  },
  {
    slug: 'chat-to-workflow-no-code',
    title: 'Chat to workflow: no-code AI automation',
    date: '2026-07-05',
    excerpt:
      'Describe the job in plain language and get a workflow you can see, run, and change. No nodes to wire by hand, no black box.',
    authors: ['Sophie M.', 'Emma R.'],
    tags: ['no-code', 'automation'],
    cover: '/blog/chat-to-workflow-no-code.jpg',
    coverAlt: 'A hand typing a message on a phone showing a chat conversation',
    content: chatToWorkflowNoCode,
  },
  {
    slug: 'from-dataset-to-live-workflow',
    title: 'From dataset to live workflow, node by node',
    date: '2026-07-03',
    excerpt:
      'One real graph on a production engine: a scheduled price watch that refreshes, decides, and gates a write. With the exact template strings that resolve, the ones that silently fail, and the idempotent guard that stops it duplicating rows.',
    authors: ['Camille R.', 'noah_schmidt'],
    tags: ['workflows', 'how-to'],
    cover: '/blog/from-dataset-to-live-workflow.jpg',
    coverAlt: 'A hand drawing a workflow diagram of connected boxes and arrows on a whiteboard',
    content: fromDatasetToLiveWorkflow,
  },
  {
    slug: 'workflow-beats-do-everything-agent',
    title: 'What a scoped workflow actually costs versus a do-everything agent',
    date: '2026-07-01',
    excerpt:
      'We deleted our own "10x cheaper" claim because it had no derivation. Here is the cost model instead: two functions, one quadratic, a worked triage ledger, and the conditions where the ratio collapses to 1.3x or inverts.',
    authors: ['theo p.', 'nora_a'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/workflow-beats-do-everything-agent.jpg',
    coverAlt: 'A single robotic arm on a stand, representing an autonomous agent',
    content: workflowBeatsDoEverythingAgent,
  },
  {
    slug: 'cap-ai-agent-cost-budgets',
    title: 'The budget that actually stops the agent',
    date: '2026-06-24',
    excerpt:
      'Most agent budgets are a number that has never refused a single call. What a real ceiling is made of, why it can only ever stop the call after the expensive one, and what each stack can actually enforce.',
    authors: ['theo p.', 'ines_l'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/cap-ai-agent-cost-budgets.jpg',
    coverAlt: 'Coins scattered on a desk beside a notebook and pen for budgeting',
    content: capAiAgentCostBudgets,
  },
  {
    slug: 'size-an-ai-agent-budget',
    date: '2026-06-22',
    title: 'How to size an agent budget you can actually enforce',
    excerpt:
      'The sizing half: a generating model you can reproduce, a derived safety factor, the floor below which a money cap cannot be enforced at all, and how many runs you need before you may quote a p99.',
    authors: ['ines_l', 'nora_a'],
    tags: ['ai agents', 'cost'],
    // Temporary: reuses the calculator cover currently on small-data-sharp-decisions,
    // which this series retires. Swap for a dedicated image when one exists.
    cover: '/blog/small-data-sharp-decisions.jpg',
    coverAlt: 'Hands using a calculator next to printed charts while analyzing data',
    content: sizeAnAiAgentBudget,
  },
  {
    slug: 'ai-agent-audit-trail',
    title: 'The agent audit trail: a field schema you can copy',
    date: '2026-06-20',
    excerpt:
      'An audit trail is not a longer log, it is a different artifact with a different reader. A copyable run-level and step-level schema where every field carries its type, cardinality, personal-data flag, and the reason it exists.',
    authors: ['ines_l', 'noah_schmidt'],
    tags: ['ai agents', 'governance'],
    cover: '/blog/ai-agent-audit-trail.jpg',
    coverAlt: 'A magnifying glass and calculator resting on printed documents',
    content: aiAgentAuditTrail,
  },
  {
    slug: 'ai-agent-audit-log-retention',
    date: '2026-06-18',
    title: 'How long to keep an agent audit trail, and what you actually owe',
    excerpt:
      'The storage arithmetic that makes retention a derived decision, the tiering that follows, and an honest map of the EU AI Act logging duties, including the part where most agents are out of scope entirely.',
    authors: ['ines_l', 'nora_a'],
    tags: ['ai agents', 'governance'],
    // Temporary: reuses the from-dataset cover (a whiteboard diagram) until a
    // dedicated image exists. Distinct from the magnifying-glass audit-trail cover.
    cover: '/blog/from-dataset-to-live-workflow.jpg',
    coverAlt: 'A hand drawing a workflow diagram of connected boxes and arrows on a whiteboard',
    content: aiAgentAuditLogRetention,
  },
];

/** All posts, newest first. */
export function getAllPosts(): BlogPost[] {
  return sortPostsByDateDesc(BLOG_POSTS);
}

/** The post for a slug, or `undefined` when no post matches. */
export function getPostBySlug(slug: string): BlogPost | undefined {
  return findPostBySlug(BLOG_POSTS, slug);
}
