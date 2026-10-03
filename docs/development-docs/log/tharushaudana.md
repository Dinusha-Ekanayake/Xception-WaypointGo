# Development log: @tharushaudana

@tharushaudana's entries, newest first. Only @tharushaudana adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-04 - feat: the dispatcher's Live tab as in the Figma frames

`feat/dispatcher-live-figma` · @tharushaudana

Needs you (cards most urgent first and the trip board), the timeline, the map with the vehicle panel, and the trip page, from one join of run sheet, loading trip and position (`data/liveDesk.ts`). The header no longer wraps; the map no longer repeats the header's filters; the offline count works. Detail in the [#19 walkthrough](../../issues/019-dispatcher-ui/WALKTHROUGH.md).
Why: Live differed from the Figma in layout and shapes, and had no trip page or recommended actions.
Verified: `npm run typecheck`, `npm test` (149), `npm run build`, dispatcher browser suite (26 passed; the 3 Forecast failures are already on `dev` since 86e6d57).
Open: store and driver messages (Notify store, Send an update, voice, calls, what each outlet was told) need a Notification command; they are drawn and disabled.

---

## 2026-10-03 - feat: the Forecast chart scales to demand, and says how many refrigerated vehicles a day needs

`feat/forecast-dispatcher-scale` · @tharushaudana

`focusScale` draws fleet capacity on the chart only when it is within 1.6 times the busiest week; otherwise the bars follow demand with round ticks and the fleet is stated on the top edge with the peak week's share. The chilled strip does the same and adds the refrigerated vehicles an average day needs under each bar. With the Figma's numbers the chart is unchanged.
Why: on preview the fleet (about 12,150 m³ a week at Peliyagoda) is ten times a week's demand, so every bar was a sliver and the chilled shares (5-16%) were unreadable.
Verified: `npm run typecheck`, `npm test` (129), `npm run build`, dispatcher browser suite (25). Screenshots with preview-like and Figma-like numbers.
Open: nothing.

---

## 2026-10-03 - feat: notifications coloured by tone, issues in red, and an unread count on every bell

`feat/notification-tones-badge` · @tharushaudana

`toneOf` makes anything about an issue urgent, and `TONE_STYLE` gives each tone one edge, tint, dot and label colour, used by the dispatcher, loader and store manager inboxes; info is blue, so only good news is green. `CountBadge` puts the unread count on each bell (99+ above 99) in place of the red dot.
Why: every row read green whatever it said, and only two bells showed that anything was unread, not how much.
Verified: `npm run typecheck`, `npm test` (127), `npm run build`, dispatcher (24), loader (18) and store (32) browser suites.
Open: the driver screens still show a sample count; they get the real one with the driver inbox (#21).

---

## 2026-10-02 - fix(deploy): start the model service

`fix/deploy-start-ml` · @tharushaudana

`deploy.sh` now starts `ml` with the other services.
Why: the preview deploy built the model service image (#103) but `up` names its services, and `ml` was missing, so it never ran and every plan fell back to the deterministic estimate.
Verified: not until the next preview deploy; the script is checked only by deploying.
Open: nothing.

---

## 2026-10-02 - fix(reference): import road conditions in one statement

`fix/reference-import-bulk-series` · @tharushaudana

`ReferenceVersionWriter.writeSeries` inserts traffic speed and road conditions as one statement each over arrays, not one per row.
Why: the preview deploy of #103 failed in `init`: about 11,000 road-condition rows, one round trip each, ran past the import's 15 s transaction deadline on the VPS database. The import rolled back whole and nothing was replaced.
Verified: compile; CI imports the reference data in every integration test.
Open: nothing.

---

## 2026-10-02 - feat(intelligence): serve the Datathon models and score published plans

`16-intelligence` · @tharushaudana

Intelligence module and a Python model service (`ml-server/`, models in Git LFS). Published plans are scored per stop (service minutes, P(late)) and stored with their model; a weekly job forecasts ten weeks of demand. Model registry commands, supply probability (R-RCP-06), a training export, and a deterministic fallback everywhere. Traffic speed and road conditions are now reference data.
Why: issue #16; the Datathon models were trained but nothing in Waypoint used them, and every plan said "without predictor". Decisions in [the plan](../issues/016-intelligence/PLAN.md); rules R-ML-01 to 06, cases ML-01 to 08, A-36 to A-38, P-28, P-29.
Verified: `ml-server` pytest (11, including exact equality with the vendored inference and the Task 2A submission); 17 domain tests, `ModuleBoundaryTest`, `EventCatalogueTest`, application start; frontend typecheck. The backend integration tests need CI's database.
Open: the screens (#18, #19, #22); `git-lfs` on the VPS; no retraining pipeline; road conditions past 2026-06-28.

---

## 2026-10-02 - feat(notification): route events to people, with an inbox and web push

`14-notification` · @tharushaudana

Notification module, backend only: 18 event consumers, a versioned routing table (`notification.routing_rules`), inbox and unread count, a live count over server-sent events, `MarkRead`, `MarkAllRead`, `Subscribe`, `Unsubscribe`, and a push job with retry and dead letter. Web push encryption and VAPID are written on the JDK. Identity gains a dated `recipientsFor`, so tomorrow's plan reaches tomorrow's driver.
Why: issue #14; the store, driver and dispatcher screens had nothing behind their notification placeholders. Decisions are in [the plan](../issues/014-notification/PLAN.md); rules R-NOT-06 to 09, cases NOT-01 to 09, A-34, A-35, P-27.
Verified: domain and crypto tests (27, including the RFC 8291 vector), `ModuleBoundaryTest`, `EventCatalogueTest`, frontend typecheck and `npm test`. The 26 database integration tests pass in CI (`mvn verify`, 681 tests, none skipped).
Open: each role UI places its own inbox, badge and push opt-in (#18, #19, #21); no admin API for a new routing version; dock and next planned date are missing from their events.
