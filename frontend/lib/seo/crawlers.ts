/**
 * Which crawlers may read this site, split by what they DO with what they read.
 *
 * The distinction that matters is not "AI or not", it is "does this crawler
 * feed an answer someone is reading right now, or a training corpus". A product
 * people discover by asking an assistant cannot afford to be invisible to the
 * first group, and gains nothing from the second.
 *
 * These lists are the single source of truth for `buildRobotsTxt`. Adding a
 * crawler here is the whole change: the groups, the rules and the tests all
 * read from these.
 */

/**
 * Crawlers that feed an answer a person reads, and can cite us back.
 *
 * They get the same access as a search engine: the public site, none of the
 * private surfaces. Grouped per vendor so the reason each one is here survives:
 *
 *  - `OAI-SearchBot` / `ChatGPT-User` - OpenAI's search index and its
 *    user-initiated fetch. `GPTBot`, the training crawler, is NOT here.
 *  - `Claude-SearchBot` / `Claude-User` - Anthropic's search index and its
 *    user-initiated fetch. `ClaudeBot`, the training crawler, is NOT here.
 *  - `PerplexityBot` / `Perplexity-User` - Perplexity's index and its
 *    user-initiated fetch.
 *  - `Applebot` - Siri and Spotlight. `Applebot-Extended`, which is the
 *    training opt-out and nothing else, is NOT here.
 *  - `YouBot` - You.com's index. It answers, so the rule above puts it here.
 *
 * Googlebot and Bingbot are absent on purpose: they are covered by the `*`
 * group, and naming them would cost them the `*` rules (see the note on
 * inheritance in `buildRobotsTxt`).
 */
export const ANSWER_ENGINE_CRAWLERS = [
  'OAI-SearchBot',
  'ChatGPT-User',
  'Claude-SearchBot',
  'Claude-User',
  'PerplexityBot',
  'Perplexity-User',
  'Applebot',
  'YouBot',
] as const;

/**
 * Crawlers that collect a training corpus.
 *
 * Refused, which is also an express reservation of rights under Article 4 of
 * EU Directive 2019/790. Each of these is a vendor's documented training agent,
 * and for the three vendors that run both kinds, the sibling that serves
 * answers is in the list above instead.
 *
 * `Amazonbot` is the judgement call. It does feed Alexa answers, which the rule
 * above would admit, but Amazon documents the same agent as training input and
 * publishes no separate search-only crawler to admit instead. It stays refused,
 * which is also where it already was before this file existed.
 */
export const TRAINING_CRAWLERS = [
  'GPTBot',
  'ClaudeBot',
  'anthropic-ai',
  'Google-Extended',
  'Applebot-Extended',
  'CCBot',
  'Bytespider',
  'Amazonbot',
  'meta-externalagent',
  'FacebookBot',
  'cohere-ai',
  'Diffbot',
  'ImagesiftBot',
  'Omgilibot',
  'AI2Bot',
] as const;

/**
 * The content signals served alongside the rules.
 *
 * <p><strong>Cloudflare's managed robots.txt has to stay off for this to mean
 * anything.</strong> It is the "Managed robots.txt" switch on the zone's
 * <em>AI Crawl Control</em> page, NOT under Security Settings: the Cloudflare
 * documentation still points at "Security Settings, filter by Bot traffic",
 * and the setting is not there, which cost a session's worth of looking.
 *
 * <p>It was ON, and it PREPENDS its own block rather than replacing ours, so
 * the file served carried two `User-agent: *` groups with two different
 * `Content-Signal` lines; duplicate resolution for that is undefined, which
 * made `ai-input=yes` ambiguous. Turned off on 2026-09-14 and verified on the
 * live file: one `*` group, two identical signal lines (the wildcard group and
 * the answer-engine one), no managed block. The crawlers Cloudflare named
 * never overlapped ANSWER_ENGINE_CRAWLERS, so only the signal was contested.
 *
 * <p>The signals are stated here because the site has none without them: they
 * are the machine-readable form of the same decision the two lists above
 * encode, and `ai-train=no` is what carries the reservation of rights for a
 * crawler that reads signals but not our group names.
 *
 * One value differs from what Cloudflare emitted. `ai-input=yes` is the
 * decision this change makes: grounding an answer in this content is welcome,
 * which is the signal form of the answer-engine list above.
 *
 * `use=reference` is NOT invented, despite not appearing in the three-signal
 * summaries: it was read off the live `livecontext.ai/robots.txt`, where
 * Cloudflare's own preamble documents it as "how AI systems may consume the
 * content (immediate, reference, or full)" and emits
 * `search=yes,ai-train=no,use=reference`. It is kept verbatim, and it is the
 * consistent companion to `ai-input=yes`: reference the content, do not absorb
 * it.
 */
export const CONTENT_SIGNAL = 'search=yes,ai-input=yes,ai-train=no,use=reference';
