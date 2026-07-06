import { Bot, Server, Store } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Plans, credits & billing',
  description:
    'How LiveContext Cloud bills usage: the Free/Starter/Pro/Team/Enterprise plans, the two-bucket credit wallet (subscription + pay-as-you-go) and its drain order, what actually consumes credits, subscription management, owner-pays team billing, BYOK, reward codes, delinquency, agent credit budgets, and the CE unlimited mode.',
  path: '/docs/billing',
});

export default function BillingPage() {
  return (
    <>
      <DocsHero
        eyebrow="Reference"
        title="Plans, credits & billing"
        lead="LiveContext Cloud bills usage in credits drawn from a two-bucket wallet: a monthly subscription bucket plus a persistent pay-as-you-go bucket. This page covers the plans, what actually consumes credits, how the wallet drains, and how billing is managed."
      />

      <DocsProse>
        <Callout variant="info">
          Everything on this page is <strong>Cloud-only</strong>. A self-hosted Community Edition (CE)
          install runs in unlimited mode: it still records every debit for observability, but the
          balance never blocks anything. See the CE section below.
        </Callout>

        <h2>Plans</h2>
        <p>
          A workspace picks one plan: <strong>Free</strong>, <strong>Starter</strong>, <strong>Pro</strong>,{' '}
          <strong>Team</strong>, or <strong>Enterprise</strong>. On paid plans you also pick a monthly
          credit tier via a slider (see below). Enterprise is not self-serve: selecting it deep-links to
          a contact form instead of checkout, on both Cloud and CE.
        </p>
        <DocsTable
          head={['Plan', 'Seats / workspaces', 'Highlights']}
          rows={[
            ['Free', '1 user, 1 workspace', 'Community support.'],
            ['Starter', '1 user, 1 workspace', 'Versioning, API access, basic analytics, email support.'],
            [
              'Pro',
              '1 user, 3 workspaces',
              'Priority execution, execution search, detailed analytics, priority support.',
            ],
            [
              'Team',
              'up to 25 users, 10 workspaces',
              'SSO, RBAC, audit logs, shared templates, centralized (owner-pays) billing, team analytics, SLA support.',
            ],
            [
              'Enterprise',
              'unlimited users/workspaces',
              'Custom credits, dedicated instance, compliance, overage protection, advanced analytics, account manager, onboarding. Contact-sales only.',
            ],
          ]}
        />
        <p>
          The authoritative catalog of plan codes, prices, and included quotas is served live from the
          plan table; the table above shows the durable, code-verified facts. One quota is pinned in
          code: <strong>Team&apos;s member cap is 25</strong>. Other displayed numbers, such as
          per-plan storage size or concurrency, are labels on the pricing page and may be adjusted
          without a code change, so treat the live pricing page as the source of truth for exact
          figures at any given moment.
        </p>
        <Callout variant="warn">
          A yearly billing cycle discounts the <strong>base plan price only</strong>, not the credit
          pack: yearly total = the discounted base plus the full, undiscounted credit-pack cost for the
          chosen tier. Monthly billing is simply base plus credit cost, with no discount.
        </Callout>

        <h2>Credit tiers</h2>
        <p>
          Paid plans choose a monthly credit amount from ten slider positions, each adding a fixed
          amount to the plan&apos;s base price. The cost curve is <strong>degressive</strong>: the
          per-1,000-credit price drops as the tier rises, and <strong>Team</strong> has its own,
          richer cost curve than <strong>Starter</strong>/<strong>Pro</strong> at the same tier.
        </p>
        <Callout variant="warn">
          <strong>Starter caps at the fifth tier.</strong> Requesting a higher tier on Starter is
          rejected; upgrade to Pro or Team to reach the larger tiers. The two largest tiers are also
          hidden by default in the picker UI and only surface once your subscription already sits on
          one of them, or via an explicit full-tier view.
        </Callout>

        <h2>What consumes credits</h2>
        <p>
          A single credit-consumption endpoint dispatches by source type. Some sources are billed at a
          flat rate, others are metered by actual usage:
        </p>
        <DocsTable
          head={['Source', 'How it is billed']}
          rows={[
            [
              'Workflow node',
              'Flat: one credit per executed node, on both Cloud and CE (CE only tracks it, see below).',
            ],
            ['Web search', 'Flat, one credit per search by default.'],
            ['Web fetch (page extraction)', 'Flat, one credit per fetch by default, tracked separately from web search.'],
            [
              'Image generation',
              'Per image actually returned by the provider, plus a fixed cost; billed on the count the provider returned, not the count requested. A model with no configured pricing refuses the debit rather than guessing.',
            ],
            [
              'LLM calls (chat, agent, classify, guardrail, compaction, browser-agent, CLI/bridge sessions)',
              'Token-metered, see the LLM cost model below. A CLI or bridge session itself reports close to zero billed tokens (the external CLI subscription pays the model provider directly) but still writes a ledger row for the audit trail.',
            ],
            [
              'Marketplace purchase',
              'Flat, the listed credit price; the buyer pays directly (this bypasses owner-pays team billing, see below).',
            ],
            [
              'Platform markup on managed API-tool calls',
              'A per-call markup on top of a platform-provided (managed) credential, so the platform recovers its own cost of hosting that credential. Goes through a reserve, then commit or release lifecycle around the actual call.',
            ],
          ]}
        />

        <h2>The LLM cost model</h2>
        <p>
          A model&apos;s token rates are stored per provider as USD per 1M tokens. The raw cost is
          input tokens times the input rate, plus output tokens times the output rate, plus any fixed
          per-call cost, then multiplied by the <strong>cloud LLM billing multiplier</strong>, the
          platform&apos;s margin lever on top of the raw provider cost.
        </p>
        <p>
          The multiplier does not apply to web-search or image-generation-style providers/models, since
          those are already priced flat or per-unit above. Cached input tokens are billed at
          provider-specific relative rates (cheaper than a fresh input token, since the provider itself
          charges less for them), so the multiplier stays the only margin lever on top of true provider
          cost.
        </p>
        <Callout variant="info">
          The credit list price is fixed at a small fraction of a US cent per credit, and a stored
          credit cost already includes the cloud margin, so the credits a user sees debited are the
          final, billed amount, not a pre-margin number.
        </Callout>

        <h2>The two-bucket wallet</h2>
        <p>
          Every subscription carries two credit buckets. Together they make up the balance a user
          sees:
        </p>
        <DocsTable
          head={['Bucket', 'Behavior']}
          rows={[
            [
              'Subscription (sub)',
              'Granted on plan renewal, wiped and replaced at the next renewal (does not roll over).',
            ],
            [
              'Pay-as-you-go (PAYG)',
              'Persists across renewals; funded by one-time top-ups, referral rewards, and similar grants.',
            ],
          ]}
        />
        <p>
          The balance endpoint returns the total plus the split of each bucket, and a delinquency flag
          (see below).
        </p>
        <p>
          <strong>Drain order for a normal, paid-plan debit:</strong> the subscription bucket is
          consumed first; once it is exhausted, PAYG is drawn. If a debit overshoots and PAYG would go
          negative, PAYG is floored at zero and the overshoot lands back on the sub bucket instead, so
          PAYG never goes negative purely from overshoot. If the sub bucket is already at or below
          zero when a debit starts, PAYG is drained first, then any overshoot lands on sub.
        </p>
        <Callout variant="warn">
          <strong>On the Free plan, the monthly grant only funds workflow-node executions.</strong>{' '}
          Every other kind of spend, chat, agent turns, web search/fetch, image generation, and
          platform markup, draws the PAYG bucket alone on Free. Paid plans and CE are not affected by
          this restriction.
        </Callout>
        <p>
          Grants route to a bucket by their own type: one-time top-ups and referral rewards (and their
          clawbacks) land on PAYG; renewals, admin grants, and refunds land on the sub bucket.
        </p>

        <h2>PAYG one-time top-ups</h2>
        <p>
          Independent of any subscription, a workspace owner can buy a one-time credit top-up in three
          sizes. PAYG credits are deliberately priced above any subscription credit-pack rate, so a
          subscription plus its credit pack always beats buying the same amount via PAYG; PAYG exists
          for occasional overflow, not as the primary way to buy credits.
        </p>
        <p>
          Available top-up tiers are listed with a flag showing whether that tier is actually wired up
          for checkout. Purchasing opens a one-time Stripe checkout, gated to the workspace owner; the
          credits land on the PAYG bucket once the payment webhook confirms it.
        </p>
        <Callout variant="info">
          CE hides the PAYG option entirely, there is no Stripe wiring on a self-hosted install and the
          underlying checkout endpoint is unavailable there.
        </Callout>

        <h2>Managing your subscription</h2>
        <p>
          All billing changes require the caller to be the <strong>owner</strong> of the active
          workspace; a member acting from a shared workspace must switch to their personal workspace to
          manage billing.
        </p>
        <DocsTable
          head={['Action', 'Behavior']}
          rows={[
            [
              'Start a subscription',
              'Choose plan, billing cycle, and credit tier and check out with Stripe; the Free plan is handled without touching Stripe at all.',
            ],
            [
              'Change plan',
              'An upgrade applies immediately with proration; a downgrade, or a cycle change, is scheduled for the end of the current period. Changing plan and cycle (or plan and credit tier) in the same request is blocked, do it in two separate steps.',
            ],
            [
              'Change credit tier',
              'An upgrade applies immediately (full charge, full credits, and the billing anchor resets to now); a downgrade is scheduled for period end; picking the same tier is rejected.',
            ],
            ['Change billing cycle', 'Switch monthly to yearly (applied immediately) or yearly to monthly.'],
            [
              'Cancel / reactivate',
              'Cancellation takes effect at period end and requires a reason; reactivation undoes a pending cancellation before it takes effect.',
            ],
            [
              'View / cancel a scheduled change',
              'A pending downgrade, cycle change, or cancellation can be inspected and cancelled before it takes effect.',
            ],
            [
              'Billing portal',
              'Opens the Stripe customer portal for payment methods and the full, unbounded invoice history.',
            ],
            [
              'Invoices',
              'Up to twelve recent invoices, newest first, each with a hosted view link and a PDF link. Not owner-gated, invoices belong to the user who was actually charged.',
            ],
            [
              'Current status',
              'Plan, billing cadence, current period, whether cancellation is pending, and the active credit quantity/tier.',
            ],
          ]}
        />

        <h2>Usage, history & analytics</h2>
        <p>
          Beyond the raw balance, a workspace can inspect a running summary, a paginated history
          filterable by source type, and analytics broken down by day, source type, provider, or model,
          including the cost of one specific workflow run. Summary, history, and analytics are
          workspace-scoped by default but accept an aggregate-across-workspaces option; per-run cost
          only ever takes the run id.
        </p>
        <p>
          Two pre-flight checks exist so a caller can avoid starting work it cannot pay for: a plain
          &ldquo;is there at least one credit?&rdquo; gate, and a cost-aware check for an upcoming chat
          turn that rejects before the LLM call runs if the estimated cost would exceed the balance
          (and refuses to proceed at all for a model with no known pricing, rather than guessing).
        </p>
        <p>
          History reflects what a user actually took part in, whether as the payer or as the executor,
          so on a Team plan a member sees their own executions in their own history even though the
          debit itself was billed to the workspace owner.
        </p>

        <h2>Owner-pays team billing & member quotas</h2>
        <p>
          On a shared workspace, a billable action taken by any member is redirected to the{' '}
          <strong>workspace billing owner&apos;s wallet</strong>, not the member&apos;s own balance.
          Marketplace purchases and BYOK usage are the two exceptions and always stay scoped to the
          user who triggered them.
        </p>
        <Callout variant="info">
          An owner can cap how much a given member is allowed to spend over a period. A consume that
          would exceed that member&apos;s cap is refused up front with a distinct, actionable outcome
          telling the member to ask an admin to raise their cap, rather than a generic insufficient-credit
          error. A member with no cap configured, or the owner themself, is never capped this way.
        </Callout>

        <h2>BYOK is not billed</h2>
        <p>
          When a user supplies their own upstream API key instead of using a platform-provided one, the
          platform does not deduct any credits for that call, the user pays their provider directly. A
          zero-amount ledger row is still written for the audit trail, attributed to the person who
          executed the call rather than routed through owner-pays.
        </p>

        <h2>Reward codes & referrals</h2>
        <p>
          A promo, referral, or partner code is redeemed through a single endpoint. A promotional
          (free-node) code grants its benefit immediately; a referral code is attributed right away but
          only pays out once the redeemer converts to a paid plan.
        </p>
        <DocsTable
          head={['Outcome', 'Meaning']}
          rows={[
            ['Invalid code', 'The code does not exist.'],
            ['Not redeemable / already redeemed', 'The code is expired, disabled, or this user already used it.'],
            ['Exhausted', 'The code has hit its total redemption cap.'],
            ['Self-referral', 'A user cannot redeem their own referral code.'],
            ['Already paid', 'The redeemer already converted, so there is nothing left to attribute.'],
          ]}
        />
        <p>
          A successful referral rewards <strong>both parties</strong> once the redeemer converts, after
          an anti-refund hold period, and always lands on the PAYG bucket (so a later clawback, if the
          conversion is reversed, only ever touches PAYG credits, never subscription credits). An
          optional per-referrer soft cap can exist; by default it is uncapped, and any over-cap
          redemption is tracked rather than rejected, pending manual approval.
        </p>
        <p>
          A user&apos;s own referral code, progress (redeemed, pending, in hold, and rewarded counts,
          credits earned so far, and any cap), and the current list of active promotional benefits are
          all available to inspect from the billing area.
        </p>
        <p>
          A separate launch-promo mechanism can grant free workflow-node executions to a redeemer,
          time-boxed and capped per account; a claimed free node writes a zero-cost promo row instead of
          debiting the wallet.
        </p>

        <h2>Insufficient credits & delinquency</h2>
        <p>
          A blocking, pre-flight debit (one that can refuse before the underlying work starts) returns{' '}
          <strong>HTTP 402</strong> when the balance cannot cover the cost, along with the failing
          result, so the caller can distinguish a budget-exhausted stop from an actual error. It writes
          a zero-amount, rejected audit row that preserves the provider/model/token context, so the
          rejection itself is auditable.
        </p>
        <Callout variant="warn">
          Some debits cannot be pre-flighted, an LLM call that has already run is billed after the fact
          and is allowed to push the balance negative rather than silently dropping the cost. When the
          total balance is at or below zero (or, on the Free plan, specifically the PAYG bucket is
          negative), the subscription is flagged <strong>delinquent</strong>. A delinquent subscription
          blocks new reservations and workflow starts until a top-up brings both buckets back to a
          non-negative state, which clears the flag automatically. The delinquency flag is part of the
          balance response.
        </Callout>
        <p>CE, running in unlimited mode, is never delinquent.</p>

        <h2>Agent credit budgets</h2>
        <p>
          Separately from the wallet above, an individual agent can carry an optional{' '}
          <code>credit_budget</code>. This is a <strong>different accounting axis</strong>: here, one
          &ldquo;credit&rdquo; means one LLM tool-calling iteration for that agent, not a wallet credit.
          A null budget means unlimited iterations; a number is a hard cap.
        </p>
        <p>
          Budgets are enforced <strong>hierarchically</strong>. When an agent spawns a sub-agent, the
          sub-agent&apos;s entire budget is reserved up front against the parent and every ancestor
          above it; when the sub-agent finishes, its actual consumption settles against that
          reservation and anything unused is refunded back up the chain. An agent&apos;s free budget and
          how much of it is currently reserved for sub-agents are both readable at run time.
        </p>
        <p>
          The default reset mode is cumulative, meaning the budget never automatically resets on its
          own; as a rule of thumb, a budget of at least five times the agent&apos;s configured maximum
          iterations avoids exhausting it before the first turn even completes. A too-low budget is
          flagged with a warning when the agent is created or updated.
        </p>
        <p>
          When a budget is exhausted, the run stops with a dedicated stop reason, and the accompanying
          scope tells you which level actually hit the cap: the tenant as a whole, this specific agent,
          a reservation held on behalf of a sub-agent, or a per-user browser-agent quota (concurrent
          sessions or steps per day).
        </p>

        <h2>Self-hosted (CE): unlimited credits, still tracked</h2>
        <p>
          A self-hosted install runs with credit consumption in <strong>unlimited mode</strong>: every
          debit is still written to the ledger for observability, the reported balance is an
          effectively infinite sentinel, and no debit ever blocks anything. CE tracks usage, it does not
          bill it.
        </p>
        <p>
          CE has no local Stripe subscription of its own. Instead, its governing plan comes from the{' '}
          <a href="/self-host">cloud account it is linked to</a>: the pricing page mirrors the linked
          cloud subscription&apos;s plan, credit tier, and billing cadence, and pricing-page actions on
          CE either open the linked cloud account&apos;s pricing page or, if no cloud account is linked
          yet, route to connecting one first.
        </p>
        <p>
          When CE relays an LLM completion through its linked cloud account, the cloud side settles
          exactly one debit per CE execution, keyed to that execution so a retry or duplicate delivery
          never double-charges it, and, like other post-flight LLM debits, it is allowed to push the
          cloud account&apos;s balance negative since the call already ran.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">Credit budgets, tool scope, and reasoning effort per agent.</Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">Buying and selling automations with credits.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Running CE and what cloud-linking unlocks for billing.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
