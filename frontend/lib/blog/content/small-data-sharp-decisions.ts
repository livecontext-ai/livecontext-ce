// Markdown body for the "Small data, sharp decisions" post. Stored as a plain
// string module (see the-niche-data-advantage.ts for the rationale).
const content = `There is a quiet assumption that better decisions need more data. Often the opposite is true. A small, trustworthy dataset that maps directly to a choice beats a giant one that buries the signal under noise.

The instinct is understandable. More data feels safer, more rigorous, more defensible. But volume and truth are different things. A large dataset can be large and wrong at the same time, and its size makes the wrongness harder to see.

## Precision over volume

A hundred rows you understand completely will outperform a million rows you half trust. With small data you can inspect every record, catch the outliers by eye, and know exactly what a number means before you act on it. That confidence is the whole point. A decision you can defend is worth more than a prediction you cannot explain.

Precision is not just accuracy. It is knowing the provenance of each value, the moment it was captured, and the reason it is in the set at all. When someone asks "why did the system flag this order," small data lets you answer with the actual rows. Big data usually forces you to answer with a shrug and a confidence interval.

There is also a speed argument. A sharp, small dataset gives a clear answer fast. A sprawling one demands modeling, sampling, and caveats before it says anything, and by then the decision window may have closed. For choices you make daily, the dataset that answers now beats the one that answers eventually.

## The hidden cost of scale you did not need

Large datasets carry taxes you pay whether or not you get value back. They cost more to store, more to move, more to keep fresh, and far more to reason about. Every extra column is another place for an error to hide. Every extra source is another pipeline that can break at 3am.

The worst cost is cognitive. When the dataset outgrows your ability to hold it in your head, you stop questioning it and start trusting it blindly. That is where quiet mistakes creep in. A miscoded field, a timezone bug, a join that silently drops a third of the rows, none of it announces itself. It just shifts your numbers, and because the dataset is too big to eyeball, no one notices until a decision goes wrong.

Scale also invites false confidence. A chart built on a million rows looks authoritative. People argue with it less. But an impressive-looking result built on data no one has actually inspected is more dangerous than a modest one everybody understands, precisely because it disarms the healthy skepticism that catches errors.

Small data keeps you honest. You can still ask, for any output, which rows produced it and why. That single ability, tracing any result back to its inputs, is worth more than another order of magnitude of volume.

## When small is the right call

Small and sharp is not always the answer. Some problems genuinely need scale: training a general model, spotting rare patterns across millions of events, forecasting from long histories. But a surprising share of everyday operational decisions do not. Reach for small data when:

- **The decision is specific and recurring**, like flagging which orders to review today or which invoices look off this week.
- **The population is bounded**, so you can cover all of it rather than sample and hope the sample represents the whole.
- **Freshness matters more than history.** For many operational calls, last week matters and ten years ago does not. A small current set beats a huge stale one.
- **A human needs to stand behind the result** and answer for it. If a person has to defend the decision, they need data they can actually read.
- **The cost of a wrong call is high enough** that you want to inspect the inputs, not trust a black box.

If most of those describe your problem, more data is not the upgrade. A cleaner, sharper version of what you already have is.

## How to keep a dataset small and sharp

Staying small takes discipline, because data accumulates by default. A few habits keep it lean:

1. **Define the decision first, then collect only what it needs.** Start from the choice the data drives and work backward. Every field should earn its place by feeding that choice. If you cannot say which decision a column serves, drop it.
2. **Set a freshness window and enforce it.** If the decision only cares about the last thirty days, do not carry three years. Let old rows age out. History you never consult is just risk in storage.
3. **Normalize at the edge, once.** Clean the data where it enters so the whole set stays in one consistent shape. Messy sprawl is how small datasets quietly become big ones.
4. **Prune on a schedule, not on a crisis.** Review the columns and sources periodically and remove what stopped being used. Datasets rot toward bloat unless someone actively trims them.
5. **Keep provenance attached.** Store where each value came from and when. It costs little and it is what lets you trust, and defend, every output.

## A concrete example

Consider an operations lead deciding which orders to hold for manual review each morning. The tempting move is to pull everything: full order history, customer lifetime value, browsing behavior, support tickets, a dozen joined tables. The result is a model no one can quite explain and a review queue people learn to ignore.

The sharp move is smaller. Take today's orders, plus three fields that actually predict a problem: order value versus the customer's usual range, shipping address mismatch, and whether the payment method is new. That is a bounded, current, inspectable set. The lead can look at any flagged order and see precisely why it was flagged. They can defend each hold to a customer. When the rules need tuning, they can reason about three signals instead of arguing with a black box.

Same decision, a fraction of the data, and far more trust in the outcome.

## Sharpen, do not accumulate

The instinct to collect more is strong. Resist it until the data you already have stops answering the question. Most of the time the fix is not a bigger dataset. It is a cleaner one, joined to the right context, feeding a decision you have defined clearly.

Keep it small. Keep it sharp. Let the workflow around it do the repeating.
`;

export default content;
