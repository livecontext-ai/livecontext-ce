// Markdown body for the "The niche data advantage" post. Stored as a plain
// string module (not a `.md` file) so it imports the same way under every
// bundler (Turbopack, webpack) and under vitest, with no loader config and no
// runtime filesystem read.
const content = `Everyone talks about big data. The teams that actually ship useful automations tend to win somewhere else: on small, sharp, well understood datasets that most people ignore.

A niche dataset covers a narrow slice of the world in depth. A list of every independent coffee roaster in a region. The full fee schedule of a public agency. Historical delivery windows for one carrier on one route. The current price list from your top twelve suppliers. None of it is glamorous, and that is exactly why it is valuable: few competitors have bothered to structure it, and fewer still keep it current.

## Why narrow beats broad

Broad data is a commodity. If everyone can buy the same market feed, no one gets an edge from it. The moment a dataset is available to your whole industry at the same price, it becomes table stakes, not advantage. You are paying for the privilege of knowing what your competitors already know.

Narrow data is different. When you are the only team that has cleaned, joined, and kept a niche source fresh, the automation you build on top of it is hard to copy. A rival cannot simply buy your edge. They would have to rebuild the collection, the cleanup, and the domain judgment that tells good rows from bad. That takes time you have already spent.

There is a second reason narrow wins. Broad datasets force you to model the average case. Niche datasets let you model the specific case, the one your business actually lives in. An average is rarely the thing you act on. A specific, current fact usually is.

## The three properties that make niche data worth it

Not every small dataset is a moat. Three properties separate the ones that pay off from the ones that just take up space.

1. **It is bounded.** You can enumerate the whole thing, so you can reason about coverage and gaps instead of sampling and hoping. A directory of licensed electricians in one county is finite. You know when it is complete. Compare that to a stream of social posts, where you can never say you have seen all of it, so you can never fully trust a count.
2. **It changes on a rhythm you can learn.** Prices update monthly. Court dockets post daily. Tariff schedules shift each quarter. Once you know the cadence, a scheduled workflow keeps you current with almost no babysitting. A source with a predictable heartbeat is a source you can automate.
3. **It maps to a decision.** Good niche data does not sit in a dashboard. It answers a question someone acts on today. Which supplier to call. Which listing to flag. Which route to avoid. Which permit is about to expire. If a dataset does not change what someone does, its size is irrelevant.

Run a candidate source through those three. A regional list of commercial kitchen equipment resellers is bounded, refreshes on a slow but knowable rhythm, and maps to a real decision (who to source a used oven from). That is a moat in the making. A generic firehose of national business listings fails all three.

## Examples across industries

The pattern repeats everywhere once you look for it.

- **Logistics.** On-time performance for one lane, one carrier, over two years. Big shippers see the national average. You see the specific route you depend on, and you route around the bad days before they cost you.
- **Local services.** Every permit filed in a metro area for a given trade. A bounded, dated, decision-shaped list. It tells a sales team exactly who just started a project and needs a supplier this week.
- **Healthcare operations.** A single payer's published reimbursement rates for a set of procedure codes. Narrow, slow-changing, and directly tied to what to bill and what to appeal.
- **E-commerce.** The price and stock status of forty competing SKUs, checked twice a day. Not the whole market, just the forty that determine your pricing move tomorrow morning.

None of these require scale. They require care and consistency.

## The workflow is the moat, not the file

Here is the part most teams miss. A dataset on its own is inert. Anyone with the same file gets the same nothing. The value shows up when the data feeds a repeatable action: classify each new row, branch on a threshold, notify the right person, and log the outcome so the next run is smarter.

That loop is where a niche source turns into leverage. The file gives you raw material. The workflow around it, the branching, the guardrails, the audit trail, is what turns raw material into a decision your team trusts and repeats. Copy someone's spreadsheet and you have a stale snapshot. Copy their operating loop and you would still need the fresh data feeding it. The two together are what compound.

This is why the moat is the workflow. Data ages. A living system that refreshes the data, applies your judgment, and acts on it does not age the same way. It gets better every time it runs.

## How to start

Pick one source you understand better than anyone else on your team. Enumerate it so you know its edges. Confirm it has a heartbeat you can predict. Then wire it into a workflow that ends in a real action, and let it run. Watch the first few runs, correct the edge cases, and step back.

## Common mistakes to avoid

- **Chasing breadth first.** A wide, shallow dataset feels impressive and does nothing. Depth compounds. Breadth dilutes.
- **Collecting without a decision.** If you cannot name the choice the data drives, you are hoarding, not building.
- **Letting it go stale.** A niche dataset that is not refreshed is worse than none, because people still trust it. Automate the refresh or do not bother.
- **Over-cleaning up front.** You do not need a perfect schema on day one. You need one clean enough to make the first decision, and a normalization step you can tighten later.

Start narrow. Keep it fresh. End every run in an action. Breadth can come later. Depth is what compounds.
`;

export default content;
