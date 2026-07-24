// Markdown body for "The niche data advantage, priced". Plain string module
// (see the-niche-data-advantage.ts's sibling modules for the rationale).
//
// This post MERGES and replaces two earlier assertion-only posts:
// the-niche-data-advantage (this slug, kept) and small-data-sharp-decisions
// (retired, 301-redirected here in next.config.mjs). Do not reintroduce the
// retired slug.
//
// This is the blog's flagship contrarian piece and its evidence contract is
// strict: every claim is cited, derived on the page, or explicitly labelled as
// the author's judgment. It argues the OPPOSITE case first and at full strength,
// and it CONCEDES what the evidence forces (the anti-moat literature has the
// better base; the one on-regime retrieval study, Nourbakhsh et al.
// arXiv:2606.04127, biomedical, found curation bought nothing). Do not edit that
// honesty into a confident sales pitch.
//
// Every worked number (N, k, r, the accuracies) is a stated illustrative
// assumption, not a measurement; k in particular has no public benchmark and the
// break-evens are most sensitive to it. All 31 citations were adversarially
// fact-checked on 2026-07-23; the load-bearing Nourbakhsh claim was independently
// re-verified against the paper. Re-check a citation before altering the figure
// it supports.
const content = `## Data selection as a refresh obligation, priced

You are not acquiring rows. You are taking on a refresh obligation. One measured parameter, r, the annual fraction of your records that become wrong, sets four things at once: the maintenance cost, the refresh cadence, the maintenance term in the build-versus-buy break-even, and how long a competitor's stolen copy stays useful. One parameter drives four decisions, but Result 4 below shows you must measure it per field, and only after buying two prerequisites: an independent oracle and a known per-record verification cost k.

This piece prices the niche-data thesis instead of praising it, and it argues the opposite case first, as hard as the evidence allows. It also fixes the blog's retired slogan, "a hundred rows you understand beat a million you half trust", which as written is false: the last section gives the condition under which it holds, and shows that condition usually vanishes.

Evidence contract. Every claim is one of three things: cited with a link, derived with the arithmetic on the page, or labelled my judgment. The worked numbers (N, k, r, the accuracies) are illustrative assumptions, flagged at each use, not measurements. Where the research turned up nothing, the article says so rather than estimating.

Scope fence. The cost of context, budget enforcement and sizing, audit-trail schemas, audit retention, and turning a qualified dataset into a running workflow are companion pieces. This one is about data selection and its economics, and it stops at the decision to acquire.

Target reader: a technical founder or lead choosing what data to invest in before building an agent or automation on top of it.

## The strongest case against (read this first)

The proprietary-data-moat thesis is contested, and the sceptics hold the better evidence base.

Lambrecht and Tucker, [Can Big Data Protect a Firm from Competition?](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2705530) (2015), run data through the VRIN test and find it usually fails: big data is rarely inimitable or rare, substitutes exist, and the scarce resource is the managerial toolkit around the data, not the data. Their counter-examples are entrants (Airbnb, Uber, Tinder) who beat incumbents that already held the relevant data.

Casado and Lauten, [The Empty Promise of Data Moats](https://a16z.com/the-empty-promise-of-data-moats/) (a16z, 2019), argue that data network effects are usually data scale effects, and scale effects saturate. In their support-chatbot case, beyond roughly 40% of queries collected more data adds no advantage, and intent coverage asymptotes near 40%: it never reaches full automation at all.

Varian, [NBER WP 24839](https://www.nber.org/papers/w24839) (2018), notes that statistical precision scales with the square root of sample size, so you need four times the data to halve your error, and that ImageNet's train and test sets were fixed across the years of largest accuracy gains, so those gains cannot be attributed to more data.

Hestness et al., [arXiv:1712.00409](https://arxiv.org/abs/1712.00409), find generalization error falls as a power law in dataset size with exponents between -0.07 and -0.35. Since the data multiple to halve error is 2^(1/beta):

\`\`\`
beta = 0.07 -> 10x data cuts error 14.9%; halving needs ~19,972x data
beta = 0.15 -> 10x data cuts error 29.2%; halving needs ~102x data
beta = 0.35 -> 10x data cuts error 55.3%; halving needs ~7x data
\`\`\`

This cuts both ways: flat exponents mean a competitor's 100x advantage buys little, but your 3x buys almost nothing.

Chiou and Tucker, [NBER WP 23815](https://www.nber.org/papers/w23815) (2017), exploit EU-prompted retention cuts (Bing 18 months to 6, Yahoo 13 to 3) and find little measurable degradation in search accuracy, concluding that "the possession of historical data confers less of an advantage in market share than is sometimes supposed." Allcott, Castillo, Gentzkow, Musolff and Salz, [NBER WP 33410](https://www.nber.org/papers/w33410) (2025), find that eliminating demand frictions doubles Bing's share while data-sharing mandates have small effects. The moat was distribution and defaults.

Head-to-head, the big generic corpus keeps winning. [Li et al.](https://arxiv.org/html/2305.05862) report GPT-4 beating BloombergGPT (50B parameters, 363B proprietary financial tokens plus 345B general, per the [BloombergGPT paper](https://arxiv.org/pdf/2303.17564)) on ConvFinQA 0-shot 76.48% against 43.41%, FiQA-SA 5-shot 88.11% against 75.07%, and Financial PhraseBank 5-shot 0.97 against 0.51 F1. [Nori et al.](https://arxiv.org/abs/2311.16452) beat Med-PaLM 2 on all nine MultiMedQA datasets using generic GPT-4 plus prompting, with no domain-specific pretraining or fine-tuning. And Ovadia et al., [Fine-Tuning or Retrieval?](https://arxiv.org/html/2312.05934v3), find RAG consistently beats fine-tuning for knowledge injection (Mistral 7B on a current-events task: base 0.481, RAG 0.875, fine-tune 0.504). If your data's value is realised in a context window, whoever obtains the documents captures the same value with no training run.

Two robustness results attack the "million rows you half trust" half of the slogan. [Subramanyam, Chen and Grossman](https://arxiv.org/abs/2510.03313) measure quality exponents of about 0.173 (machine translation) and 0.401 (causal language modelling), both well under 1, so effective dataset size decays sublinearly with quality. [Muennighoff et al.](https://arxiv.org/abs/2305.16264) (NeurIPS 2023) find that under a fixed compute budget with constrained data, up to four epochs of repeated tokens are near-indistinguishable from fresh unique data.

The small side fails on its own arithmetic. The 95% CI on a proportion at p=0.5 is 1.96*sqrt(p(1-p)/n): n=100 gives plus or minus 9.80 points, n=1,000 gives 3.10, n=1,000,000 gives 0.098. And P(zero occurrences) = (1-rate)^n, so 100 curated rows have a 36.6% chance of containing zero instances of a 1%-frequency failure mode. You need about 299 rows to be 95% confident of seeing a 1-in-100 event once, about 2,995 for a 1-in-1,000. Small data you understand cannot see its own tail.

Behind all of this sits Sutton's [Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html) (2019), and two expensive failures. IBM assembled one of the largest proprietary health corpora through roughly $4B of acquisitions (Merge about $1B, [Truven $2.6B](https://techcrunch.com/2016/02/18/ibm-acquiring-truven-health-analytics-for-2-6-billion-and-adding-it-to-watson-health), plus Phytel and Explorys) and [sold Watson Health to Francisco Partners in 2022](https://www.fiercehealthcare.com/tech/ibm-sells-watson-health-assets-to-investment-firm-francisco-partners) for a reported ~$1.065B. Zillow closed Zillow Offers in November 2021 after a $422M Q3 2021 Homes-segment loss (Q3 2021 8-K), the CEO citing unpredictability in forecasting home prices ([AI Incident Database 149](https://incidentdatabase.ai/cite/149/)).

| Argument | Measured result | Source | What it does not settle |
|---|---|---|---|
| Data fails VRIN | Data rarely rare or inimitable; entrants beat data-holding incumbents | Lambrecht and Tucker 2015 | Whether first-party unpublished events have substitutes |
| Scale effects saturate | Marginal coverage flat beyond ~40% of queries collected; intent coverage asymptotes near 40% | Casado and Lauten 2019 | Datasets whose value is freshness, not coverage |
| Square-root precision | 4x data to halve estimate error | Varian, NBER 24839 | Retrieval, where precision is not the mechanism |
| Power-law returns | Error exponents -0.07 to -0.35 | Hestness et al. 2017 | Anything outside model training |
| Retention cuts harmless | Bing 18 to 6 months, no measurable accuracy loss | Chiou and Tucker, NBER 23815 | Small operational corpora with no scale substitute |
| Distribution is the moat | Removing demand frictions doubles Bing share | Allcott et al., NBER 33410 | Markets without a default-placement channel |
| Generic beats domain | GPT-4 over BloombergGPT on 3 of 3 cited tasks | Li et al. 2305.05862 | Structured extraction, where the same paper shows fine-tuned models winning |
| Retrieval beats fine-tuning | Mistral 7B: 0.875 RAG vs 0.504 fine-tune | Ovadia et al. 2312.05934 | Whether the documents themselves are obtainable |

## What that evidence does not cover

Almost the entire anti-moat base concerns model pretraining at frontier scale. The target reader is not training anything; they are selecting data for a context window or a tool response. That 363 billion proprietary financial tokens failed to beat GPT-4 says little about whether 40,000 well-structured internal rows make a good agent input.

The mirror problem cuts against my thesis with equal force: almost every large measured curation win is also a training-corpus result. [FineWeb-Edu](https://arxiv.org/abs/2406.17557) removed roughly 91% of FineWeb (15T to 1.3T tokens) and raised MMLU from 33% to 37% and ARC from 46% to 57% at a fixed 350B-token budget, matching full-corpus MMLU with about 10x fewer tokens than C4 and Dolma. [LIMA](https://arxiv.org/abs/2305.11206), [AlpaGasus](https://arxiv.org/abs/2307.08701) and DataComp are training results too. Transferring them to retrieval is an assumption, and no located study measures both regimes on the same task.

The one large-scale retrieval-side study points the other way, and this article does not get to walk past it. [Nourbakhsh et al., "When Retrieval Doesn't Help"](https://arxiv.org/abs/2606.04127), a biomedical RAG study across 5 models, 10 QA datasets, 4 retrieval methods and 4 corpora, found retrieval gave only 1 to 2 points over a no-retrieval baseline, and expert-curated sources performed no better than layman sources. The binding constraint was the model's limited ability to use retrieved evidence, not corpus quality. It is the single located measurement in the reader's actual regime, it is curation-specific, and its finding is that curation bought nothing. The domain is biomedical, so its transfer to other retrieval tasks is itself unmeasured, but it is on-regime evidence and the thesis has to be demoted to a hypothesis in front of it.

One retrieval-side result does support curation. RAG accuracy follows an inverted U, peaking around 10 to 20 passages on Natural Questions and dropping past 40 across Gemma-7B, Gemma-2-9B, Mistral-Nemo-12B and Gemini-1.5-Pro ([arXiv:2410.05983](https://arxiv.org/html/2410.05983v1), ICLR 2025). The damage comes from hard negatives, near-miss documents that score high and do not contain the answer. Curation earns its keep by removing plausible wrong neighbours. Whether that mechanism transfers is untested judgment, not an answer to Nourbakhsh.

The gap the reader most wants closed is empty. I found no public, methodologically transparent measurement of what curating a private corpus buys. Vendor content asserts 95 to 99% accuracy with no baseline, methodology or sample size, which this article will not cite. Nor did I find one measured case of a small organisation's niche dataset beating a generic corpus in a production agent setting.

LIMA's Superficial Alignment Hypothesis is a weapon against my thesis: knowledge comes almost entirely from pretraining, and small curated sets teach format and style. On that reading, a curated niche corpus buys formatting, not understanding. So the thesis cannot be defended on volume or knowledge. If it survives, it survives on freshness, coverage of a specific decision surface, and cost, which are measurable, and which the rest of this article instruments.

## The only parameter that matters: r, and what it costs you

Measure it, do not cite it, and measure it as a two-timepoint design: sample records verified at t0, re-check them at t0+delta against an independent oracle, count changed fields, r = -ln(1-p)*365/delta_days. Report the confidence interval: at p=0.3, n=100, the CI on r runs roughly 21% to 39%, which propagates into every derived figure below (Mb of about $7,400 to $15,400, a build-beats-nothing threshold of roughly 723 to 1,059 rather than a single confident number). The small-sample critique above applies to your own r too.

Model, derived here. Under a constant hazard, a record verified at t=0 is still correct with probability A(t) = e^(-lambda*t), where lambda = -ln(1-r). To hold a worst-case accuracy floor A_floor, refresh every T years:

\`\`\`
lambda        = -ln(1 - r)
T             = ln(1/A_floor) / lambda
passes / year = lambda / ln(1/A_floor)
maintenance   = N * k * lambda / ln(1/A_floor)
\`\`\`

One consistency check: the quoted 2.1% monthly contact decay gives 12 * -ln(1-0.021) = 0.2547, and -ln(1-0.225) = 0.2549. They circulate as separate figures and are the same figure compounded, to three decimals.

**Result 1.** If you refresh at exactly the cadence that holds A_floor, mean accuracy over the cycle is (1-A_floor)/ln(1/A_floor), which depends only on the floor, not on r. A 95% floor always averages 97.48%, a 90% floor 94.91%, a 99% floor 99.50%. The change rate sets the price of the floor, never the quality you get for it.

**Result 2.** Passes per year is lambda/ln(1/A_floor), so relative to a 90% floor a 95% floor costs 2.05x, a 99% floor 10.48x, a 99.9% floor 105.31x. Choose the floor from the cost of a wrong decision.

**Result 3.** Rolling re-verification must be oldest-first. Random rolling at rate v reaches a steady-state mean of v/(v+lambda) but has no floor at all: record ages are exponentially distributed, so a tail of records is arbitrarily stale no matter what you spend. Oldest-first is equivalent to batch and bounds the worst case; random does not.

**Result 4, the largest lever.** Measure r per field. A dataset 80% stable (r=2%) and 20% volatile (r=30%) costs 6.954 full passes/year uniformly, against 0.2*6.954 + 0.8*0.394 = 1.706 pass-equivalents segmented, a 4.08x saving at an identical 95% floor. This assumes verification cost scales with the fraction of fields touched; a fixed per-record component (fetch, match, context-switch) shrinks the saving toward 1x.

Model caveat: constant hazard is a simplification, and it is testable. Plot the survival curve on a log axis; if it is not straight, fit a Weibull S(t) = exp(-(t/eta)^k), giving T = eta*(ln(1/A_floor))^(1/k). Pew's link-rot data is front-loaded, which is the k<1 case (a decreasing hazard, heavy early loss). Under k<1 the exponential understates early loss and overstates late survival, so the first refresh must come sooner than T.

For sources you do not control, Pew's [When Online Content Disappears](https://www.pewresearch.org/data-labs/2024/05/17/when-online-content-disappears/) (2024) is the only clean external anchor I found: 38% of pages that existed in 2013 were gone by October 2023, but 8% of 2023 pages were already gone within a year. The ten-year-average hazard is -ln(0.62)/10 = 0.0478/yr, but the directly observed first-year hazard is 8%. Use 8% for cadence-setting on fresh sources.

One provenance warning: the 22.5% per year B2B contact figure traces to MarketingSherpa via [HubSpot's Database Decay Simulation](https://www.hubspot.com/database-decay), replicated by lead-gen vendors with a commercial interest and no published methodology or sample size. Apply it to B2B contact lists and nothing else. Decay rates for product catalogues, pricing, regulatory corpora, geospatial and technical documentation appear unpublished. The table is a model to plug your own r into.

| Annual change rate r | lambda | Days between refreshes, 95% floor | Days, 90% floor | Full passes/yr, 95% floor | Half-life of a one-shot copy |
|---|---|---|---|---|---|
| 2% | 0.0202 | 927 | 1,904 | 0.39 | 34.3 yr |
| 5% | 0.0513 | 365 | 750 | 1.00 | 13.5 yr |
| 10% | 0.1054 | 178 | 365 | 2.05 | 6.58 yr |
| 22.5% (B2B contacts only) | 0.2549 | 73.5 | 151 | 4.97 | 2.72 yr |
| 30% | 0.3567 | 52.5 | 108 | 6.95 | 1.94 yr |
| 60% | 0.9163 | 20.4 | 42.0 | 17.87 | 0.76 yr |

## The scorecard: seven rows, two gates

Symbols used below and defined here: D is decisions per year, v is the net value per correct decision (the swing between a right and a wrong call, so error cost is already inside it), and D_be is the build-versus-nothing break-even volume (Cb/H+Mb)/(v*(Ab-A0)) derived in the next section. Row 3's threshold uses annual decision value, written D times v.

| Criterion | Test you can run this week | Threshold | Score 0-3 |
|---|---|---|---|
| 1. Enumerability | Two independent samples by two routes, overlap m, Chapman estimator | 3 if coverage >=95%; 2 if 90-95%; 1 if 75-90% and you can name the excluded segment; 0 if no N-hat | |
| 2. Verifiability (GATE) | Name the independent oracle; measure k and minutes per record | Pass if k <= 1% of v and <= 10 min/record | pass/fail |
| 3. Decay affordability | Re-verify records at two timepoints, annualise to r, compute maintenance as % of D times v | 3 if <=5%; 2 if 5-15%; 1 if 15-30%; 0 if >30% or r unmeasured | |
| 4. Heartbeat | Last 12 published versions, coefficient of variation of inter-publication gaps | 3 if CV <=0.25 and max gap <=2x median; 2 if CV <=0.5 or you control the pull; 1 if CV <=1.0 or gaps irregular but bounded; 0 if no version history | |
| 5. Decision linkage (GATE) | Name decision, actor, default, D per year; measure 90-day divergence rate | Pass if D >= D_be and divergence >= 2% | pass/fail |
| 6. Non-substitutability | Price the cheapest full replication in days of skilled work | 3 if replication is legally blocked (name the access right); 2 if >180 days; 1 if 30-180 days; 0 if <30 days or a vendor lists it as a SKU | |
| 7. Join integrity | Attempt the join on a 500-row sample, measure exact primary-key match rate | 3 if >=98%; 2 if 95-98%; 1 if 90-95%; 0 if <90% | |

**Row 1** uses the Chapman estimator N-hat = ((n1+1)(n2+1)/(m+1)) - 1: n1=300, n2=250, m=180 gives 416, so holding 380 rows is 91.3% coverage. Chapman assumes equal catchability, but the missing entities are systematically the newest and most remote, which biases N-hat down. So N-hat is a lower bound on the universe and the coverage figure an upper bound. Run the recapture again restricted to entities first seen in the last 12 months as a required second number.

**Row 2 is a gate** because without an oracle you cannot measure r, so rows 1 and 3 are unanswerable. k is also the multiplicand in the maintenance formula, so this one number prices the whole obligation. Note that k = $0.40 in the worked example implies near-automated verification (about a minute per record at $25.23/hr); the gate itself tolerates 10 min/record, which is $4.20, an order of magnitude higher.

**Row 3, worked:** N=4,000, k=$0.40, r=30%, 95% floor gives 6.95 passes and $11,126/yr; segmenting to 20% volatile fields gives $2,729. Both scale linearly with the assumed k.

**Row 4** makes the retired post's "changes on a rhythm you can learn" measurable: a source whose own publication interval is more variable than your required T makes the floor unenforceable at any spend.

**Row 5 kills most candidates.** Name the decision, actor, default and D per year, and measure the 90-day divergence rate (how often the data would have changed the call). Below 2% the data is not moving decisions, a hard fail. I found no study measuring production divergence, so the 2% is my judgment.

**Row 6** pairs with the one-shot copy half-life ln2/lambda, but compute it per field segment: a competitor copies the stable 80% (half-life 34.3 years at r=2%) and re-derives the volatile fifth, so the dataset-level half-life overstates defensibility. Report the stable-segment number.

**Row 7** matters because accuracies multiply: a 95%-accurate dataset joined at 90% delivers 85.5% effective accuracy. Apply the join factor to both your build and any vendor set, since it degrades any external dataset joined to your keys.

Scoring rule, my judgment: two gates pass/fail, five rows score 0-3 for a maximum of 15, invest at 11 with both gates passed. The tests are runnable and the arithmetic behind rows 1, 3 and 7 is on the page. Every numeric cut-off in the threshold column is my judgment, calibrated on experience, not derived or cited; move them. The instrument's real function is to force seven measurements that take about a week.

Applied to the retired posts' interchangeable examples: on-time performance for one carrier on one lane, every trade permit in a metro, one payer's reimbursement rates, and price and stock for 40 SKUs checked twice daily differ enormously on rows 3, 4 and 6. The SKU set has a lambda in the hundreds per year (r pinned at essentially 100%, because r is a fraction bounded below 1; use lambda directly when change is faster than annual) and a copy half-life in days. The permit register has an r near zero and is trivially copyable.

## What data actually costs

Every figure carries its provenance grade.

| Item | Vendor | Published price | Provenance |
|---|---|---|---|
| Residential proxy bandwidth | [Bright Data](https://brightdata.com/pricing/proxy-network/residential-proxies) | $8/GB PAYG, $5/GB at the $1,999/mo tier | Primary page fetch |
| Datacenter / ISP proxies | Bright Data | $1.30-$1.80 and $0.90-$1.40 per IP per month | Primary page fetch |
| Scraping by difficulty tier | [Zyte](https://docs.zyte.com/zyte-api/pricing.html) | 5 HTTP and 5 browser tiers; ~$0.13-$1.27 and ~$1.01-$16.08 per 1,000 | Tier structure primary; rates aggregator-reported |
| Screenshot add-on | Zyte | $0.002 each | Primary docs |
| Labelling tooling | SageMaker Ground Truth | $0.08 / $0.04 / $0.02 per object (tiers 1-50k / 50-100k / >100k); 500 objects/mo free for the first two months | Aggregator-reported, possibly legacy, not currently published by AWS |
| Labelling tooling | Labelbox | $0.10 per Labelbox Unit, 1 LBU per labeled row | Aggregator-reported |
| Labelling | [Scale AI](https://scale.com/pricing) | No enterprise rate published; free tier only | Primary page fetch |
| US annotation labour | [ZipRecruiter](https://www.ziprecruiter.com/Salaries/Data-Annotation-Salary) | ~$25.23/hr ($52,488/yr); offshore ~$2 to $5-12/hr | Primary; offshore aggregator-reported |
| B2B contact data | [Vendr](https://www.vendr.com/buyer-guides/zoominfo) | ZoomInfo median $33,500/yr over 1,566 purchases, range $7,200-$155,550 | Verified transaction data |
| Market data | [Databento](https://databento.com/pricing) | $199 / $1,750 / $4,500 per month | Primary page fetch |
| Narrow single-purpose feeds | [Massive](https://massive.com/pricing) | NYSE Order Imbalances $49/mo; European Consumer Spending by Merchant $99/mo | Primary page fetch |
| Marketplace listings | [AWS Data Exchange](https://aws.amazon.com/data-exchange/pricing/) | Provider-set; $0.023/GB/mo storage, $0.04167/hr data grants | Primary page fetch |
| Marketplace listings | Snowflake Marketplace | Per-month, query or hybrid; real listings $100-$1,500/mo | Vendor docs plus secondary |
| Training-data licensing | News Corp / OpenAI; Reddit / Google | >$250M over 5 years; ~$60M/yr (Reddit S-1: $203M aggregate) | Corroborated press reporting |
| Legal review of the acquisition method | Your counsel | Indicative: review plus DPIA, low-to-mid five figures one-off plus ongoing handling | My judgment, no transacted figure located |

Two derived figures, with assumptions visible. A one-million-page niche corpus at an assumed 200KB per page (my assumption) is 200GB: about $1,600 of Bright Data residential bandwidth at list, against about $130 through Zyte at HTTP tier 1 or about $16,080 at browser tier 5, two orders of magnitude apart, decided by which tier the target lands in. Labelling 100,000 records at the Ground Truth tiers above is 50,000*$0.08 + 50,000*$0.04 = $6,000 in tooling (the 500-free allowance is immaterial, and these per-object tiers are possibly legacy, no longer published by AWS), or 100,000*$0.10 = $10,000 in Labelbox units, both excluding human labour, the larger line.

The honest hole: I found no transacted mid-market price for licensing or building a 10,000 to 100,000 row domain dataset. The published range runs from about $0.01 per label to $250M per deal, roughly ten orders of magnitude, with the middle undocumented. There is also no public benchmark for k, the cost per verified record, the input the break-even below is most sensitive to.

## Build, buy, or do nothing

The genre compares build against buy and never tests the third option. Doing nothing has a positive net value, D*v*A0, and the model below beats both alternatives at every volume under the break-even with these inputs.

\`\`\`
Nothing = D*v*A0
Buy     = D*v*Av - L
Build   = D*v*Ab - (Cb/H + Mb)

Build beats buy     when D > (Cb/H + Mb - L) / (v*(Ab - Av))
Build beats nothing when D > (Cb/H + Mb)     / (v*(Ab - A0))
Buy   beats nothing when D > L               / (v*(Av - A0))
\`\`\`

Cb is one-time acquisition, H the amortisation horizon, Mb maintenance per period, L licence per period, v the net value per correct decision (already the swing between right and wrong, so error cost is inside it; if you prefer gross value plus a separate error cost c, replace v with v+c).

Worked inputs, all illustrative assumptions, not measurements: N=4,000, Cb=$30,000, H=3 years, L=$18,000/yr, v=$60, Ab=0.95, Av=0.78, A0=0.55, r=30%, k=$0.40. Mb=$11,100/yr is derived from them (4,000*$0.40*6.95 passes at a 95% floor), which is not the same as independently known: it inherits the assumed k, and k has no public benchmark. A 40-point Ab-A0 gap is optimistic; a smaller, more realistic gap raises all three break-evens and widens the range where doing nothing wins.

Break-evens at k=$0.40: build beats buy above (10,000+11,100-18,000)/(60*0.17) = 304/yr; build beats doing nothing above 21,100/(60*0.40) = 879; buy beats doing nothing above 18,000/(60*0.23) = 1,304.

These flip on k. At k=$0.40 the buy band is empty (upper bound 304 below lower bound 1,304), so buy is dominated. But the row 2 gate tolerates 10 min/record, which at $25.23/hr is $4.20. At k=$4.20, Mb=$116,800: build-beats-nothing moves from 879 to 5,283, and the buy band opens to roughly 1,304 to 10,667. The band opens once k exceeds about $0.75. So "buying is dominated at every volume" holds only under near-automated verification. It is not a general result, and it is retracted for manual verification.

| Decisions/yr | Do nothing | Buy at $18,000/yr | Build ($30k over 3yr + $11.1k/yr) | Winner |
|---|---|---|---|---|
| 294 | $9,702 | -$4,241 | -$4,342 | Nothing |
| 879 | $29,007 | $23,137 | $29,003 | Nothing (crossing point) |
| 1,304 | $43,032 | $43,027 | $53,228 | Build |
| 2,000 | $66,000 | $75,600 | $92,900 | Build |

| Vendor accuracy Av | Buy band lower bound | Buy band upper bound | Band (k=$0.40) |
|---|---|---|---|
| 0.78 | 1,304 | 304 | Empty |
| 0.85 | 1,000 | 517 | Empty |
| 0.90 | 857 | 1,033 | Open, 857-1,033 |
| 0.93 | 789 | 2,583 | Open, 789-2,583 |

Buying is right precisely when the vendor is nearly as accurate as you would be on your own surface, which is a question about your subset, not their marketing. Sample 200 vendor records inside your niche and measure Av before signing. Sensitivity on v scales as 1/v: at $6 instead of $60, build beats buy only above 3,100/(6*0.17) = about 3,040/yr.

The model also omits the option that usually dominates in this reader's regime: buy the copyable bulk and build only the outcome column no one can scrape. In a context window you hold both, so there is rarely a reason to choose one dataset over the other at all.

Now the cadence loop. Your edge over a competitor is the mean-accuracy gap from a faster cadence, where mean accuracy over interval T is (1-e^(-lambda*T))/(lambda*T). At r=30%, monthly refresh gives a 98.53% mean, annual gives 84.11%, a 14.4-point gap. The incremental cost of monthly over annual is 11 extra passes, 11*4,000*$0.40 = $17,600/yr, so the cadence gap only pays above 17,600/(60*0.144) = about 2,034 decisions/yr, above both worked break-evens. And it is not a data moat: a competitor who staffs the same refresh pipeline erases it. The defensible thing is an operating cadence, a hiring and tooling fact, not a data fact.

## Four ways this goes wrong after you buy

| Failure mode | Symptom you will actually see | Detection method | Trigger threshold |
|---|---|---|---|
| Coverage illusion | Backtest fine, live performance on new cases poor, gap widening | Capture-recapture (row 1) on entities first seen in the last 12 months | New-entity coverage more than 15pp below overall |
| Stale-but-trusted | Confident answers built on fields nobody has touched in years | Read-weighted staleness: fraction of reads landing on rows older than T | More than 5% of reads past the floor cadence |
| Decision drift | Green pipeline, updating data, nobody's action changes | 90-day divergence rate (row 5) | Below 2%, kill the dataset |
| Maintenance cliff | k jumps, a refresh pass silently fails, a source starts blocking you, a field means something new | Source concentration, year-over-year k, and source-block rate | Any single source >50% of rows, k up >25% YoY, or a scraped source refusing you |

In my judgment the shortfall in a coverage number is not random: it concentrates in the newest, smallest, most remote entities, exactly the segment the decision is about. If your sampling routes correlate with entity age, run row 1 separately on the last 12 months to find out.

Read-weighting matters because the hot 5% of rows are usually the ones consulted (my assumption, testable via the read-weighted measure itself); if they are also the volatile ones, record-weighted freshness flatters you. Add a verified_at column or none of the model in this article can be run. Decision drift survives longest because every dashboard reads healthy. A source that starts refusing you is both a maintenance cliff and a legal signal. The thresholds in these rows are my judgment; the base hazard for uncontrolled web sources is Pew's first-year 8%.

## Where the niche thesis holds, and where it does not

My contribution, offered as judgment: defensibility is proportional to refresh cost, and proprietary data by itself is not a moat. Lambrecht and Tucker's point stands: the scarce resource is the operating toolkit around the data. What may be defensible is a maintained refresh cadence wrapped around a closed decision loop, and only for as long as no competitor staffs the same pipeline. That is a hiring race, not a data advantage. "Find data cheap to maintain" and "find data that is defensible" are therefore opposing instructions, and most founders are handed both.

Say the scoreboard plainly. The anti-thesis has two documented failures (Watson Health, Zillow) and six empirical strands. The pro-thesis has zero named production cases in this reader's regime: no transparent measurement of what curating a private corpus buys, and the one on-target retrieval study found it buys nothing. Failures in this class go unpublished, so the sample is survivorship-selected. Treat the thesis as a hypothesis this article instruments, not a result it proves. Its falsification test: measure divergence and effective-accuracy lift on your own surface; if the lift is inside the noise, the thesis has failed for you.

Four conditions under which it might survive.

1. **The data records a decision only you make, not a body of knowledge.** The defensible object is the closed loop of decision, outcome, labelled record, because the outcome column cannot be scraped, only earned. This is the only condition consistent with all the evidence above: no claim of rare facts, no reliance on scale, not inferable from public text.
2. **First-party observation of events that leave no joined public trace.** An event you observe still leaves a footprint with your counterparty, a broker, or a processor (the $99/mo merchant-spend feed above is exactly resold transaction data). But no one else holds the joined record of event, context and outcome under your key. That join is the defensible object, not the event.
3. **High decay, understood as a recurring cost rather than a barrier.** A fast-decaying set cannot be stolen once, only maintained, so it is defensible only while you hold the cadence gap, which a competitor can hire away. At r=30% a one-shot snapshot is 23.5% wrong within 9 months, but a competitor who also builds a refresh machine loses nothing.
4. **Small enough to verify exhaustively.** At 4,000 records and k=$0.40 a 99% floor at r=30% costs about $56,800/yr; at 400,000 records it is about $5.68M and nobody buys it. Both scale with the assumed k.

Where it does not hold: (a) a vendor sells it as a SKU (rent it, see the $49 to $99/mo feeds above); (b) low decay plus public sources (your copy and theirs age together, so you compete on distribution, where the natural experiments say the moat actually was); (c) below the break-even decision volume; (d) no independent oracle (you cannot measure r, so you cannot price anything here); (e) the task is reasoning or semantics rather than lookup and structured extraction (GPT-4 over BloombergGPT, generic prompting over Med-PaLM 2); (f) divergence below 2%; (g) the acquisition method is contractually or legally barred at the source, so price counsel before the scraper.

Finally, the retired slogan, kept as a condition. Effective accuracy is c*A_small + (1-c)*A0 only if you fall back to the baseline off the curated surface. In a context window you usually hold both sets, so effective accuracy is c*A_small + (1-c)*A_big, which is at least A_big for every c>0: the small clean set is never worse, and there is no threshold at all. A threshold exists only where the two are mutually exclusive, which for retrieval they rarely are. Under that exclusivity, with A_small=0.99, A0=0.55 and A_big=0.60 (all assumed), break-even coverage is (0.60-0.55)/(0.99-0.55) = 11.4%; at A_big=0.65 it doubles to 22.7%. So the answer is coverage, not row count, and it is dominated by how good the generic baseline already is on your surface, which you can measure.

The week's work: buy the two prerequisites, an oracle and a per-record cost k; measure r per field with its confidence interval; run the seven rows; compute your three break-evens with k varied across the range your own labour costs imply. Only then decide. If the dataset qualifies, [from-dataset-to-live-workflow](/blog/from-dataset-to-live-workflow) covers what happens next.
`;

export default content;
