# 06. Intelligence: Jev, LLM and agents, Phase 5

Where language and decision models help, and where they do not.

**When this phase starts.** Only after:

- the Kandy pilot go/no-go;
- signed data processing agreements with every AI and speech vendor.

**Until then:**

- issue triage uses code rules inside FF-03;
- reply suggestions use rules (FF-08).

**The cheapest answer first.** Every decision goes through this cascade:

1. **Code rules.**
2. **A small classic model** trained on our own data (logistic regression, TF-IDF).
3. **Jev** for typed decisions on free text.
4. **An LLM**, only when the task needs written text.
5. **A person**, whenever confidence is low.

## When to use what

| Task | Use | Why |
| --- | --- | --- |
| Late risk | Logistic regression, then LightGBM | Calibrated, cheap, explainable ([05](05-DATATHON-MODELS.md)) |
| Arrival time | Code (propagation) | Exact, no model needed |
| Message intent | Templates, then a TF-IDF logistic regression | Handles romanised Sinhala better than an English-first model |
| Personal data in text | Regex (phone, NIC) and a dictionary | Deterministic, auditable |
| Blurry or dark photo | On-device check (M-02) | No upload, no vendor |
| Why an order cannot fit | The solver | It knows the constraints exactly |
| Complaint detection, free-text triage | Jev | Typed choice from free text, calibrated, about USD 0.03 a day |
| Translation, explanation, shift brief | LLM | Needs written text |

## Jev in brief

**What it is.** Jev is TypeSafe AI's first "System One" decision model, launched 15 September 2026.

**How it answers.** It does not write text. Given a `state` text and typed questions, it returns calibrated answers:

| Question type | Returns |
| --- | --- |
| Choice | One of up to 255 options, with per-option probabilities and a confidence |
| Score | A position on ordered levels |
| Noul | The probability of yes |

**Access.**

- OpenRouter model `typesafe/jev-1.13`.
- Endpoint: the alpha Decisions API (`POST /api/alpha/decisions`) or `POST /api/v1/systemone`.
- Auth: the OpenRouter key.

| Property | Value |
| --- | --- |
| Price | USD 0.042 per million input tokens; output free |
| Latency | 70-500 ms |
| Context | 32k tokens on OpenRouter |

**Strengths.** Classification, routing, scoring and guardrails, where code consumes the answer.

**Limits:**

- unreliable at counting, arithmetic and comparing dates (keep those in code);
- bias toward the first option (shuffle the options and check stability);
- no free text, text input only;
- English is the most accurate language, and others are "handled but not equally well".

**Vendor risk.** A new vendor on an alpha endpoint. It stays behind a port with a rules fallback, at a pinned version.

**Volume.** At about 2,000 short texts a day of about 300 tokens, Jev costs about USD 0.03 a day. Cost is not the constraint; data protection is.

## AI-0 The gateway

Recorded as ADR-005.

**Ports.**

- `DecisionPort` uses Jev, pinned to `typesafe/jev-1.13`, never `~jev-latest`. Thresholds hold only for the version they were tuned on.
- `LlmGateway` uses a pinned, cost-effective model. Default: Gemini 2.5 Flash-Lite through OpenRouter, USD 0.10 in and 0.40 out per million tokens.
- Prompts are capped at 2,000 tokens.

**One key.** One OpenRouter key per environment. Its credit limit is the hard spend cap, and there is a kill switch.

**How calls run.** A consumer inserts a pending job. The F-09a worker calls the port with no transaction open and stores the answer in a new transaction.

**What is stored.** The model id, probabilities and confidence (the R-ML-02 pattern).

**Environments.** A stub port in judge and CI ([environment matrix](README.md#environment-matrix)).

**Privacy.**

- Before any call, code masks names, phone numbers, NIC numbers and outlet identifiers.
- Store and driver text is treated as untrusted input.
- Agents may only draft and read; they never send or change anything.
- Prompts and outputs are audited, and fall under P-33 retention.

**Degrade.** The screen says "Suggestions: rules only".

**Proof before going live.** Every item must show a KPI gain in shadow before it goes live (P-AI-1).

## Items

### AI-J1 Issue triage

**Where the type comes from.** The kind card in the report sheet (FF-01). Jev classifies only voice transcripts or free text with no kind chosen. Choice over the issue types.

**Severity.** "Needs the dispatcher now" comes from code rules: vehicle fault, emergency, Tech goods, chilled goods. No model can lower an emergency.

**Gate.**

- **Labelled set:** 400 or more labelled items, at least 25 per class, split half for tuning and half for test.
- **Option-order stability:** three option orders averaged; top-1 agreement across orders 95% or more.
- **Threshold:** chosen on the tuning half by the cost of a misroute.
- **Beat the baseline:** must beat a TF-IDF character n-gram logistic regression.

**KPI.** Median minutes from issue to decision.

### AI-J5 Reply ranking for FF-08

**Step 1.** A logistic-regression acceptance ranker trained on the FF-08 logs. The second and third slots are randomised 10% of the time, to remove position bias.

**Step 2.** Jev ranks the code-allowed set only if step 1's top-1 acceptance stays under 50% for four weeks.

- Options are shuffled.
- A Noul "reply needed?" marks threads waiting on the dispatcher.

**Storage.** The result goes in `intelligence.reply_suggestions`, keyed by the last message id. The composer uses it only if that id is still the latest; otherwise it uses the rule ranking.

**Ownership.** Intelligence owns the ranking as a contract query; Messaging owns the templates.

**Never.** An LLM is never used to pick a reply or fill a slot.

### AI-1 Plan explainer and what-if

**Who does what.** The solver computes; the LLM explains the difference in words.

**Questions it answers:**

- "Why is OUT045 deferred?"
- "What if VEH014 comes back from the workshop?"

**Rule.** Every number in the answer must exist in the facts given to it, checked by code before it is shown.

**Shares with T2B.** The what-if runs, from the T2B backlog.

### AI-2 Why an order cannot fit

**Who does what.** The solver finds which constraints the order breaks and the smallest change that would let it fit. The LLM only words it.

**First.** A specification is written before building.

### Later

| Item | Why later |
| --- | --- |
| AI-J2 complaint detection on channel posts | Code checks plus the manual redirect cover version 1 |
| AI-J3 intent routing | Templates already carry their intent |
| AI-3 translation of transcripts | Needs FF-05 |
| AI-7 shift brief, including a conversation summary per trip | Useful at shift handover |

### Cut

| Item | Why |
| --- | --- |
| AI-J4 guardrail and context ranking | Masking in code and the IAM allowlist do this better; an alpha model is not a security control |
| AI-8 AI photo check | Jev reads text only, and an LLM check would send photos abroad; replaced by the on-device check |
| AI-4 auto-actions | Covered by T1-02 |
| AI-5 capacity narration | Covered by T2-01 |
| AI-6 ETA | Replaced by T1-03 |
| AI-9 store order suggestion | Stores tend to over-order |

## Sources

- [Introducing System One models and Jev](https://typesafe.ai/blog/introducing-system-one-models-and-jev)
- [Jev on OpenRouter](https://openrouter.ai/docs/guides/community/jev)
- [TypeSafe Jev review (eesel)](https://www.eesel.ai/blog/typesafe-jev-review)
- [Jev explained (DataCamp)](https://www.datacamp.com/blog/system-one-models-jev)
- [Jev release guide (Developers Digest)](https://www.developersdigest.tech/blog/typesafe-jev-system-one-models-release-guide-2026)
- [Microsoft OptiGuide: LLMs explaining optimisation](https://www.microsoft.com/en-us/research/publication/large-language-models-for-supply-chain-optimization/)
- [LLM infeasibility diagnosis for routing](https://arxiv.org/html/2508.03406v1)
- [Gartner: 40% of agentic AI projects cancelled by 2027](https://www.techcentral.ie/gartner-40-of-agentic-ai-projects-flop/)
- [Gemini 2.5 Flash-Lite pricing on OpenRouter](https://openrouter.ai/google/gemini-2.5-flash-lite/pricing)
