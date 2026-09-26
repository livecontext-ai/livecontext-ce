import { Bot, Building2, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Plans & billing',
  description:
    'LiveContext Cloud plans and prices, credit tiers, top-ups, the two-bucket wallet, what consumes credits, own-key fees, storage, referrals, and how the self-hosted edition tracks usage.',
  path: '/docs/billing',
});

export default function BillingPage() {
  return (
    <>
      <DocsHero
        eyebrow="Account & billing"
        title="Plans & billing"
        lead="LiveContext Cloud bills usage in credits drawn from a two-bucket wallet: a monthly subscription bucket plus a pay-as-you-go bucket that never expires. This page covers the plans and their prices, what consumes credits, how the wallet drains, and where you manage it all."
      />

      <DocsProse>
        <Callout variant="info" title="Cloud only">
          Prices, limits, and billing on this page apply to LiveContext Cloud. A
          self-hosted Community Edition (CE) install runs in unlimited mode: it records every debit so
          you can see your usage, but nothing is capped or billed. See{' '}
          <a href="#self-hosted-ce">the CE section</a> below.
        </Callout>

        <h2>Where to find it</h2>
        <DocsTable
          caption="Billing pages and what each is for"
          head={['Page', 'What it is for']}
          rowHeaders
          rows={[
            [<strong key="p">Settings &gt; Pricing</strong>, 'Compare plans, pick a credit tier and a billing cycle, subscribe, or change plan.'],
            [<strong key="b">Settings &gt; Billing</strong>, 'Your subscription, payment methods, and invoices (cloud only).'],
            [<strong key="q">Settings &gt; Quota &amp; Usage</strong>, 'Your balance, top-ups, usage history, and usage analytics.'],
            [<strong key="s">Settings &gt; Storage</strong>, 'How much of your storage allowance is used.'],
            [<strong key="r">Settings &gt; Refer &amp; earn</strong>, 'Your referral code, and where you redeem a code you received.'],
          ]}
        />
        <p>
          Billing changes (subscribe, change plan, top up) are made by the owner of the
          active workspace. A member working in a shared workspace switches to their personal workspace
          to manage their own billing.
        </p>

        <h2>Plans and prices</h2>
        <p>
          There are five plans: <strong>Free</strong>, <strong>Starter</strong>, <strong>Pro</strong>,{' '}
          <strong>Team</strong>, and <strong>Enterprise</strong>. A paid plan&apos;s price is its base
          price plus the price of the monthly credit tier you choose (see{' '}
          <a href="#credit-tiers">Credit tiers</a>). Prices are in US dollars.
        </p>
        <DocsTable
          head={['Plan', 'Base price, monthly billing', 'Base price, yearly billing', 'Credits']}
          caption="Plan base prices per month"
          rowHeaders
          rows={[
            ['Free', '$0', '$0', '1,000 per month'],
            ['Starter', '$10 / month', '$8 / month', '5,000 to 100,000 per month'],
            ['Pro', '$24 / month', '$19 / month', '5,000 and up per month'],
            ['Team', '$49 / month', '$39 / month', '5,000 and up per month'],
            ['Enterprise', 'Contact sales', 'Contact sales', 'Custom'],
          ]}
        />
        <Callout variant="info" title="Yearly billing">
          Yearly billing takes 20% off the base price only. The credit tier is never
          discounted: yearly price = discounted base + the full price of your credit tier.
        </Callout>
        <p>
          Enterprise is not self-serve: choosing it opens a contact form instead of checkout, on both
          the cloud and the Community Edition.
        </p>

        <h3>What each plan includes</h3>
        <DocsTable
          head={['Limit or feature', 'Free', 'Starter', 'Pro', 'Team', 'Enterprise']}
          caption="Plan limits and features"
          rowHeaders
          rows={[
            ['Users', '1', '1', '1', 'Up to 25', '25 to 500, by Enterprise tier'],
            ['Workspaces', '1', '1', '3', '10', 'Unlimited'],
            ['Nodes and integrations', 'Core library (publishing integrations excluded)', 'Publishing integrations included', 'Every node and integration', 'Every node and integration', 'Every node and integration'],
            ['Variables', '3', '25', '100', '500', 'Unlimited'],
            ['Storage', '100 MB', '1 GB', '10 GB', '100 GB', '500 GB to 5 TB, by Enterprise tier'],
            ['Log retention', '7 days', '30 days', '30 days', '90 days', 'Custom'],
            ['Versioning, API access, email alerts, managed integration credentials', 'No', 'Yes', 'Yes', 'Yes', 'Yes'],
            ['Vector search and RAG', 'No', 'No', 'Yes', 'Yes', 'Yes'],
            ['Browser Agent', 'No', 'No', 'Yes', 'Yes', 'Yes'],
            ['Run on your own LLM API keys', 'No', 'No', 'Yes', 'Yes', 'Yes'],
            ['Priority execution, execution search', 'No', 'No', 'Yes', 'Yes', 'Yes'],
            ['SSO, RBAC, audit logs, shared templates, centralized billing', 'No', 'No', 'No', 'Yes', 'Yes'],
            ['Dedicated instance, overage protection, custom onboarding', 'No', 'No', 'No', 'No', 'Yes'],
            ['Support', 'Community', 'Email', 'Priority, 24/7', 'Priority, with SLA', 'Priority with SLA, custom 99.9% SLA, dedicated account manager'],
          ]}
        />
        <p>
          The Pro-gated features (vector search, <a href="/browser-agent">Browser Agent</a>, and your
          own LLM keys) are never gated on a self-hosted install.
        </p>

        <h3>Storage is shared per account</h3>
        <p>
          The storage allowance belongs to the account, not to each workspace. Files stored in every
          workspace you own count against one pool. When the pool is full, every one of those
          workspaces refuses new files, even one that has stored nothing itself. Check usage in{' '}
          <strong>Settings &gt; Storage</strong>, and see <a href="/files">Files &amp; storage</a>.
        </p>

        <h2 id="credit-tiers">Credit tiers</h2>
        <p>
          On a paid plan you choose how many credits you get each month. Each tier adds a fixed amount
          to the base price, and the price per credit drops as the tier rises. Team has its own price
          curve.
        </p>
        <DocsTable
          head={['Credits per month', 'Added to Starter or Pro', 'Added to Team']}
          caption="Monthly price of each credit tier"
          rowHeaders
          rows={[
            ['5,000', '$0', '$0'],
            ['10,000', '$10', '$15'],
            ['25,000', '$22', '$30'],
            ['50,000', '$42', '$55'],
            ['100,000', '$80', '$100'],
            ['250,000', '$185 (not on Starter)', '$230'],
            ['500,000', '$365 (not on Starter)', '$430'],
            ['1,000,000', '$720 (not on Starter)', '$825'],
            ['5,000,000', '$3,500 (not on Starter)', '$4,000'],
            ['10,000,000', '$7,000 (not on Starter)', '$8,000'],
          ]}
        />
        <p>
          For example, Pro with 10,000 credits billed monthly is $24 + $10 = $34 per month; billed
          yearly it is $19 + $10 = $29 per month.
        </p>
        <Callout variant="warn" title="Tier limits">
          Starter stops at 100,000 credits: upgrade to Pro or Team for more. The 5,000,000 and
          10,000,000 tiers are hidden in the picker unless your subscription already uses one of them.
        </Callout>

        <h2>Top-ups (pay as you go)</h2>
        <p>
          Independently of your subscription, the workspace owner can buy a one-time credit top-up from{' '}
          <strong>Settings &gt; Quota &amp; Usage</strong>. Checkout is handled by Stripe, and the credits
          arrive once the payment is confirmed.
        </p>
        <DocsTable
          caption="Top-up packs, prices, and credits"
          head={['Top-up', 'Price', 'Credits']}
          rowHeaders
          rows={[
            ['Small', '$10', '8,000'],
            ['Medium', '$50', '40,000'],
            ['Large', '$100', '80,000'],
          ]}
        />
        <p>
          That is $1.25 per 1,000 credits, deliberately more than any subscription credit tier, so a
          subscription is always the cheaper way to buy credits. Top-ups are meant for occasional
          overflow. Top-up credits never expire.
        </p>

        <h2>What a credit is worth</h2>
        <p>
          One credit is listed at $0.001 (a tenth of a US cent). The credits you see debited are the
          final amount: any platform margin is already included.
        </p>

        <h2>What consumes credits</h2>
        <DocsTable
          caption="What consumes credits and how it is billed"
          head={['Usage', 'How it is billed']}
          rowHeaders
          rows={[
            ['Workflow node', '1 credit per executed node.'],
            ['Web search', '1 credit per search.'],
            ['Web fetch (page extraction)', '1 credit per fetch.'],
            [
              'LLM calls (chat, agents, Classify, Guardrail, context compaction, Browser Agent)',
              'Metered by tokens: see the LLM cost model below.',
            ],
            [
              'LLM calls on your own provider key',
              'A small flat fee per turn instead of the token cost: see Own-key fee below.',
            ],
            [
              'CLI sessions (Claude Code, Codex, Gemini CLI)',
              'Close to zero: your CLI subscription pays the model provider. A usage row is still recorded.',
            ],
            [
              'Integration calls on a platform-provided credential',
              'A per-call markup, reserved before the call and settled or refunded after it. Using your own integration credential adds no markup.',
            ],
            ['Marketplace purchase', 'The listed credit price, paid by the buyer.'],
          ]}
        />

        <h3>The LLM cost model</h3>
        <p>
          Each model has a price per million input and output tokens. The cost of a call is its input
          tokens times the input price, plus its output tokens times the output price, plus any fixed
          per-call cost, then the platform margin (25% of the final price) is added. Cached input tokens
          are billed at the model&apos;s own, cheaper cached rate. The margin does not apply to web search
          or other flat-priced usage.
        </p>

        <h3 id="own-key-fee">Own-key fee</h3>
        <Callout variant="info" title="Pro and above">
          Running chats and agents on your own LLM provider key requires the Pro plan or higher on the
          cloud. You add your keys in <strong>Settings &gt; AI Providers</strong>, under{' '}
          <strong>Your keys</strong>.
        </Callout>
        <p>
          When a turn runs on your own key, your provider bills you for the tokens, and LiveContext
          charges a flat fee per turn based on the model&apos;s price band:
        </p>
        <DocsTable
          caption="Own-key fee per agent turn, by price band"
          head={['Price band', 'Model output price (USD per 1M tokens)', 'Fee per turn (at most)']}
          rowHeaders
          rows={[
            ['Budget', 'under $1.50', '1 credit'],
            ['Mid tier', '$1.50 to under $5', '2 credits'],
            ['High tier', '$5 to under $15', '5 credits'],
            ['Top tier', '$15 and above', '10 credits'],
            ['Unknown (no published price)', '-', '2 credits'],
          ]}
        />
        <p>
          The fee is never more than what the same turn would have cost on the platform&apos;s own key,
          and a turn that used no tokens costs nothing. In a shared workspace the fee is billed to the
          workspace owner, like other usage. In <strong>Quota &amp; Usage</strong>, own-key rows show the
          fee next to an estimate of what your provider charged.
        </p>

        <h2>The two-bucket wallet</h2>
        <p>Every subscription carries two credit buckets, which together make up your balance:</p>
        <DocsTable
          caption="The two credit buckets and how each behaves"
          head={['Bucket', 'Behavior']}
          rowHeaders
          rows={[
            ['Subscription', 'Granted at each renewal and replaced at the next one: unused credits do not roll over.'],
            ['Pay as you go', 'Never expires. Funded by top-ups, referral rewards, and similar grants.'],
          ]}
        />
        <p>
          Drain order on a paid plan: the subscription bucket is used first, then the
          pay-as-you-go bucket. If a debit overshoots, the pay-as-you-go bucket stops at zero and the
          remainder is taken from the subscription bucket, so pay-as-you-go never goes negative through
          overshoot.
        </p>
        <Callout variant="warn" title="Free plan">
          The Free plan grants 1,000 credits a month, in one pool, once your email is verified. Unused
          credits do not carry over. The pool pays for workflow nodes and for chat and agent turns on
          the models opened to the free tier (marked Free in the model menus). A turn on any other
          model, web search and fetch, and platform markup draw only from the pay-as-you-go bucket, so
          without a top-up those are unavailable on Free.
        </Callout>

        <h2>Managing your subscription</h2>
        <DocsTable
          caption="Subscription actions and what they do"
          head={['Action', 'Behavior']}
          rowHeaders
          rows={[
            ['Subscribe', 'Choose a plan, a billing cycle, and a credit tier, then check out with Stripe. The Free plan needs no checkout.'],
            [
              'Change plan',
              'An upgrade applies immediately, prorated. A downgrade or a cycle change takes effect at the end of the current period. Change the plan and the cycle (or the plan and the credit tier) in two separate steps.',
            ],
            [
              'Change credit tier',
              'A higher tier applies immediately (charged in full, credits granted in full, and the billing date resets to today). A lower tier takes effect at the end of the period.',
            ],
            ['Change billing cycle', 'Monthly to yearly applies immediately; yearly to monthly takes effect at the end of the period.'],
            ['Cancel or reactivate', 'Cancellation takes effect at the end of the period and asks for a reason. Reactivating undoes a pending cancellation.'],
            ['Scheduled changes', 'A pending downgrade, cycle change, or cancellation can be reviewed and cancelled before it takes effect.'],
            ['Manage in Stripe', 'Opens the Stripe customer portal: payment methods and the full invoice history.'],
            ['Invoices', 'The twelve most recent invoices, each with a view link and a PDF.'],
          ]}
        />

        <h2>Usage and history</h2>
        <p>
          <strong>Settings &gt; Quota &amp; Usage</strong> shows your balance (split between the
          subscription and pay-as-you-go buckets), a usage breakdown for the last 30 days, a usage
          history you can filter by type, and usage analytics by day, type, provider, or model. The{' '}
          <strong>Workspace</strong> selector shows one workspace or <strong>All workspaces</strong>.
        </p>
        <p>
          History shows what you took part in, as the payer or as the person who ran it. On a Team
          plan, a member sees their own runs in their history even though the owner was billed.
        </p>
        <p>
          Before an expensive chat turn starts, LiveContext estimates its cost and refuses it up front
          if your balance cannot cover it, rather than failing halfway.
        </p>

        <h2>Team billing: the owner pays</h2>
        <p>
          In a shared workspace, usage by any member is billed to the workspace owner&apos;s
          wallet, not to the member. Marketplace purchases are the exception: the buyer always
          pays.
        </p>
        <Callout variant="info">
          The owner can cap what each member may spend (see{' '}
          <a href="/organizations">Organizations &amp; roles</a>). When a member reaches their cap, the
          action is refused with a message asking them to have an admin raise it, rather than a generic
          &ldquo;insufficient credits&rdquo; error. The owner is never capped this way.
        </Callout>

        <h2>Refer &amp; earn</h2>
        <p>
          <strong>Settings &gt; Refer &amp; earn</strong> holds your referral code, a share link, your
          progress (pending and rewarded referrals, credits earned), and a <strong>Redeem a code</strong>{' '}
          box for a referral or promo code you received.
        </p>
        <ul>
          <li>
            When someone who used your code subscribes, you both get 8,000 bonus
            credits.
          </li>
          <li>
            The reward is released after their first payment clears a holding period (14 days), and
            always lands in the pay-as-you-go bucket. If the payment is reversed, the reward is taken
            back from that bucket only.
          </li>
          <li>You cannot redeem your own code, each code can be redeemed once per account, and an account can use only one referral code in total.</li>
        </ul>
        <DocsTable
          caption="Referral code redeem results"
          head={['Redeem result', 'Meaning']}
          rowHeaders
          rows={[
            ['Invalid code', 'The code does not exist.'],
            ['Not redeemable or already redeemed', 'The code is expired or disabled, or you already used it.'],
            ['Exhausted', 'The code has reached its total redemption limit.'],
            ['Self-referral', 'You cannot redeem your own referral code.'],
            ['Already paid', 'You already subscribed, so there is nothing left to attribute.'],
          ]}
        />
        <p>
          On a self-hosted install, <strong>Refer &amp; earn</strong> works through your connected cloud
          account: connect one first, and rewards land on that cloud account.
        </p>

        <h2>Insufficient credits and delinquency</h2>
        <p>
          When a check before the work starts finds that your balance cannot cover it, the action is
          refused with HTTP 402, so a stop for lack of credits is distinguishable from
          an error. The refusal is recorded in your history.
        </p>
        <Callout variant="warn">
          An LLM call that has already run is billed afterwards and can push your balance below zero.
          When the total balance is at or below zero (on the Free plan: when the pay-as-you-go bucket is
          negative), the subscription is delinquent: new workflow starts are blocked
          until a top-up brings the balance back, which clears the state automatically.
        </Callout>

        <h2>Agent credit budgets</h2>
        <p>
          An agent can also carry its own <code>credit_budget</code>, counted in the same wallet
          credits as everything else: before each step the agent projects what it has spent plus the
          likely cost of the next step, and stops when that would pass its budget. See{' '}
          <a href="/agents">Agents</a>.
        </p>

        <h2 id="self-hosted-ce">Self-hosted (CE): unlimited, still tracked</h2>
        <p>
          A self-hosted install runs in unlimited mode: every debit is recorded, the
          balance never runs out, and nothing blocks. <strong>Quota &amp; Usage</strong> shows your spend
          in US dollars at the providers&apos; own prices, with no platform margin. There is no Stripe and
          no top-up on CE.
        </p>
        <p>
          A CE install can follow the plan of a linked cloud account. That happens only when the install
          is linked and at least one of its two sources is set to Cloud: the{' '}
          <strong>LLM source</strong> (in <strong>Settings &gt; AI Providers</strong>) or{' '}
          <strong>Integration credentials</strong> (in <strong>Settings &gt; Cloud</strong>). An install
          that uses its own keys for both keeps its local plan. When the cloud plan applies, the CE
          pricing page mirrors the cloud subscription and sends plan changes to the cloud, and the{' '}
          <strong>Quota &amp; Usage</strong> headline shows the cloud account&apos;s usage.
        </p>
        <p>
          Model calls relayed through the cloud are billed to the cloud account, once per execution, so
          a retry never charges twice. See <a href="/self-host">Self-hosting</a>.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common billing and credit problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              "A dialog says “You've run out of credits”",
              'Your balance cannot cover the action, so it was refused before it started.',
              'Choose Top up instead (when it is shown) for a one-time top-up, or Upgrade to a plan or a larger credit tier.',
            ],
            [
              'A lock appears next to a model, with “Your credits cannot pay for this model”',
              'On the Free plan, the monthly credits pay for workflow nodes and for the models marked Free only. Any other model draws on pay-as-you-go credits, and you have none left.',
              'Pick a model marked Free, or follow See plans or top up.',
            ],
            [
              'The upgrade or top-up button is replaced by “Billing is managed by the workspace owner”',
              'You are not the owner of the active workspace. Only the owner changes the subscription or the payment method.',
              'Ask the owner, or follow Switch to your personal organization to subscribe to manage your own billing.',
            ],
            [
              'A dialog says “Storage limit reached”',
              'The storage pool shared by every workspace you own is full.',
              'Delete files you no longer need (check usage in Settings > Storage), or upgrade to a plan with more storage.',
            ],
            [
              'New workflow runs are refused right after a long chat or agent turn',
              'A model call that had already run was billed afterwards and took your balance to zero or below, so the subscription is delinquent.',
              'Top up in Settings > Quota & Usage. The block lifts by itself once the balance is back above zero.',
            ],
            [
              'Redeeming a code says “This code is for new subscriptions only.”',
              'Your account has already paid for a subscription, so there is nothing left for the code to attribute.',
              'None: referral and promo codes must be redeemed before your first payment.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Building2} title="Organizations & roles" href="/organizations">
            Workspaces, members, quotas, and who pays.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Per-agent credit budgets and settings.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Run CE, and what linking a cloud account changes.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
