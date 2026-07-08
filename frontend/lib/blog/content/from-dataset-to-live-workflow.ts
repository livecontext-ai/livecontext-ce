// Markdown body for the "From dataset to live workflow" post. Stored as a plain
// string module (see the-niche-data-advantage.ts for the rationale).
const content = `A dataset becomes useful the moment something happens because of it. Until then it is a file. This is the shape we use to turn a static niche source into a workflow that runs on its own and ends in a real action.

To keep it concrete, one example runs through all five steps: a weekly supplier price list that should trigger a review-and-alert workflow. Every Monday your suppliers send an updated price sheet. Today someone opens each one, eyeballs it, and pings the buyer if something jumped. That is exactly the kind of chore that should run itself.

## Step 1: pick a source with a heartbeat

Choose data that updates on a schedule you can predict. A weekly export, a public page that refreshes each morning, an inbox that receives a report every Monday. The heartbeat is what lets you automate the refresh instead of copying rows by hand. If the source never changes, you do not need a workflow, you need a lookup. Save yourself the effort.

Be specific about the heartbeat. Not just "weekly" but "arrives by email each Monday before 9am, one CSV per supplier." That precision decides your trigger. A file that lands in an inbox suggests an email trigger. A page that refreshes suggests a scheduled fetch. A system that can call out suggests a webhook.

**Worked example.** The supplier price lists arrive as email attachments every Monday morning. That is a clean, predictable heartbeat. The trigger is "new email from a known supplier with a price-list attachment." No one has to remember to start anything.

## Step 2: normalize once, at the edge

Raw sources are messy. Column names drift, dates come in three formats, the same entity appears under two spellings, one supplier calls it "unit price" and another calls it "price/ea." Do the cleanup in one place, right where the data enters, so every downstream step sees a single consistent shape. A small normalization step at the front pays for itself many times over. Everything after it gets simpler because it can trust the input.

Decide the canonical shape first, then map every source onto it. For the price lists, the canonical row might be: supplier, sku, description, unit_price, currency, effective_date. Whatever a supplier's sheet looks like, the normalization step emits that shape and nothing else. Downstream nodes never see the raw mess.

**Worked example.** Supplier A ships an Excel file with a "Cost" column in euros. Supplier B ships a CSV with "List Price" in dollars. The normalization step reads each, converts to a common currency, parses the dates, and outputs the same six clean fields for every supplier. From here on, the workflow does not know or care which supplier a row came from.

## Step 3: branch on the decision, not the data

The point of the workflow is a decision. Model that decision explicitly. If a value crosses a threshold, route one way. Otherwise, route the other. Split a list and handle each item in parallel when the items are independent. Fork into separate paths when two things should happen at once. Keep the branching readable. A graph your whole team can follow at a glance is worth more than a clever one only its author understands.

The trap here is branching on raw data instead of on the decision. You do not care that the price is 12.40. You care whether it rose more than your tolerance since last week. So compute the decision, then branch on it.

**Worked example.** For each normalized row, the workflow looks up last week's price for the same sku, computes the percentage change, and branches: if the increase is over five percent, route to the "flag it" path; otherwise mark it reviewed and move on. Because each sku is independent, the list is split and the rows are checked in parallel, so a thousand-line sheet still clears in one pass.

## Step 4: end in an action, with a human where it matters

The last node should do something: send the notification, update the row, file the ticket, prepare the purchase order. When the action is risky or irreversible, pause for approval first. The run waits for a person to sign off, then picks up exactly where it stopped. Cheap, reversible actions can run unattended. Expensive or one-way actions get a human gate.

**Worked example.** Flagged price jumps are collected into one summary and sent to the buyer: supplier, sku, old price, new price, percent change. If a jump is large enough to auto-pause an order already in flight, the workflow stops at an approval step and waits for the buyer to confirm before touching anything. The routine ones just send the alert.

## Step 5: log the outcome so the next run is smarter

Write the result back. A table the workflow reads and updates becomes a shared memory: it remembers what it already processed, so the next run skips duplicates and only touches what is new. It is also the source for next week's comparison.

**Worked example.** Every processed row is written to a prices table keyed by supplier and sku, with the effective date. That table is exactly what Step 3 reads to compute "change since last week." The workflow feeds itself. It also gives you a clean audit trail: which prices changed when, and who approved the response.

## Common pitfalls

- **No real heartbeat.** Automating a source that rarely changes adds moving parts for no payoff. Confirm the cadence before you build.
- **Normalizing in three places.** If two nodes each clean the data their own way, they will drift and disagree. Normalize once, at the edge.
- **Branching on raw values.** Compute the decision, then branch on the decision. Thresholds buried inside five different nodes are impossible to change later.
- **No human gate on irreversible actions.** Auto-sending a purchase order on a bad parse is how automation earns a bad name. Gate the one-way steps.
- **Forgetting to write back.** Without a memory, every run reprocesses everything and cannot detect change. The log is not optional, it is what makes the loop work.
- **Comparing text as if it were numbers.** Store prices in a consistent numeric shape and compare them as numbers, so a jump from 9 to 100 reads as a rise, not a fall.

That is the whole pattern. A source with a heartbeat, a clean edge, an explicit decision, a real action, and a memory. Wire those five together and the dataset stops being a file you check. It becomes a system that works for you.
`;

export default content;
