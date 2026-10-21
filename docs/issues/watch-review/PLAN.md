# The watcher's AI layer, in shadow

Owner: @Oxshadha. Step 4 of the AI steps agreed on 2026-10-21 (the hybrid watcher). The folder takes its issue number once the issue is opened.

## Why

The attention watch (`AttentionWatchJob`, issue #268) is a set of fixed rules over every stop of every live trip, every minute: cheap, explainable and tested. It cannot read a situation as a whole: which of six raised items the dispatcher should take first, and why in one line. A language model over every trip every minute would cost about 1.8 million calls a day and give different answers to the same input. The hybrid keeps the rules for detection and asks a model only about what the rules already raised, which is a handful of items per depot.

## How it works

1. **Rules detect, as today.** Nothing in the watch changes.
2. **Every 5 minutes, per depot with two or more open items today,** the items go to the model as facts of a fixed shape: a short reference (`A1`, `A2`, never an id), the kind, the severity, the minutes left, the outlet and the vehicle. Codes only, no names or free text; the facts pass `PromptFacts` like every call.
3. **The model answers in a fixed shape:** the references in the order it would handle them, and one line why. The answer is used only when the order holds exactly the references sent and the line names no figure the facts do not hold (`PromptFacts.faithful`); otherwise it is recorded as unfaithful.
4. **Shadow only.** Nothing is shown to anyone. Each call is recorded in `ml.ai_calls` (purpose `watch_review`, prompt version, masked facts, answer, outcome), so the AI health screen shows how it does, and a metric counts how often the model's first item is the rules' first.
5. **Guardrails:** off unless `AI_ENABLED=true` and `AI_WATCH_REVIEW=true`; within the same daily cap as every other call; no transaction open during the call; a failure changes nothing.

## Later, not here

Showing the model's order or line beside "Needs you" once the shadow record shows it adds something the rules' order does not; read-only tools the model can ask for (a store's history, the trip's messages) rather than facts sent up front. Both wait on the shadow results.

## Measured on 2026-10-21

`AiLiveEvaluation` (backend tests, run by hand with `AI_EVAL=1` and the keys of `.env`, never in CI) sends five depots' worth of raised items to each candidate model three times each, once in the rules' order and once shuffled.

- **Two faults found and fixed.** The job masked the whole facts with `PromptFacts.mask`, which cuts at 300 characters, so a depot with more than about three items sent broken JSON and every answer was refused: each code is now masked on its own and the facts are never cut (ML-52). And Groq retired `llama-3.1-8b-instant`, the pinned default, so every call answered 404: the default is now `qwen/qwen3.8-27b`.
- **Results after the fix**, valid answers of 30 and right first item of the valid: `qwen/qwen3.8-27b` (Groq) 25 valid, 25 right, the 5 others refused by Groq's rate limit (429) on the 12-item depot; `google/gemini-2.5-flash-lite` (OpenRouter) 23 valid, 21 right, 5 calls failed and 2 rate-limited, and on one shuffled depot it put a window at risk above a failed stop three times out of three while its own line said "critical first"; Groq's `openai/gpt-oss-20b` and `-120b` spend the 320-token budget on hidden reasoning and come back empty on anything beyond three items. The faithfulness check refused the one line that named a figure not in the facts ("less than 10 minutes").
- **What it says.** Given the rules' order, the model agrees with the rules' first item; shuffled, it mostly finds it again, sometimes not. Nothing yet shows it adds to the rules' order, which is why it stays in shadow and is judged on real depots by the first-item agreement.

## Tests

Domain: the answer accepted only as a permutation of what was sent; the first-item agreement. Integration: a depot with two open items produces one `watch_review` call recorded as used with the stub model, and an answer naming an item never sent is recorded as unfaithful.
