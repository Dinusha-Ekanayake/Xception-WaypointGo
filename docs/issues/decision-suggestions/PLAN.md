# Decision suggestions in the trip's chat

Owner: @Oxshadha, for review by @jv-ransika. Written 2026-10-21 after a strict review of the first proposal; the review's valid points are folded in below. The folder takes its issue number once the issue is opened.

## Why

The trip's chat is where a problem on the road is reported and where the dispatcher decides. Each situation has a short, known list of sensible decisions, so the chat can offer the likely ones as chips, with how often each was chosen there, and one tap opens the decision already filled in. That replaces most phone calls and keeps every decision recorded (rule 8). It does not remove backend work: every decision still needs its command, rules, state moves and tests, as Skip store did (docs/issues/trip-skip/PLAN.md).

## Merged with issue #269 (2026-10-21)

Issue #269 (playbooks, @Dinusha-Ekanayake) already gave each "Needs you" situation its steps and a ready message, in the frontend, with "playbooks an administrator can edit, which need the backend" left to do. Two catalogues for one kind of advice would break rule 5, so this plan is #269's backend: one catalogue on the server (intelligence `Playbooks`) holds the five playbooks unchanged and the report situations of the chat, each with its steps, message and decisions. "Needs you" and the chat both read it. An administrator's editor for it is still #269's.

**Built in the first slice:** `Playbooks` (pure, `PlaybooksTest`), `GET /api/ml/playbooks`, `GET /api/ml/decision-suggestions?deliveryId=&situation=` (`PlaybookQuery`, facts from Execution, Ordering and Reference, scoped to the dispatcher's depots), `ml:RecordSuggestion` into `ml.decision_suggestions`, the chips beside a report in the chat (`ReportSuggestions`), and "Needs you" reading the server's playbooks. Rule R-ML-26; edge cases ML-39 to ML-41. **Built in the second slice:** chances counted from the log once a situation has 20 decided cases in 90 days, smoothed toward the prior, shown as "chosen 21 of 30 times" (`Playbooks.Tally`). The log counts by situation only, so the narrower fallback in "How it works" 3 waits until the log keeps the window and stop state. **Built 2026-10-21, part of PR 4:** the store manager's ready replies follow the store's delivery (`storeReplies` in `shared/messaging/templates.ts`): found closed, "We are open now" or "Deliver on the next day" to the dispatcher; cannot unload, "Space is clear now"; at the door or on the way, a word to its own driver (R-MSG-16); skipped or not delivered, an answer to the dispatcher. In the store's language, logged as ready replies (`storereply.*`, R-MSG-13), never sent by themselves. Not yet: a log row when the dispatcher decided without a chip, the dispatcher's several-store message (rest of PR 4), Jev (PR 5).

## What exists

- **Decisions with commands:** `delivery:SkipStop`, `delivery:EndTrip` (R-EXE-31), `issue:AskDecision` and its answers (FF-09, R-ISS-12 to R-ISS-15), resolving a report (R-MSG-07), and messages.
- **Ready replies (FF-08)** in `frontend/src/shared/messaging/templates.ts`, built from the trip's state. What was offered and picked is logged in `messaging.reply_uses` and the reply-shown migration; `ReplyRankingQuery` (AI-J5 step 1) ranks by acceptance, with slots 2 and 3 shuffled 10% of the time.
- **Jev** behind `intelligence/application/DecisionPort`, in shadow only (AI-J1, `ml.jev_shadow`): it picks one of the options code allows and writes no text.
- **What is missing:** a catalogue of situations and their allowed decisions on the server, a log of what was suggested against what was decided, and outcome measures.

## How it works

1. **Situation.** Code derives it from facts, never from free text: the report type (store closed, cannot unload, breakdown, road blocked, running late, goods damaged), the stop's state (not reached, at the door), the window (inside, closing within 30 minutes, missed), whether the store was already deferred yesterday, chilled goods, and a mall window (R-PLN-14). About 50 to 100 situations.
2. **Allowed decisions.** One list per situation, defined once on the server (rule 5) and checked against the rules that already exist. For example "store closed, at the door, inside the window": Skip to next day, Skip and cancel, Ask the store to receive later, Wait 10 minutes, Call the store. A store deferred yesterday gets a warning on Skip to next day ("no store skipped twice"); a mall stop past its window never offers Wait.
3. **Chance.** Rule priors agreed with @jv-ransika until a situation has 20 decided cases; then the share chosen in that situation, smoothed, borrowing from the broader situation (any "store closed") when the narrow one is thin; later the logistic-regression ranker of AI-J5.
4. **On screen.** At most three chips under the report, never preselected: "Skip to next day · chosen 7 of 10 times here", or "Suggested by the rules" before there is data. A tap opens the decision's form filled in; the reason box stays. Suggestions never act (role-ui-scope.md).
5. **Log.** Each suggestion shown, keyed by the report and the stop's `row_version`, with the decision taken and when (a new table, `intelligence.decision_suggestions`, append only, action keys and ids, no text).
6. **Jev, last.** Only to turn a voice note or free text with no report type into a situation, with a confidence. Shadow first; on only when it beats the rules on the labelled set, and only with @jv-ransika's agreement on the data processing (the gateway is off in production).

## Keeping every screen in step

A decision taken in the chat changes several screens and phones at once, so it rides the change stream (docs/issues/live-refresh/PLAN.md) and has gaps to close there:

| Who | Sees the decision through | Gap and fix |
| --- | --- | --- |
| The dispatcher, every open screen (chat, Live trip, Needs you, Orders) | `announceChange` at once, then the `delivery` and `message` signals | none |
| The skipped store | its outlet stream (`message`, `order`) and the push | none |
| Other dispatchers of the depot | the depot stream | none |
| A suggestion on screen | re-read on `message`, `delivery`, `decision` signals; dropped when the report is no longer the newest about its stop or the stop's version moved | to build with the chips |
| **The driver** | the push only; the driver is on no stream (a vehicle, not a depot) and the run sheet re-reads on its poll | the push for `delivery.skipped` re-reads the run sheet at once; live refresh phase 3 adds a vehicle watch |
| **A driver with no signal** | nothing until reconnect, so they may unload at a store just skipped; the write is refused but the goods are there | Skip from "at the door" and Cancel show "Not seen by the driver yet" (the R-MSG-14 mark) until the driver's phone has read the push, so the dispatcher can phone |

## Measures

Minutes from report to decision; the share of decisions taken in the chat against by phone; outcomes (the order delivered on the next run, a store skipped twice, a complaint, a write-off), not only acceptance of the top chip.

## Pull requests

1. Finish trip-skip: Skip store and End trip beside reports and in the chat's header (inline forms; a dialog cannot sit over the chat drawer).
2. The situation catalogue and allowed decisions as a server contract query, checked against existing rules; chips with rule priors; the suggestion log; the driver's run sheet re-read on the push; the "not seen yet" mark on a skip.
3. Learned chances from the log, with the minimum and the fallback.
4. Store manager reply chips that follow the store's situation, and the dispatcher's several-store message sent as one message per store, each in that store's words.
5. Jev in shadow for voice and free text.

## Decisions for @jv-ransika

1. The rule priors for each situation before there is data.
2. Whether the chips show counts ("7 of 10 times") or only an order.
3. Jev for voice and free text: agreement to run it in shadow, and later to switch it on.
