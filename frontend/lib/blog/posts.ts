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
import smallDataSharpDecisions from './content/small-data-sharp-decisions';
import capAiAgentCostBudgets from './content/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from './content/ai-agent-audit-trail';

export type { BlogPost } from './postUtils';
export { estimateReadingMinutes, formatBlogDate, formatAuthors } from './postUtils';

// Authoring order here does not matter (getAllPosts sorts newest first). Keep a
// `slug` stable once published, it is the permalink.
const BLOG_POSTS: BlogPost[] = [
  {
    slug: 'the-niche-data-advantage',
    title: 'The niche data advantage',
    date: '2026-07-07',
    excerpt:
      'Big data is a commodity. The teams that ship useful automations win on small, sharp datasets almost nobody bothers to structure.',
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
    title: 'From dataset to live workflow',
    date: '2026-07-03',
    excerpt:
      'A five-step shape for turning a static niche source into a workflow that refreshes itself and ends in a real action.',
    authors: ['Camille R.', 'noah_schmidt'],
    tags: ['workflows', 'how-to'],
    cover: '/blog/from-dataset-to-live-workflow.jpg',
    coverAlt: 'A hand drawing a workflow diagram of connected boxes and arrows on a whiteboard',
    content: fromDatasetToLiveWorkflow,
  },
  {
    slug: 'workflow-beats-do-everything-agent',
    title: 'Why a workflow beats a do-everything agent',
    date: '2026-07-01',
    excerpt:
      'A scoped workflow runs far cheaper, stays auditable, and fails less than one big autonomous agent. Here is when to use each.',
    authors: ['theo p.', 'nora_a'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/workflow-beats-do-everything-agent.jpg',
    coverAlt: 'A single robotic arm on a stand, representing an autonomous agent',
    content: workflowBeatsDoEverythingAgent,
  },
  {
    slug: 'small-data-sharp-decisions',
    title: 'Small data, sharp decisions',
    date: '2026-06-28',
    excerpt:
      'Better decisions rarely need more data. A small, trustworthy dataset that maps to a choice beats a giant one that buries the signal.',
    authors: ['nora_a', 'theo p.'],
    tags: ['niche data', 'decisions'],
    cover: '/blog/small-data-sharp-decisions.jpg',
    coverAlt: 'Hands using a calculator next to printed charts while analyzing data',
    content: smallDataSharpDecisions,
  },
  {
    slug: 'cap-ai-agent-cost-budgets',
    title: 'How to cap what an AI agent can spend',
    date: '2026-06-24',
    excerpt:
      'Unbounded agents are a financial risk. Give each one a hard budget it cannot exceed, and scope the tools and data it can touch.',
    authors: ['theo p.', 'ines_l'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/cap-ai-agent-cost-budgets.jpg',
    coverAlt: 'Coins scattered on a desk beside a notebook and pen for budgeting',
    content: capAiAgentCostBudgets,
  },
  {
    slug: 'ai-agent-audit-trail',
    title: 'The audit trail every AI agent needs',
    date: '2026-06-20',
    excerpt:
      'A demo that works is not enough. Log inputs, tool calls, outputs, cost, and each decision so you can debug, prove compliance, and earn trust.',
    authors: ['ines_l', 'noah_schmidt'],
    tags: ['ai agents', 'governance'],
    cover: '/blog/ai-agent-audit-trail.jpg',
    coverAlt: 'A magnifying glass and calculator resting on printed documents',
    content: aiAgentAuditTrail,
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
