# 08. Business case: value, cost and revenue

This is for the people who fund and run Waypoint Dispatch. It answers:

- What does the roadmap deliver, and what is it worth?
- What does it cost to build and to run?
- How does it compare with buying a product?
- Could it earn revenue beyond this group?

**About the figures.**
- They come from web research done on 5 October 2026. Every number has a source or is marked **ESTIMATE**.
- Rates: LKR 328 per USD ([Daily Mirror](https://www.dailymirror.lk/business-news/Central-Banks-dollar-buying-hits-29-month-high-in-August-at-US-579mn/273-349598)). Diesel LKR 392 per litre, CPC from 1 October 2026 ([Ada Derana](https://adaderana.lk/news/cmuod7f0c000r356piseqgj1q)).
- Finance must confirm the distance run, store sales and hire rates. The pilot then replaces each estimate with a measured number (B-05).

## 1. Summary

| | Conservative | Expected | Optimistic |
| --- | --- | --- | --- |
| Value per year at full rollout | LKR 11.5m (USD 35k) | **LKR 26.5m (USD 81k)** | LKR 50.8m (USD 155k) |
| Payback after full rollout | about 11 months | **about 5 months** | about 3 months |

**Costs:**

| Cost | Amount |
| --- | --- |
| Build | USD 25-36k one-off (LKR 8-12m), for 4 engineers and half an ML engineer over 22 weeks |
| Running | about USD 58 a month on the recommended setup, under the USD 100 ceiling |
| Maintenance | about USD 10k a year (three quarters of an engineer) |
| **Total over three years** | **about USD 62k** |

**Return:**
- Expected net gain over three years is about USD 180k, roughly 3.9 times the cost.
- The conservative case still gains about USD 43k.
- Value ramps up: the Kandy pilot starts in week 6 and Peliyagoda follows after the go/no-go, so year one delivers about 40% of the annual figure.

## 2. Where the value comes from

LKR millions a year (ESTIMATE): conservative / expected / optimistic.

| Lever | Value | How it is estimated | Delivered by |
| --- | --- | --- | --- |
| Fuel from better routing and consolidation | 4.5 / 11.0 / 20.0 | 5 / 10 / 15% of about 230-340k litres a year. Routing studies report 5-20% transport cost cuts; UPS ORION saved 10 million gallons a year | Existing planner, pilot |
| Fewer vehicle-days | 1.4 / 2.7 / 5.4 | 3 / 6 / 12% fewer, at LKR 5,800 crew cost per day; a grocery case cut vehicles needed by 12% | Planner, T1-04 |
| Festival truck hires avoided | 0.5 / 1.1 / 1.8 | 15 / 30 / 50% of about 120 hire-days; hire rate LKR 30,000 a day is **unverified** | T2-01 |
| Dispatcher planning time | 0.5 / 0.9 / 1.4 | 2 / 4 / 6 hours a day per depot | Existing planner, FF-08 |
| Paperwork | 0.2 / 0.4 / 0.7 | 1 / 1.7 / 3 hours a day per depot | F items, FF-01, FF-02 |
| Phone calls | 0.2 / 0.5 / 0.7 | 15 / 30 / 45% fewer | FF-04, FF-08, T1-03 |
| Fresh before 08:00 (lost sales and spoilage) | 2.2 / 6.0 / 13.9 | +3 / +5 / +8 points on time; fresh spoilage runs 4-9% of sales | T1-01, Phase 4 |
| Damaged-goods write-offs | 1.2 / 2.4 / 3.6 | 10 / 20 / 30% of about LKR 12m a year | FF-02, FF-03, M-02 |
| Dispute handling | 0.3 / 0.5 / 0.7 | 40 / 60 / 80% fewer disputes with photo and PIN proof | FF-03 |
| Fuel-log reconciliation | 0.5 / 1.1 / 2.7 | 0.5 / 1 / 2% of fuel spend (ASSUMPTION) | FF-06a |
| **Total** | **11.5 / 26.5 / 50.8** | | |

Fuel and Fresh on-time are about 64% of the expected value, so the pilot measures those first. Labour savings are small because wages are low; the value is in service and fuel.

## 3. What gets better for people

| Role | Today | After the roadmap | Measured by |
| --- | --- | --- | --- |
| Store manager | Calls to ask where goods are; disputes rest on memory | Expected arrival and handling time; a notice when an order will be late; receipt with photos and a PIN; stays in the trip channel until goods arrive | Receipt confirmed within 5 minutes of handover; "where is it" calls down 30% |
| Dispatcher | Spreadsheets and calls; no view after departure | One live view; risk shown before publishing; capacity weeks ahead; ready replies with facts filled in | Reply time under 2 minutes, 03:30-08:00; calls per trip down 30% |
| Loader | Printed lists that go stale | One report sheet with photos, in Sinhala or Tamil | 90% or more of damaged lines photographed before release |
| Driver | Paper run sheet, phone calls | Works with no signal; Fuel Pass and fuel log on the phone; nothing to tap while driving | 90% or more of stops recorded in the app; zero lost records |

Research on technology adoption finds that perceived usefulness predicts use more strongly than ease of use. So the rollout leads with the features that remove phone calls.

## 4. What gets simpler

**For the business:**
- One system replaces spreadsheets, calls and paper run sheets.
- A deferral carries its reason.
- A dispute rests on photos and a PIN.
- Capacity is known weeks ahead, not on the morning.

**For engineering:**
- One server.
- Postgres for queues and caching, with no Redis or RabbitMQ.
- Machine learning on CPU.
- The cheapest answer first: code, then a small model, then Jev, then an LLM.

## 5. Running cost per month

USD, ESTIMATE.

| Line | Lean | Recommended | Scaled out |
| --- | --- | --- | --- |
| Server | Contabo VPS 8 (8 vCPU, 24 GB), about 16 | Hetzner CX43 (8 vCPU, 16 GB), about 20 | Two Lightsail 8 GB, 88, plus managed Postgres, 120 |
| Object storage (R2) | 0 | 1 | 3 |
| Off-server backups | 0.5 | 5 | 5 |
| Jev (about 2,000 short texts a day) | 0.8 | 0.8 | 0.8 |
| LLM (Gemini 2.5 Flash-Lite, about 100 calls a day) | 1 | 2 | 3 |
| Speech-to-text | 0 | about 10 (Google Chirp, only notes with no kind chosen, next-day batch where possible) | 71 (every note) |
| SMS (optional; web push is free) | 0 | 14 | 49 |
| Domain and email | 5.5 | 5.5 | 5.5 |
| **Total** | **about 24** | **about 58** | **about 365** |

**Cost traps to avoid:**
- Hetzner's CPX servers rose about 2.7 times in June 2026.
- Sending every voice note to cloud speech-to-text.
- Replicas before they are needed.
- Logging every GPS ping.
- Long LLM prompts.

**Unit cost:**
- Recommended setup: about USD 0.97 per vehicle per month, or USD 0.01 (about LKR 3.2) per delivery.
- Including build and maintenance over three years: about USD 28.7 per vehicle per month, or USD 0.29 per delivery.

## 6. Build or buy

Three years, ESTIMATE.

| Option | Cost | What it does not cover |
| --- | --- | --- |
| **Build (this roadmap)** | **about USD 62k** | nothing in scope |
| Routific | USD 20-26k | Route planning only |
| Shipday | about USD 59k | Multi-brand allocation, offline Sinhala and Tamil, store receipt and disputes, Fuel Pass quota |
| Onfleet Enterprise (needed for multi-brand) | USD 112k or more | as above |
| Track-POD | USD 106-192k | as above |

Every bought option also needs about USD 19k of integration work, and none covers:
- multi-brand allocation with explained deferrals;
- reefer, van and mall constraints;
- offline work in Sinhala and Tamil;
- store receipt and disputes;
- the Fuel Pass quota;
- the trained models;
- keeping data in Sri Lanka under the PDPA.

Building costs the same range as the cheaper products and fits the operation.

## 7. Revenue model

**Recommended:** a hybrid of a one-off licence and implementation fee plus a monthly subscription per vehicle. The group is the reference customer.

| Tier | Price per vehicle per month | Includes |
| --- | --- | --- |
| Core | USD 15 (LKR 4,950) | Dispatch, offline driver app, proof of delivery, store receipt, Sinhala and Tamil |
| Pro | USD 25 (LKR 8,250) | Core plus constrained allocation with explained deferrals, reefer windows, fuel quota, issues, trip voice channel |
| Enterprise | USD 40 (LKR 13,200) | Pro plus model-based arrival times, late risk and forecast; capped AI features; single sign-on; service levels; hosting in Sri Lanka |

**Terms and margin:**
- Minimum 10 vehicles.
- Implementation USD 3-15k one-off.
- Prices sit under Detrack Advanced (USD 39 per driver) and far under Locus or FarEye (about USD 100k a year).
- Gross margin on subscription is about 95% at about USD 1 running cost per vehicle (ESTIMATE).

**Market (ESTIMATE):**
- About 300 Sri Lankan supermarket chains, manufacturers and distributors run fleets of 10 or more vehicles, about 20 each. That is about 6,000 vehicles, or about **USD 1.6m a year** within reach.
- Modern trade is 15-18% of consumer goods sales; the rest moves through distributors.
- Bangladesh, Pakistan and the Maldives come later.

**Three-year projection (ESTIMATE)**

Assumptions: about USD 24 average per vehicle per month, USD 5k implementation per customer, 10% churn.

| Year | Customers | Vehicles | Revenue |
| --- | --- | --- | --- |
| 1 | 5 | 125 | USD 44k (LKR 14.5m) |
| 2 | 14 | 420 | USD 128k |
| 3 | 28 | 980 | USD 277k (LKR 92m) |

**Before selling to anyone else:**
- The system needs multi-tenancy. Row-level security today is per person, not per customer company.
- That work is **not in this roadmap**. It is a Phase 6 decision, taken after the pilot shows the value.

**Go to market:**
- The Kandy pilot results are the proof.
- Sell first to second-tier supermarket chains and consumer-goods distributors.
- Partner with local sales-force tool vendors (Sandbox, iForce), since Waypoint does not take field orders.
- Expect a 6-9 month sales cycle.
- Local competition includes RouteSONAR.

## 8. Risks

| Risk | Answer |
| --- | --- |
| The PDPA takes effect 1 January 2027, and the 2025 amendment covers cross-border transfer | P-PD-1: data processing agreements and masking before any cloud AI or speech-to-text; hosting in Sri Lanka as an Enterprise option |
| The rupee fell 7.9% in the first half of 2026, while cloud and AI costs are in USD | Invoice in LKR with a yearly USD indexation clause; annual prepayment; AI spend capped per tier |
| The fuel quota in the data disagrees with the National Fuel Pass | P-FUEL-Q: Finance confirms; quotas stay configuration |
| The value figures are estimates | Each lever is replaced by measured B-05 and pilot numbers before the Peliyagoda rollout and before any external sale |

## 9. What this research changed in the plan

| Change | Where |
| --- | --- |
| Container memory limits so everything fits one 16 GB server; scale up before scaling out | F-15 in [02](02-FOUNDATION-FIXES.md#f-15-container-memory-limits) |
| Voice notes re-encoded to Opus (2 MB to about 80 KB); speech-to-text only for notes with no kind chosen, 15 s or less, batch tier when not urgent | M-02, FF-05 |
| Self-hosted Whisper fails on Sinhala, so speech-to-text will likely be Google Chirp, after a data processing agreement | FF-05 |
| Default LLM is Gemini 2.5 Flash-Lite through OpenRouter, pinned, prompts capped at 2,000 tokens | AI-0 |
| Never log each GPS ping; no per-vehicle labels in the log store | F-12, observability |
| Phones fetching media straight from the bucket was proposed and **rejected**: streaming keeps access control and audit in one place | M-01 |

## Sources

**Competitor pricing:**
- [Onfleet](https://onfleet.com/pricing)
- [Routific](https://www.routific.com/pricing)
- [Detrack](https://www.detrack.com/pricing/)
- [OptimoRoute](https://optimoroute.com/pricing/)
- [Upper](https://www.upperinc.com/pricing/)
- [Shipday](https://www.shipday.com/pricing)
- [Tookan](https://jungleworks.com/tookan/pricing/)
- [Locus pricing](https://locus.sh/blogs/locus-pricing-2026/)
- [Track-POD](https://costbench.com/software/fleet-management/track-pod/)

**Market:**
- [Cargills](https://en.wikipedia.org/wiki/Cargills_(Ceylon))
- [Keells](https://en.wikipedia.org/wiki/Keells)
- [Laugfs supermarkets](https://www.sundaytimes.lk/260802/business-times/100-laugfs-supermarket-outlets-650666.html)
- [Sri Lanka freight market](https://www.mordorintelligence.com/industry-reports/sri-lanka-freight-and-logistics-market)
- [Vehicle registrations](https://www.ft.lk/front-page/First-five-months-vehicle-registrations-top-73-000/44-777928)

**Fuel and Fuel Pass:**
- [Fuel Pass reintroduced](https://english.newsfirst.lk/2026/3/15/sri-lanka-reintroduces-qr-based-national-fuel-pass-system)
- [Weekly limits](https://newsfirst.lk/2026/03/15/qr-weekly-fuel-limits-motorcycles-5l-cars-15l)
- [Revised quotas](https://english.newsfirst.lk/2026/03/21/weekly-fuel-quotas-revised-cpc)

**Routing value:**
- [Toth and Vigo summary](https://file.scirp.org/Html/2-1040087_19928.htm)
- [UPS ORION](https://www.ttnews.com/articles/ups-routing-program-orion-helps-drivers-trim-miles-reduce-costs)
- [Grocery routing case](https://epg.com/?p=196508)

**Wages:**
- [Lorry driver](https://www.paylab.com/lk/salaryinfo/transport-haulage-logistics/lorry-driver?lang=en)
- [Warehouse staff](https://www.paylab.com/lk/salaryinfo/transport-haulage-logistics/warehouseman)
- [Dispatch clerk](https://www.paylab.com/lk/salaryinfo/transport-haulage-logistics/dispatch-clerk?lang=en)
- [Minimum wage 2026](https://wageindicator.org/work/minimum-wage/updates/2026/minimum-wage-updated-in-sri-lanka-from-01-january-2026-january-01-2026/)
- [IT salaries](https://itpro.lk/salary-sri-lanka-it-jobs/)
- [Levels.fyi Sri Lanka](https://www.levels.fyi/en-gb/t/software-engineer/levels/senior/locations/sri-lanka)

**Service and proof of delivery:**
- [Fresh department value](https://www.fmi.org/blog/view/fmi-blog/2025/08/15/four-stats-that-prove-the-value-of-fresh-food-departments)
- [Out-of-stock study](https://www.supermarketnews.com/mergers-acquisitions/new-gma-study-shows-rising-incidence-of-out-of-stocks)
- [Spoilage cost](https://eightx.co/blog/food-bev-spoilage-cost)
- [Electronic proof of delivery](https://www.fretron.com/blog/glossary-epod/)
- [Transport manager disputes survey](https://www.logisticsit.com/articles/2025/10/09/research-reveals-over-60-of-transport-managers-have-been-in-stand-off-with-a-customer-over-delivery-issue)
- [Delivery tracking calls](https://www.bringg.com/resources/real-time-delivery-tracking)
- [Adoption meta-analysis](https://www.scienceopen.com/document/vid/330fa5f4-c3bc-47d2-b16f-7c1121da4a9b)

**Running cost:**
- [Hetzner price changes](https://northflank.com/blog/hetzner-cloud-server-price-increases)
- [Contabo](https://contabo.com/en/vps/)
- [Lightsail](https://aws.amazon.com/lightsail/pricing/)
- [Cloudflare R2](https://developers.cloudflare.com/r2/pricing/)
- [Backblaze B2](https://www.backblaze.com/cloud-storage/pricing)
- [Jev pricing](https://openrouter.ai/blog/insights/what-is-jev/)
- [Gemini Flash-Lite](https://openrouter.ai/google/gemini-2.5-flash-lite/pricing)
- [Google Speech-to-Text pricing](https://convertaudiototext.com/blog/google-cloud-speech-to-text-pricing-2026)
- [SMS in Sri Lanka](https://text.lk/pricing/)
- [Offshore rates](https://www.secondtalent.com/developer-rate-card/offshore-software-development/)

**Law and currency:**
- [PDPA effective 1 January 2027](https://www.ft.lk/front-page/Data-protection-compliance-regime-takes-effect-on-1-Jan-2027/44-795776)
- [PDPA cross-border notes](https://concentric.ai/sri-lankas-pdpa-takes-effect-january-1-2027-what-global-enterprises-need-to-know/)
- [Rupee in the first half of 2026](https://ground.news/article/sri-lanka-rupee-depreciates-79-pct-in-h1-2026-as-central-bank-intervenes)
- [Exchange rate](https://tradingeconomics.com/sri-lanka/currency)
