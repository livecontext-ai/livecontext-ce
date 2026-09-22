import { Bot, Server, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Models & providers',
  description:
    'How LiveContext sources and configures models: BYOK API-key providers, CLI bridge providers (Claude Code, Codex, Gemini CLI, Mistral Vibe), the six reasoning-effort levels and who honors them, the admin model catalog, model categories, cloud-only execution links, and the CE cloud-vs-BYOK toggle.',
  path: '/docs/models',
});

export default function ModelsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Models & providers"
        lead="Every model that powers chat, standalone agents, and workflow agent nodes comes from one admin panel. This page covers where models come from, how reasoning effort works across providers, how the catalog is configured, and what's different on a self-hosted install."
      />

      <DocsProse>
        <h2>Where models are configured</h2>
        <p>
          Models live in <strong>Settings &rsaquo; AI Providers</strong>, an <strong>admin-only</strong>{' '}
          page (non-admins see an unauthorized notice). It&apos;s organized as a row of tabs: <strong>API
          Keys</strong>, one tab per CLI bridge (<strong>Claude Code</strong>, <strong>Codex</strong>,{' '}
          <strong>Gemini CLI</strong>, <strong>Mistral Vibe</strong>), <strong>Models</strong>, and, on
          cloud only, <strong>Execution Links</strong>.
        </p>
        <p>
          Models come from three families: <strong>API-key providers</strong> you bring your own key
          for (BYOK), <strong>CLI bridge providers</strong> that run a coding-agent CLI on a bridge
          host, and, on cloud, a curated hosted catalog. The <strong>Models</strong> tab is the single
          source of truth for every model the platform exposes, not just admin overrides on top of a
          hidden default list.
        </p>

        <h2>API-key providers (BYOK)</h2>
        <p>
          Add a platform credential for any of these and its models become available wherever you pick
          a model:
        </p>
        <DocsTable
          head={['Provider', 'Models']}
          rows={[
            ['Anthropic', 'Claude'],
            ['OpenAI', 'GPT'],
            ['Google', 'Gemini'],
            ['Mistral AI', 'Mistral'],
            ['DeepSeek', 'DeepSeek'],
            ['xAI', 'Grok'],
            ['Perplexity', 'Sonar'],
            ['Cohere', 'Command R+'],
            ['Z.AI', 'GLM'],
            ['OpenRouter', 'multi-provider aggregator'],
            ['Qwen', 'Alibaba'],
            ['Moonshot', 'Kimi'],
            ['MiniMax', 'MiniMax M-series'],
          ]}
        />
        <p>
          Saving a key stores it as a platform credential and immediately invalidates the model cache
          for that provider, so newly enabled models show up right away.
        </p>

        <h2>CLI bridge providers</h2>
        <p>
          The four bridge providers run a coding-agent CLI on a bridge host rather than calling an API
          directly: <code>claude-code</code>, <code>codex</code>, <code>gemini-cli</code>, and{' '}
          <code>mistral-vibe</code>. Each bridge tab has two panels: a <strong>setup panel</strong> to
          install and authenticate the CLI on the bridge host, and an <strong>access panel</strong> to
          control who may dispatch through it.
        </p>
        <p>
          The distinction that matters: an <strong>API model</strong> is usable the moment its key is
          saved. A <strong>CLI model</strong> additionally needs a live, authenticated CLI running on
          the bridge host, a separate, external step from adding a key.
        </p>
        <Callout variant="info">
          Bridge model turns are priced like any other model, at the underlying cloud model&apos;s
          published list price, and debit ledger credits at the same per-token rate as the direct API
          route. The CLI subscription itself is a cost the admin pays externally; it doesn&apos;t make
          turns free inside LiveContext.
        </Callout>
        <p>
          Because CLI providers are local bridges the cloud can never provide on your behalf, the
          cloud-vs-BYOK source toggle (see the CE section below) is not shown on bridge tabs.
        </p>

        <h2>Reasoning effort</h2>
        <p>
          Reasoning effort is a single dial with six canonical levels, from lightest to most thorough:
        </p>
        <DocsTable
          head={['Level', 'Notes']}
          rows={[
            ['minimal', 'Lightest reasoning pass.'],
            ['low', ''],
            ['medium', ''],
            ['high', ''],
            ['xhigh', ''],
            ['max', 'Deepest reasoning pass, highest latency and cost.'],
          ]}
        />
        <p>
          <strong>Only three providers actually honor it today: <code>claude-code</code>,{' '}
          <code>codex</code>, and the direct <code>anthropic</code> API.</strong> Gemini CLI and
          Mistral Vibe expose no usable effort knob and don&apos;t show the control at all. Effort
          isn&apos;t a bridge-only idea either: anthropic is a regular BYOK/API provider and still
          honors it, so the per-model effort-default column in the Models panel renders for these three
          providers&apos; rows specifically, not for bridges as a category. Every other
          provider&apos;s row shows an empty cell there.
        </p>
        <p>Each of the three maps the level to its own native knob:</p>
        <DocsTable
          head={['Provider', 'Native knob', 'Notes']}
          rows={[
            [
              'Claude Code',
              <code key="cc">CLAUDE_CODE_EFFORT_LEVEL</code>,
              <>accepts low, medium, high, xhigh, max; <code>minimal</code> clamps to low.</>,
            ],
            [
              'Codex',
              <code key="cx">-c model_reasoning_effort=&lt;level&gt;</code>,
              <>
                accepts minimal, low, medium, high, xhigh (no <code>max</code>, so max clamps to
                xhigh); xhigh and max additionally require a <code>codex-max</code> model, clamping
                down to high on other models.
              </>,
            ],
            [
              'Anthropic (API)',
              <code key="an">output_config.effort</code>,
              'clamped per model, see below.',
            ],
          ]}
        />
        <p>Anthropic&apos;s clamping is derived from the model id, with no live capability check:</p>
        <DocsTable
          head={['Case', 'Rule']}
          rows={[
            [
              'Effort support at all',
              'Fable / Mythos, Opus 4.5 and above, or Sonnet 4.6 and above. Haiku (through 4.5) supports no effort control.',
            ],
            ['minimal', 'always maps to low; the API itself has no minimal level.'],
            [
              'xhigh',
              'needs Fable / Opus 4.7+ or Sonnet 5+; otherwise falls back to high.',
            ],
            [
              'max',
              'needs Fable / Opus 4.6+ or Sonnet 4.6+; otherwise falls back to high.',
            ],
          ]}
        />
        <p>
          Precedence when an agent or run actually executes: a <strong>per-conversation/run
          override</strong> beats a <strong>per-agent setting</strong>, which beats the{' '}
          <strong>per-model admin default</strong>. The first value that resolves to a known level
          wins; if none is set, the provider&apos;s own default behavior applies (nothing is sent). The
          per-model admin default itself is the lowest-precedence fallback, and its <code>-</code>{' '}
          option means &ldquo;inherit, no default&rdquo;, letting the CLI or model decide.
        </p>
        <Callout variant="info">
          <code>supports_reasoning</code> in the catalog is a separate enrichment flag describing the
          model, unrelated to whether the effort selector is shown. The selector is gated purely by
          provider (<code>claude-code</code>, <code>codex</code>, <code>anthropic</code>), so a
          non-Anthropic provider never shows it regardless of that flag.
        </Callout>

        <h2>The admin model catalog</h2>
        <p>
          The <strong>Models</strong> tab is a sortable, per-row editable list. For each model an admin
          can toggle it enabled, rename its display label, set its tier, set a reasoning-effort default
          (only for the three effort-capable providers above), mark it recommended, edit pricing and
          rate limits, and delete or reset the row.
        </p>
        <DocsTable
          head={['Field', 'Detail']}
          rows={[
            ['Tier', <><code>top</code>, <code>high</code>, <code>mid</code>, <code>budget</code>; a new or custom model defaults to <code>mid</code>.</>],
            ['Ranking', 'set by drag-and-drop; the chat tab writes the global ranking, the browser agent tab writes a per-category ranking.'],
            [
              'Pricing',
              <>USD per 1M tokens, input and output. The credits columns shown alongside are
              derived from price and markup and aren&apos;t directly editable.</>,
            ],
            [
              'Rate limits',
              'global tokens-per-minute and requests-per-minute are editable; per-tenant limit columns exist but stay hidden while the platform runs a global rate-limit strategy.',
            ],
            [
              'Custom models',
              <>added via a dialog: pick the provider, set a model id (required), optional display
              name, tier, pricing, and global rate limits. A custom model is appended to the end of
              the ranking.</>,
            ],
          ]}
        />
        <p>
          <strong>Reset</strong> reverts an admin override back to the synced catalog value and is only
          offered for non-custom rows that actually have an override; <strong>Reset all</strong> does
          the same across the board. Deleting a regular (non-custom) model simply disables it; deleting
          a custom model removes it entirely, since it has no underlying catalog row to fall back to.
        </p>
        <p>
          Additional fields ride along per model, mostly populated by the catalog sync: context window,
          max output tokens, and support flags for tools, vision, prompt caching, reasoning, computer
          use, response schema, and web search, plus its mode, batch and cache pricing, price floors,
          and release/deprecation dates.
        </p>

        <h2>Model categories</h2>
        <p>
          The Models panel has one pill tab per category, and a model can be enabled in one category
          while disabled in another:
        </p>
        <DocsTable
          head={['Category', 'Accepts', 'Ranking scope']}
          rows={[
            ['chat', 'chat-capable models (mode unset or chat)', 'the legacy global list; this is also what other parts of the picker read.'],
            ['browser_agent', 'chat-capable models', 'a per-category sidecar, independent of chat.'],
          ]}
        />
        <p>
          Bridge (CLI) providers are hidden from the <code>browser_agent</code> tab, since re-ranking
          or disabling them there would have no runtime effect; they still appear on the{' '}
          <code>chat</code> tab because full CLI sessions do serve chat.
        </p>
        <p>
          Generation models (image, video, sound, speech, music) are not listed here. A ranking has
          nothing to say about them: the caller names its model, a video model is no substitute for a
          voice one, and the price is per image or per second rather than per token. What decides
          whether a generation model is available, and at what price, is the platform credential it
          is published against, under Settings &gt; Platform Credentials.
        </p>

        <h2>Model execution links (cloud only)</h2>
        <p>
          An execution link decouples <strong>what you&apos;re billed for</strong> from{' '}
          <strong>what actually runs</strong>. It maps a billed <code>(provider, model)</code> pair to a
          different execution provider/model, which can be a CLI bridge or another regular API
          provider. The billed identity is re-stamped onto the response, so credit consumption still
          charges the billed price even though a different model actually did the work.
        </p>
        <p>
          Leaving the execution model blank means &ldquo;reuse the billed model id verbatim&rdquo;, shown
          in the UI as <em>same as billed</em>.
        </p>
        <p>A link is scoped to where it applies:</p>
        <DocsTable
          head={['Scope', 'Applies to']}
          rows={[
            ['ALL', 'the wildcard default, used when no more specific scope matches.'],
            ['CHAT', 'chat conversations (CONVERSATION is an alias of this).'],
            ['WORKFLOW', 'workflow node executions: the agent node, and the classify and guardrail nodes.'],
            ['WEBHOOK', 'webhook-triggered runs.'],
            ['WIDGET', 'embedded widget conversations.'],
            ['SCHEDULE', 'schedule-triggered runs.'],
            ['TASK', 'task executions.'],
            ['TASK_REVIEW', 'task review executions.'],
          ]}
        />
        <p>
          Resolution checks the exact surface first, then falls back to the <code>ALL</code> row.
          Guardrail and Classify nodes resolve as <code>WORKFLOW</code>, like the agent node they sit
          next to. The browser agent, avatar generation and single JSON completions carry no surface
          at all, and a sub-agent run carries one (<code>SUB_AGENT</code>) that matches none, so in
          every case only an <code>ALL</code> row reaches them. Disabling a surface-scoped
          row does not park that surface on the billed model either: it reverts to the{' '}
          <code>ALL</code> route when one exists. At most one link exists per billed pair and scope; the surface picker only offers
          scopes not already linked for that pair, so you can stack <code>ALL</code> plus any subset of
          specific surfaces, but never the same surface twice. Links can be individually enabled,
          disabled, or deleted.
        </p>
        <p>
          The <strong>Models</strong> tab carries a shortcut for the most common link. A model whose
          provider has a CLI counterpart that actually routes that exact model id (Anthropic to Claude
          Code, OpenAI to Codex, Google to Gemini CLI, and Mistral to Mistral Vibe, though that last
          one matches nothing in the standard catalog because the Vibe CLI names its models with
          local aliases) shows a small route button: one click creates
          the link to that CLI, on the same model id, scoped <code>ALL</code>. Once a model is routed,
          the button becomes a badge carrying the execution target&apos;s icon (its name is in the
          tooltip) and opens a surface picker.
          Everything it writes is an ordinary link, editable from the Execution Links tab like any
          other. A model whose CLI counterpart does not route it gets no route button, but it still
          shows the badge once it is linked from the Execution Links tab, so the Models tab answers
          &ldquo;is this model routed, and where&rdquo; for every model, not only the CLI-capable ones.
        </p>
        <p>
          The picker mirrors how a route is resolved rather than showing eight independent switches.
          While <code>All surfaces</code> is on it routes every surface, so the others are listed as
          covered by it and are not clickable: a surface-scoped row could only send that surface
          somewhere else, never switch it off. A surface already overridden onto another target is
          frozen there too, and stays editable from the Execution Links tab. Turning <code>All surfaces</code> off
          disables that row (it is not deleted, so the badge and this picker stay) and the surfaces
          become individually routable.
        </p>
        <p>
          <code>All surfaces</code> also covers the callers that carry no surface, and each lands on
          a model of its own choosing: chat compaction summarises with the platform summariser
          (unless a conversation or agent overrides it), avatar generation uses the tenant&apos;s
          default model, and the browser agent uses the pair configured on it. Where the model they land on is the one
          you routed to a CLI, the CLI cannot serve it: a single JSON completion fails outright,
          avatar generation quietly switches to the platform utility model (and fails only if that
          one is routed to a CLI too), and the browser agent keeps the billed model. So the rows to
          think twice about are the platform summariser model and your tenant default.
        </p>
        <p>
          The button turns amber when that CLI <strong>cannot run</strong> on the bridge host, which
          means it is absent OR present but logged out. On an already-linked model it stays neutral
          while nothing reaches that CLI (the model is routed elsewhere, or its routing is switched
          off), since an unusable CLI cannot break what is not sent to it; on a model with no link
          yet it is amber precisely because the click would send it there. That is worth heeding
          before clicking: a
          linked run falls back to the billed provider only when the bridge transport is entirely
          unwired, so with the bridge up and the CLI unusable everything routed to it fails until the
          CLI is fixed or the routing is changed.
        </p>
        <p>
          Installed and logged in is not the only precondition, and it is the only one the badge can
          see. Each CLI also has its own <strong>access policy</strong> on that CLI&apos;s tab, and it
          ships as <strong>admin-only</strong>: an admin&apos;s own runs pass, so the person reading
          this panel is the least likely to notice that every other user&apos;s run of that model is
          denied with a typed error. On a shared model an <code>ALL</code>-scoped link makes that
          everyone else&apos;s problem, and the badge stays neutral throughout. The third
          precondition is the bridge transport being wired at all, and that is the one case that
          degrades quietly: there the link is dropped and the billed model runs on its own provider.
        </p>
        <Callout variant="info">
          Execution Links is a cloud-only tab and feature. On a self-hosted install this tab
          doesn&apos;t appear, and neither does the route button on the Models tab.
        </Callout>

        <h2>Bridge availability filtering</h2>
        <p>
          Before a CLI bridge provider is offered anywhere a model is picked, it&apos;s checked against
          the bridge host: it&apos;s kept only when the bridge reports the CLI both{' '}
          <strong>installed and authenticated</strong>. Installed-but-not-authenticated is hidden,
          since it would fail at run time asking you to log in.
        </p>
        <p>
          For the user-facing picker this check is <strong>strict</strong>: if availability can&apos;t be
          verified at all (the bridge is unreachable or its status can&apos;t be read), every bridge
          provider is dropped rather than guessed at. Availability itself is cached briefly and
          refreshed periodically. Regular API providers are never affected by this filter.
        </p>

        <h2>Self-hosted (CE): cloud vs BYOK, and the catalog bundle</h2>
        <p>
          On a self-hosted install, the API Keys tab shows a source toggle: <strong>CLOUD</strong> uses
          the linked cloud account&apos;s default models (this requires an active cloud link), and{' '}
          <strong>BYOK</strong> uses the admin&apos;s own API keys. Switching source clears the model cache
          so the picker immediately reflects the new source; choosing CLOUD without a link surfaces a
          &ldquo;link required&rdquo; message.
        </p>
        <p>
          The model catalog itself arrives as a signed bundle from cloud: cloud is the single source of
          truth and builds a signed, versioned bundle, and a linked CE install syncs it automatically
          on a schedule (every 15 minutes) and on startup, verifying the signature offline before
          merging it in. The merge preserves anything an admin has already edited locally and any
          locally added custom models. An <strong>Update model catalog bundle</strong> control lets an
          admin trigger a sync on demand; on an unlinked install it instead prompts to connect to
          cloud first, since the sync itself requires the link.
        </p>
        <Callout variant="warn">
          Two providers are blocked on CE and don&apos;t appear in the provider list at all:{' '}
          <strong>OpenRouter</strong> and <strong>Cohere</strong>. Cloud keeps every provider; only
          self-hosted installs have this restriction.
        </Callout>
        <p>
          <strong>DeepSeek</strong> is handled differently: it isn&apos;t blocked, but it&apos;s not
          shipped as a default platform provider on CE, so a fresh install is <strong>off by
          default</strong> and exposes no DeepSeek key or model out of the box. An admin can still opt
          in, either with the <code>DEEPSEEK_ENABLED</code> flag or by adding their own DeepSeek key
          via the built-in BYOK credential. On cloud, DeepSeek is available as usual.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">Temperature, tool scope, and credit budgets per agent.</Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">Where agent nodes sit in the graph.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Running LiveContext yourself, and what cloud-linking unlocks.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
