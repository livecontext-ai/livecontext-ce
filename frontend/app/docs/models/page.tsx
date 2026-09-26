import { Bot, Server, Sparkles, Wallet } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Models & providers',
  description:
    'Where LiveContext models come from: platform API keys, your own provider key, CLI bridges, reasoning effort, the admin model catalog, and the self-hosted cloud relay.',
  path: '/docs/models',
});

export default function ModelsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Models & providers"
        lead="Every model that powers chat, agents, and workflow agent nodes is configured in one settings page. This page covers where models come from, running turns on your own provider key, reasoning effort, the admin model catalog, and what changes on a self-hosted install."
      />

      <DocsProse>
        <h2>Where models are configured</h2>
        <p>
          Models live in <strong>Settings &rsaquo; AI Providers</strong>. What you see there depends on
          your role and edition:
        </p>
        <DocsTable
          caption="What the AI Providers page shows, by role"
          head={['Who', 'What the page shows']}
          rowHeaders
          rows={[
            [
              'Admin',
              <>
                A row of tabs: <strong>API Keys</strong>, one tab per CLI bridge (<strong>Claude Code</strong>,{' '}
                <strong>Codex</strong>, <strong>Gemini CLI</strong>, <strong>Mistral Vibe</strong>),{' '}
                <strong>Models</strong>, and on the cloud also <strong>Execution links</strong> and{' '}
                <strong>Your keys</strong>.
              </>,
            ],
            ['Non-admin, cloud', <>Only the <strong>Your keys</strong> panel, to run your own turns on your own provider key.</>],
            ['Non-admin, self-hosted', 'An unauthorized notice. Only the administrator configures AI providers.'],
          ]}
        />
        <p>
          The <strong>Models</strong> tab is the single source of truth for every chat and agent model
          the platform exposes. Generation models (image, video, sound, speech, music) are handled
          separately, see <a href="/studio">Studio</a>.
        </p>

        <h2>Platform API-key providers</h2>
        <p>
          On the <strong>API Keys</strong> tab an admin saves one key per provider. Its models then
          become available wherever a model is picked:
        </p>
        <DocsTable
          caption="Platform API-key providers and their models"
          head={['Provider', 'Models']}
          rows={[
            ['Anthropic', 'Claude'],
            ['OpenAI', 'GPT'],
            ['Google', 'Gemini'],
            ['Mistral AI', 'Mistral'],
            ['DeepSeek', 'DeepSeek'],
            ['xAI', 'Grok'],
            ['Perplexity', 'Sonar'],
            ['Cohere', 'Command'],
            ['Z.AI', 'GLM'],
            ['OpenRouter', 'multi-provider aggregator'],
            ['Qwen (Alibaba)', 'Qwen'],
            ['Moonshot (Kimi)', 'Kimi'],
            ['MiniMax', 'MiniMax'],
            ['TypeSafe (Jev)', 'Decision model for Classify nodes only, never offered in chat or agent pickers'],
          ]}
        />
        <p>
          A key saved here takes priority over the server&apos;s environment configuration; with no saved
          key, the server falls back to its environment. Saving a key refreshes the model list right
          away.
        </p>

        <h2>Run on your own provider key</h2>
        <Callout title="Cloud only, from the Pro plan">
          <p>
            The <strong>Your keys</strong> tab exists on the cloud only. Below the Pro plan your saved
            keys stay read-only and your agents keep running on the LiveContext key, with an
            upgrade prompt. On a self-hosted install there is no plan gate: the
            admin&apos;s keys on the <strong>API Keys</strong> tab are already your own.
          </p>
        </Callout>
        <p>
          In <strong>Your keys</strong>, paste a key for a provider and press <strong>Save key</strong>. The
          provider checks the key when you save it, and a rejected key shows the provider&apos;s reason.
          Then turn on <strong>Use my key</strong> for that provider. Each row states its route:{' '}
          <strong>Runs on your key</strong>, <strong>Runs on the LiveContext key</strong>, or{' '}
          <strong>Runs on the LiveContext key (yours is saved)</strong>. You can switch back at any time,
          and your key stays saved. OpenRouter and Cohere are not offered here.
        </p>
        <p>
          On your key, the provider bills you the tokens directly. LiveContext charges a flat fee per
          agent turn, by the model&apos;s price band, and never more than the same turn would have cost
          on the LiveContext key. These turns appear as <strong>Your key</strong> in your credit history.
          Press <strong>See the price per turn</strong> in the panel to see the current fees.
        </p>
        <p>
          The fee for each price band is listed under{' '}
          <a href="/billing#own-key-fee">Own-key fee in Billing</a>.
        </p>

        <h2>CLI bridge providers</h2>
        <p>
          The four bridge providers run a coding-agent CLI on a bridge host instead of calling an API
          directly: <code>claude-code</code>, <code>codex</code>, <code>gemini-cli</code>, and{' '}
          <code>mistral-vibe</code>. Claude Code and Codex let you use a Claude or ChatGPT subscription
          instead of an API key, Gemini CLI logs in with a Google account, and Mistral Vibe uses a
          Mistral API key set on the bridge host. Each bridge tab has a setup panel (install the CLI,
          log in, start the bridge, then <strong>Verify Connection</strong>) and an access panel titled{' '}
          <strong>Who can use this bridge</strong>.
        </p>
        <DocsTable
          caption="Bridge access modes"
          head={['Access mode', 'Who can dispatch through the bridge']}
          rowHeaders
          rows={[
            ['Disabled', 'No one, not even the admin.'],
            ['Admin only', 'Only users with the ADMIN role. This is the default.'],
            ['Allowlist', <>Only the users you add with <strong>Grant access</strong>.</>],
            ['All users', 'Every user of this instance. Pair it with a per-user daily quota.'],
          ]}
        />
        <p>
          <strong>Max requests per user per day</strong> caps each user (leave it empty for unlimited),
          and <strong>Usage today</strong> lists the day&apos;s activity. Because the default is{' '}
          <strong>Admin only</strong>, an admin testing a bridge model sees it work while every other
          user is refused until the mode changes.
        </p>
        <p>
          An API model is usable the moment its key is saved. A bridge model also needs the CLI to be{' '}
          installed and logged in on the bridge host. Before a bridge provider is offered
          in a model picker, the bridge is asked for its status (the answer is cached for 60 seconds). A
          CLI that is missing or logged out is hidden, and if the bridge cannot be reached at all, every
          bridge provider is hidden rather than guessed at. Regular API providers are never affected.
        </p>

        <h2>Reasoning effort</h2>
        <p>Reasoning effort is one dial with six levels, from lightest to most thorough:</p>
        <p>
          <code>minimal</code>, <code>low</code>, <code>medium</code>, <code>high</code>,{' '}
          <code>xhigh</code>, <code>max</code>.
        </p>
        <p>
          Only three providers honor it: <code>claude-code</code>, <code>codex</code>, and the direct{' '}
          <code>anthropic</code> API. Gemini CLI and Mistral Vibe expose no usable effort control, so the
          selector is not shown for them, nor for any other provider. Each of the three maps the level to
          its own setting:
        </p>
        <DocsTable
          caption="How each provider maps reasoning effort"
          head={['Provider', 'Native setting', 'How levels map']}
          rows={[
            [
              'Claude Code',
              <code key="cc">CLAUDE_CODE_EFFORT_LEVEL</code>,
              <>accepts low to max; <code>minimal</code> becomes low.</>,
            ],
            [
              'Codex',
              <code key="cx">-c model_reasoning_effort=&lt;level&gt;</code>,
              <>
                accepts minimal to xhigh, so <code>max</code> becomes xhigh. <code>xhigh</code> and{' '}
                <code>max</code> need a <code>codex-max</code> model and drop to high on other models.
              </>,
            ],
            ['Anthropic (API)', <code key="an">output_config.effort</code>, 'depends on the model, see below.'],
          ]}
        />
        <DocsTable
          caption="Reasoning effort rules for Anthropic models"
          head={['Anthropic case', 'Rule']}
          rows={[
            ['Effort supported at all', 'Fable / Mythos, Opus 4.5 and above, or Sonnet 4.6 and above. Haiku (through 4.5) has no effort control.'],
            ['minimal', 'always becomes low; the API has no minimal level.'],
            ['xhigh', 'needs Fable / Opus 4.7+ or Sonnet 5+; otherwise high.'],
            ['max', 'needs Fable / Opus 4.6+ or Sonnet 4.6+; otherwise high.'],
          ]}
        />
        <p>
          Precedence when a run executes: a <strong>per-conversation or per-run override</strong> beats
          the <strong>per-agent setting</strong>, which beats the <strong>per-model admin default</strong>{' '}
          (the <strong>Effort</strong> column of the Models tab). If none is set, nothing is sent and the
          provider&apos;s own default applies. In the Effort column, <code>-</code> means &ldquo;no
          default, let the model decide&rdquo;.
        </p>

        <h2>Vision and attachments</h2>
        <p>
          Images and files you attach, and images returned by tools (for example a file an agent opens),
          are sent inline to the model, and a vision-capable model can see the images. Inline content has a size cap per item:
        </p>
        <DocsTable
          caption="Inline size cap per attachment type"
          head={['Attachment', 'Inline cap']}
          rowHeaders
          rows={[
            ['Image', '3.6 MB'],
            ['Other binary or PDF', '256 KB'],
          ]}
        />
        <p>
          Past the cap, the model receives the file&apos;s extracted text when there is some, otherwise a
          placeholder naming the file: it knows the file exists but cannot read its contents. Tool-result
          images reach the model on both API providers and CLI bridges.
        </p>

        <h2>The admin model catalog</h2>
        <p>
          The <strong>Models</strong> tab (<strong>Model Configuration</strong>) is a sortable, per-row
          editable list. Its columns include <strong>Provider</strong>, <strong>Tier</strong>,{' '}
          <strong>Effort</strong>, the <strong>Recommended</strong> star, <strong>Price ($/1M)</strong>{' '}
          (USD per 1M input and output tokens), and global rate limits (<strong>TPM global</strong>,{' '}
          <strong>RPM global</strong>).
        </p>
        <DocsTable
          caption="Admin model catalog controls"
          head={['Control', 'What it does']}
          rowHeaders
          rows={[
            ['Enabled toggle', 'Offers or withdraws the model everywhere a model is picked.'],
            ['Display name', 'Click the name to rename it.'],
            ['Tier', <><code>top</code>, <code>high</code>, <code>mid</code>, or <code>budget</code>. A new or custom model defaults to <code>mid</code>.</>],
            ['Ranking', 'Drag to reorder. Each category tab keeps its own ranking.'],
            ['Provider on', 'Switches a whole provider off without touching the setting of each model, so switching it back on restores your selection.'],
            ['Filters and search', <><strong>State</strong>, provider, and <strong>Tier</strong> filters, plus <strong>Search a model</strong>.</>],
            ['Bulk actions', <>Select rows, then <strong>Enable</strong>, <strong>Disable</strong>, or <strong>Set tier</strong>.</>],
            ['Not configured', 'A key icon on a model whose provider has no key yet. It is listed so you can rank and price it, but it cannot run.'],
            ['Add Model', <>A custom model: <strong>Provider</strong> and <strong>Model ID</strong> (required), an optional <strong>Display Name</strong>, tier, price, and rate limits. It is added at the end of the ranking.</>],
            ['Reset / Reset All', 'Reverts admin changes to the catalog value. Offered only on non-custom rows that were changed.'],
          ]}
        />
        <p>
          Deleting a regular model disables it. Deleting a custom model removes it, since there is no
          catalog row to fall back to. Per-tenant rate-limit columns exist but stay hidden.
        </p>

        <h3>When a model is disabled</h3>
        <p>
          Disabling a model does not break the agents, workflow nodes, and chats that already use it. On
          the <strong>Chat / Agent</strong> tab a disabled model shows a <strong>Replaced by</strong>{' '}
          selector: while the model stays disabled, those runs use the chosen replacement instead (and are
          billed for it). <strong>Platform default</strong> means the model the platform uses when none is
          chosen.
        </p>

        <h3>Free-plan models (cloud)</h3>
        <p>
          On the cloud each row has a <strong>Free</strong> chip. It decides whether a Free-plan account
          may spend its monthly credits on chat and agent turns with that model. It is off by default: a
          Free account then needs a top-up to run the model. If no model on a tab is open to the Free
          plan, the tab shows a warning, because Free accounts are then refused for that kind of turn.
        </p>

        <h3>What ships to self-hosted installs (cloud)</h3>
        <p>
          The cloud catalog also carries a <strong>CE: auto</strong> / <strong>CE: on</strong> /{' '}
          <strong>CE: off</strong> chip per row. It decides what the model catalog bundle ships to
          self-hosted installs, independently of the enabled toggle: auto follows it, on ships the model
          enabled, off ships it disabled.
        </p>

        <h2>Model categories</h2>
        <p>The Models tab has one pill tab per category, and a model can be enabled in one and disabled in the other:</p>
        <DocsTable
          caption="Model categories and what uses them"
          head={['Category', 'Used by']}
          rowHeaders
          rows={[
            ['Chat / Agent', 'Chat, agents, and every other consumer of the global catalog. Its ranking is the default order.'],
            ['Browser Agent', 'Browser Agent runs only. Disabling a model here removes it from those runs.'],
          ]}
        />
        <p>
          Bridge (CLI) providers are hidden from the <strong>Browser Agent</strong> tab, where ranking or
          disabling them would have no effect. They still appear on <strong>Chat / Agent</strong>.
        </p>

        <h2>Model execution links</h2>
        <Callout title="Cloud only">
          <p>The <strong>Execution links</strong> tab and the route button on the Models tab do not exist on a self-hosted install.</p>
        </Callout>
        <p>
          An execution link separates what you are billed for from{' '}
          what actually runs. It maps a billed provider and model to a different
          execution provider and model, a CLI bridge or another API provider. Credits are still charged
          at the billed model&apos;s price. Leaving the execution model blank reuses the billed model id,
          shown as <em>same as billed</em>.
        </p>
        <DocsTable
          caption="Model execution link scopes"
          head={['Scope', 'Applies to']}
          rows={[
            ['ALL', 'The default, used when no more specific scope matches.'],
            ['CHAT', 'Chat conversations.'],
            ['WORKFLOW', 'Workflow agent, Classify, and Guardrail nodes.'],
            ['WEBHOOK', 'Webhook-triggered runs.'],
            ['WIDGET', 'Embedded widget conversations.'],
            ['SCHEDULE', 'Schedule-triggered runs.'],
            ['TASK', 'Task executions.'],
            ['TASK_REVIEW', 'Task review executions.'],
          ]}
        />
        <p>
          Resolution checks the exact scope first, then falls back to <code>ALL</code>. Sub-agent runs,
          the browser agent, and other callers without a scope are reached only by an <code>ALL</code>{' '}
          link. There is at most one link per billed model and scope, and each link can be enabled,
          disabled, or deleted.
        </p>
        <p>
          On the Models tab, a model whose exact id is also served by a CLI (Anthropic to Claude Code,
          OpenAI to Codex, Google to Gemini CLI) has a route button that creates an <code>ALL</code> link
          to that CLI in one click. Once routed, the button becomes a badge that opens a surface picker.
          The button turns amber when that CLI cannot run on the bridge host (missing or logged out): runs
          routed to it would fail until the CLI is fixed or the link is changed. Remember the bridge access
          policy too: with the default <strong>Admin only</strong> mode, other users&apos; runs of a routed
          model are refused.
        </p>

        <h2>Self-hosted (CE)</h2>
        <h3>LLM source: Cloud or API keys</h3>
        <p>
          On a self-hosted install the <strong>API Keys</strong> tab starts with an{' '}
          <strong>LLM source</strong> switch:
        </p>
        <DocsTable
          caption="LLM source options on a self-hosted install"
          head={['Source', 'Behavior']}
          rowHeaders
          rows={[
            [
              'Cloud',
              'API model calls use your linked LiveContext Cloud account. Only the model completions are relayed to the cloud and billed in credits on that cloud account. Tools and traces still run locally. Requires a linked cloud account.',
            ],
            ['API keys', 'API model calls use the keys configured on this instance.'],
          ]}
        />
        <p>
          CLI bridges have no such switch: they always run on your own bridge host. See{' '}
          <a href="/self-host">Self-hosting</a> to link a cloud account.
        </p>

        <h3>The model catalog on a self-hosted install</h3>
        <p>
          Each release of the Community Edition ships with the cloud&apos;s signed model catalog of that
          day, applied when the install starts. So a never-linked install still gets the models of its
          release. A linked install also syncs the latest signed catalog on startup and about every 15
          minutes, and the <strong>Model catalog bundle</strong> control (<strong>Update bundle</strong>)
          syncs on demand. The signature is verified before anything is merged, and your own edits and
          custom models are kept.
        </p>
        <Callout variant="warn" title="Providers not available on self-hosted">
          <p>
            OpenRouter and Cohere are blocked on self-hosted installs and
            do not appear in the provider list. The cloud offers every provider.
          </p>
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common model problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'A model shows a key icon and cannot run',
              'Its provider has no key yet (Not configured).',
              'Save a key for that provider on the API Keys tab.',
            ],
            [
              'A bridge model works for the admin but other users are refused',
              'The bridge access mode is Admin only, the default.',
              'In Who can use this bridge, choose Allowlist or All users, with a daily quota if needed.',
            ],
            [
              'A bridge provider is missing from the model picker',
              'Its CLI is not installed or is logged out on the bridge host, or the bridge cannot be reached. The status is cached for 60 seconds.',
              'Fix the CLI, press Verify Connection on its tab, and wait a minute.',
            ],
            [
              'Your key is saved but turns still run on the LiveContext key (cloud)',
              'Use my key is off for that provider, or your plan is below Pro, where saved keys stay read-only.',
              'Turn on Use my key, or upgrade to Pro.',
            ],
            [
              'The model says it cannot read an attached PDF or file',
              'The file is over the inline cap (256 KB for a PDF or other binary), so the model only got its extracted text or a placeholder.',
              'Attach a smaller file, or send the part that matters as text.',
            ],
            [
              'Switching the LLM source to Cloud fails with "Link a Cloud account before switching to Cloud." (self-hosted)',
              'The install is not linked to a cloud account.',
              <span key="f">
                Link it in Settings &gt; Cloud first, see <a href="/self-host">Self-hosting</a>.
              </span>,
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Bot} title="Agents" href="/agents">Model, reasoning effort, tools, and credit budgets per agent.</Card>
          <Card icon={Sparkles} title="Studio" href="/studio">Generate images, video, and audio with generation models.</Card>
          <Card icon={Wallet} title="Billing" href="/billing">How credits and plans work on the cloud.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Running LiveContext yourself, and what cloud-linking unlocks.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
