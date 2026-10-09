# 04. Field features: Phase 3

Features for the four field roles, built on the existing modules. Each reuses what is there and changes only what it must. Rule and ADR changes below are **proposals**; they move into the canonical documents with the pull request that builds them.

Order inside the phase:

1. FF-00 (language) and FF-01 (shared report sheet).
2. FF-02 (loader photos), then FF-03 (inquiry).
3. FF-04 (trip channel), then FF-08 (reply suggestions).
4. FF-06b (Fuel Pass) after its step 0.
5. FF-05 (transcription) as a spike only.
6. FF-07 (profile photos) after the pilot.

## FF-00 Language

- **Goal:** the field roles work in Sinhala and Tamil.
- **Scope:**
  - Message templates (FF-08) in si, ta and en.
  - The driver's critical screens in si and ta: Home, run sheet, stop, proof, handover, issue. The full driver UI is not translated, to keep the browser suites from multiplying.
  - The loader and store drafts already exist. A native speaker reviews them before the pilot.
- **Reuse:** the per-role string tables (`roles/store/data/strings.ts`, the loader's i18n), and dates, times and counts from `frontend/src/shared/wording`.
- **KPI:** share of stops recorded in the app.
- **Done:** a native-speaker sign-off is recorded for each language. **Recorded 2026-10-09:** the driver, loader and store Sinhala and Tamil reviewed by a native speaker (confirmed by @Oxshadha).

**Scope decided 2026-10-08 (@Oxshadha), revised 2026-10-09:** Sinhala and Tamil for the driver, the loader and the store manager, complete in each, because a screen half in one language is worse than either; the dispatcher stays in English.

**Loader and store completed 2026-10-09:** the loader's last gaps (the sync state read to a screen reader, two labels, two rows that dropped a value) and the store's: about 300 texts on 25 screens, the helpers that build sentences with a value (`planNote`, `movedWhy`, `issueCard`, `makeUpSteps`, `countStatus`, `conflictMessage`) taking the screen's language, and the unit and line counts through `useCounts()` instead of the shared English ones. `tests/role-strings.test.ts` holds both roles to the driver's rule, including the labels a screen draws by value; `e2e-store/language.spec.ts` receives a delivery in each language and checks the same command is sent. Dates stay as the shared wording writes them; product names and the dispatcher's message bodies stay as written.

**Driver built 2026-10-08:** every driver screen follows the language chosen in Settings (the same choice that already picked the read-aloud voice): Home, the run sheet and map, a stop, arriving, the handover and PIN, the proof, the delivery report, the problem report, driving mode, the run summary and the sheets. 337 texts in `roles/driver/data/strings.ts`, English as the key, the loader's wording reused where the English is the same (stop නැවතුම / நிறுத்தம், trip ගමන / பயணம்). What the server writes stays as written: notification bodies, refusals in its own words, outlet and vehicle names. A text that is compared in code (a gap under its field, a stop's outcome) is compared in English and translated only where drawn. `tests/driver-strings.test.ts` fails on any text a driver screen translates without a Sinhala and a Tamil row, and on a translation that drops a value; `e2e-driver/language.spec.ts` works a stop in each language and checks the commands sent are unchanged. **Drafts awaiting the native speaker's review.** Left: reply templates in the reader's language (a reply is stored as English text, so a driver reads dispatch's ready replies in English).

## FF-01 Shared report sheet

**Status.** Built 2026-10-09 as a shared core, not one screen. The loader's shortfall sheet and the store's report and receive screens take photos through one flow, `shared/media/useTakenPhotos.ts` (M-02 encoding, the blur and darkness check, previews released on close; the store's report sheet leaked them), and each keeps its own command. One visual sheet was not built: the loader's tablet sheet and the store's phone sheet differ in look and in what they pick (manifest items by line against product lines), and changing the loader's look needs J.V.'s agreement under the role rule.

**As planned:**

- **Goal:** loader and store report damaged, short or missing goods with the same screen. Each keeps its own business command.
- **Change:**
  - New `frontend/src/shared/ui/report/ReportSheet` with:
    - kind cards;
    - order and line picker;
    - unit stepper;
    - reason;
    - `PhotoPicker`, using the M-02 encoder;
    - optional voice note (reuses `VoiceCapture`).
  - Each role supplies a submit adapter:

    | Role | Command |
    | --- | --- |
    | Loader | `loading:Shortfall` (release gating stays in Loading) |
    | Store | `issue:Raise`, or a receipt command while counting |

- **Replaces:** loader `roles/loader/screens/IssueSheet.tsx`; store `roles/store/screens/issues/ReportIssue.tsx` and `receive/ReportCard.tsx`.
- **Backend:** unchanged. Both paths already end in the Issues module.
- **Done:** one component, two adapters, the existing loader and store browser specs pass.

## FF-02 Loader damaged-goods photos

**Built 2026-10-07** (R-ISS-10, ISS-16). An optional photo on the loader's existing issue sheet, rather than a new shared sheet: the loader screens change only to close a gap (AGENTS.md, role UI scope), so FF-01 is kept to the store until a loader redesign is agreed. One photo per report today; the rule allows up to 10. The Sinhala and Tamil strings are drafts for the FF-00 review.

- **Goal:** a loader's damaged or short line carries photo evidence into the issue. This is the "left the dock intact" record for disputes.
- **Change:**
  - Migration grants `issue:AttachPhoto` to the loader policy.
  - The attachment insert rule becomes `(app.actor_has_depot(depot) AND app.actor_holds_role('loader')) OR app.actor_has_outlet(outlet)`. Drivers, who also hold depot scope, stay excluded.
  - `LoadingShortfall` carries `photoAttachmentIds`, and `IssuesConsumers` links them to the issue.
  - Photos upload through the existing queued uploads (`frontend/src/shared/offline/uploads.ts`). They are stored in the database first and move to the bucket with M-01.
- **Policy:** P-DG-2. The loader photographs Tech lines and any damaged line before release.
- **New rule R-ISS-09:** a loading shortfall may carry up to 10 photos, readable by whoever may read the issue.
- **Edge case:** photo arrives before the issue, and the reverse; both must link.
- **Tests:** denied-scope test (a driver cannot attach to a depot issue).
- **KPI:** 90% or more of damaged lines photographed before release; write-off value.

## FF-03 Operations inquiry

**Built 2026-10-07** (R-ISS-11, ISS-17). Roles `ops_inquiry` and `ops_head` with policy `WaypointOpsInquiry` (issue:Read and the two commands only); the Inquiries workspace lists the depot's damaged, short and disputed goods with photos, history and write-offs. No new SQL function was needed: issues and their photos are already read by depot scope, while trips, deliveries and messages need `app.actor_oversees_depot`, which these roles are not part of, and every other action is outside their policy. The depot limit is a parameter, LKR 25,000 until Finance confirms (P-DG-1). Not yet: the audit trail panel and a role address (`ops.`), which needs the nginx and certificate change.

- **Goal:** an operations person reviews a damaged-goods case with its photos, history and audit trail, and records the outcome. The inquiry itself happens between people; the system records it.
- **Access:**
  - New role `ops_inquiry`, depot-scoped.
  - New SQL function `app.actor_inquires_depot(depot)` = `actor_has_depot(depot) AND actor_holds_role('ops_inquiry')`. It is used **only** in the read policies for issues, issue attachments and the audit read.
  - `app.actor_oversees_depot` is not changed. It already gates trips, delivery records, vehicle reports and messages, which this role must not see.
- **Screen** (admin console area). It reuses the `AuditConsole.tsx` patterns and shows:
  - the issue list and detail;
  - the history (`/api/issues/{id}/history`);
  - a photo grid with a full-size viewer;
  - the linked report messages and the dispatcher's decision;
  - the audit trail (`/api/audit?targetType=issue&targetId=`, `/api/audit/decisions/{commandId}`).
- **New commands**, each with an IAM catalogue row and a policy version for `ops_inquiry`:

  | Command | What it does |
  | --- | --- |
  | `issue:RecordInquiry` | Appends an outcome to the issue history: actor, reason, time, **images**, and a write-off value entered by hand (the catalogue has no prices) |
  | `issue:ApproveWriteOff` | Approval by the threshold in P-DG-1 |

- **Domain rule:** the approver is not the person who decided the delivery.
- **Tests:** denied scope (another depot); denied write (ops inquiry cannot assign or resolve).
- **KPI:** median minutes from issue to decision; write-off value.

## FF-04 Live trip channel

**Built 2026-10-07** (R-MSG-08, MSG-16, MSG-17), and the driver safety gate is FF-04b below. Built as below with four differences: the channel opens when the driver starts the trip after loading, not at publication (decided with the product owner 2026-10-07); the loaders keep their own lane on the thread rather than leaving it (the loader screens change only to close a gap), an outlet that a revision moves off the trip goes read only in the channel, and the thread still refreshes as it did until the single event stream carries it. Routing version 9 sends a channel post to the stores still active. The draft check looks for another store's code (`OUT063`) and for an order number; the data has no store names to match.

**Goal.** During a trip, the dispatcher and the stores still waiting for their goods coordinate in one place, by text or voice. The driver is driving, so the driver is **not** part of that conversation: the driver reads and hears only what is addressed to the driver (decided 2026-10-06).

### Proposed amendment to ADR-004 and MODULES §13

> A trip's thread has a **channel** for live coordination.
>
> **Members:**
> - the depot's dispatcher;
> - the vehicle's driver on the service date;
> - the store managers of the trip's outlets whose deliveries on the trip are not all done.
>
> Loaders are no longer members. Their issues reach the dispatcher through Issues, and they see the decision on their issue list. This narrows the thread, so it amends the rule that threads are never narrowed.

### Rules (proposed changes to R-MSG-01 to R-MSG-06 and R-NOT-14)

**Membership** (`messaging.thread_outlets(thread_id, outlet_id, state, read_only_at)`):
- An outlet becomes active on `plan.published` or `plan.revised`.
- It becomes read-only only when **all** its deliveries on the trip are completed or failed (`delivery.completed`, `delivery.failed`).
- It never reverts. Events from different aggregates arrive in no guaranteed order.
- Membership is held per outlet, so a change to a person's scope applies at once.

**Posting:**

| Who | May post to `channel` | May post privately |
| --- | --- | --- |
| Dispatcher | Yes | To a single outlet or to the driver |
| Active store manager | Yes | To the dispatcher (`dispatch` audience, unchanged) |
| Driver | No, and does not read it either | An issue to the dispatcher only, typed or voice. Reads and hears only messages addressed to the driver, dispatcher messages to everyone on the trip, the decisions on the driver's own issues, and the driver's own posts |

**Driver reading**, enforced in the same row-level security: `channel` posts are **not** visible to the driver. The store and dispatcher coordination stays between them; the dispatcher passes on to the driver only what the driver must act on, addressed to the driver. This keeps the driver's phone quiet while driving and matches today's R-MSG-01, where the driver already sees only `driver` and `all` messages and their own.

**Reading**, enforced in row-level security on `messages` and on transcripts, joined the same way as `voice_notes_read`:
- A store reads `channel` messages only while its outlet row is active, plus those created before its `read_only_at`.
- Redirected rows are hidden from stores.
- Messages stored before this change keep the visibility they had.

**Safeguards:**
- Complaints and disputes go through Issues, which stay private.
- Code checks a draft for another outlet's name or order number and asks the poster to send it privately instead.
- The dispatcher can redirect any `channel` post to that store's private line or to an issue:
  - `message:Redirect` appends a `messaging.message_redirects` row;
  - messages are never updated;
  - every redirect is audited.

**Driver safety gate (FF-04b, built 2026-10-07 as R-EXE-28, EXE-37).** The driver is not on the channel, so the gate covers the driver's Messages screen. Not yet: reading queued messages aloud once stopped.
- It applies when the smoothed GPS speed is 8 km/h or more over three fixes.
- With no fix, the screen shows "Stop to send".
- While gated, posts are queued, never dropped, and audio does not play by itself. Queued items read out once stopped.
- A vehicle fault or emergency issue is never blocked.

**Delivery:**
- Notifications send `channel` posts only to active outlets, with a new routing version. One owner assigns routing versions, since T1-02 also adds one.
- The thread joins the session's single event stream (F-07) instead of polling every 10 s.

**Reuse:**
- `TripThread`, `ThreadComposer`, `VoiceCapture`, `VoiceNote`, `shared/messaging/*`.
- `MessagePolicy.java` and `MessagingQuery.java`, rewritten for the new matrix.

**Tests:**
- Store A cannot read store B's private line.
- A completed store cannot read new `channel` posts.
- A redirected post is hidden from stores.
- A driver cannot post to `channel`.
- A denied-scope case.

**KPI:** calls to the dispatcher per trip; zero cross-store data leaks.

## FF-09 Live decisions

**Built 2026-10-07, PRs 1 and 2** (R-ISS-12 to R-ISS-14, ISS-18 to ISS-20, P-37, P-38). The dispatcher asks from the "Needs you" cards on Live: a closing window offers three times from the expected arrival; a stop not delivered offers to ask the next store on its trip to help at the dock. The store's question waits at the top of its home with Yes and No, a typed reason and a spoken one (posted to the dispatcher on the trip's thread, where it plays). Then, same day: the system asks by itself when the attention watch finds a stop at risk (R-ISS-15), and each card shows "What happened", built from the record alone. Checking the watch found two faults, both fixed: it read with no actor and so saw nothing (ML-16), and a stop past its closed window with no delay observed was not flagged (ML-17); it now also rings the dispatcher (routing version 11). Not yet: the Sinhala and Tamil review of the new store strings (FF-00), which needs a native speaker: they are the block marked "Live decisions (FF-09)" in `frontend/src/roles/store/data/strings.ts`.

**Goal.** A problem on the road is settled by the people it concerns in a minute, with a tap, not by phone calls. Decided with the product owner 2026-10-07 after three reviews (research, design, critique); the research found no delivery product that asks a store for a decision.

**Plan (written before code):**
- **The queue already exists.** The attention watch (#268) ranks live stops by severity and minutes left (`FAILED_STOP`, `WINDOW_AT_RISK`, `RUNNING_LATE`, `PROOF_OWED`) and the dispatcher acknowledges items. Decision cards hang off those items; no second queue.
- **Decision requests are Issues' aggregate** (`issues.decision_requests`), one store asked one question, with a code-computed deadline. States `SENT`, `ANSWERED`, `EXPIRED`, `WITHDRAWN`; asking again withdraws the open one. The store answers in the app; the dispatcher may record an answer given by phone. An answer after the deadline is still recorded, marked late.
- **Kinds (v1):**
  - `RECEIVE_LATE`: "Can someone receive at about HH:MM?" Yes or No, never for a mall (its window is fixed: it gets the failed-delivery path).
  - `DOCK_HELP`: the depot loads the last stop first (R-LOD-08), so goods for a stop that was not delivered sit in front of the next stop's. "Can the crew unload them at your dock to reach your goods, and reload them?" Yes or No. Never "keep them": chilled goods stay in the refrigerated vehicle, so no hold time is needed (assumption recorded).
- **Every name and number is filled by code** from the run sheet: store, units, expected arrival. A reason is optional, typed or spoken (a voice reason goes on the trip thread to the dispatcher, with its play button).
- **Effects:** `decision.requested` notifies the store; `decision.answered` and `decision.expired` notify the dispatcher; a Yes tells the driver on the trip thread and, for `RECEIVE_LATE`, acknowledges the window-at-risk item with the store's answer as the reason. No answer by the deadline: the dispatcher phones and records it, else today's rule applies (deliver late at an ordinary store, R-EXE-05; redeliver at a mall).
- **PRs:** (1) backend: aggregate, commands, expiry job, events, consumers, routing v10, policy; (2) dispatcher: ask from the attention item, answers, record by phone; store: the card with Yes or No and a reason; (3) later: code-filled templates (FF-08), a case summary from the recorded steps, then AI ranking in shadow.



**Goal.** Between 03:30 and 08:00 the dispatcher is the bottleneck. When they pick who to answer (the driver, one store or the whole channel), the screen offers the three most likely approved replies with the facts already filled in.

**How it works:**

1. **Allowed set.** Code computes it from the trip state. For example, "Redelivery booked" appears only if a redelivery exists. A template for one outlet is never offered for `channel`.
2. **Ranking.** In version 1 the allowed set is ranked by rules. Jev ranking is added only if a gate is met ([06](06-INTELLIGENCE.md#items), AI-J5).
3. **Slots.**
   - Times, units, outlets and dates are filled by code only, from the Execution, Planning and Intelligence contracts, and shown highlighted.
   - An estimated time is labelled "estimate".
   - A model never produces a number, a time or a name.
4. **Sending.** Nothing is sent automatically. The dispatcher can edit; an edited message is marked "edited".

**Data:**
- Templates are stored in `messaging.reply_template_versions`, a versioned decision table like `notification.routing_versions`.
- Each template has: id, scenario, allowed audiences, slot names, and text in en, si and ta. Scenario conditions are domain code, not SQL.

**Language.** An unedited template message is stored as template id plus slot values, so every reader sees it in their own language.

**Recording.** `message:Post` records `{templateId, version, rank, edited}`. That gives the acceptance figures and a training set for later ranking.

**Day-one templates:**
1. Running late, expected HH:MM.
2. Stop order changed: you are now stop N, expected HH:MM.
3. Held at dock, new departure HH:MM.
4. Issue accepted: replacement on DATE.
5. Issue not accepted: REASON (from a fixed list).
6. Redelivery booked for DATE.
7. Short N units of LINE: written off / to follow.
8. Order deferred to DATE: REASON (rule).
9. Mall bay closed: wait or skip?
10. Driver: proceed / wait / return to depot.

**Governance.** Template wording is governed by P-MSG-T1 ([07](07-POLICIES.md)).

**Degrade.** If suggestions fail, the composer says "Suggestions: rules only" and works as today.

**Tests.** A template's slots can never put one outlet's data into `channel`.

**KPIs:**
- median reply time under 2 minutes, 03:30-08:00;
- calls per trip down 30%;
- 70% or more of replies from a template;
- zero wording errors in a weekly sample of 50.

## FF-05 Voice transcription

**Status.** Built 2026-10-09 with Gemini, off unless `TRANSCRIBE_ENGINE=gemini` and `GEMINI_API_KEY` are set; production stays off until the data processing agreement is signed. The dispatcher's thread shows each voice note's machine transcript (R-MSG-15, MSG-26); the driver's, loader's and store's screens are unchanged. Trial on a synthetic English note: `gemini-3.8-flash` (the default) wrote it exactly in about 70 s; the lite models took 2.5 s but heard "Kandy" as "candy". `gemini-2.5-flash` is no longer offered to new keys. The spike below (200 real Sinhala and Tamil notes, character error rate) is still to run before anyone relies on the words.

**As planned:**

**Goal.** Every voice note can be read as well as heard, and every text message can be heard as well as read.

**Spike:**
- At least 200 real samples, stratified by language (Sinhala, Tamil, English, mixed) and noise, with recording consent.
- Two annotators, with their agreement measured.
- **Measure:** character error rate on normalised text, plus a task measure: are the quantity, the item and the outlet right? Word error rate is not well defined for code-mixed text in mixed scripts.
- **Candidates:**
  - Google Speech-to-Text Chirp / chirp_2: Sinhala; Tamil only as ta-IN.
  - Self-hosted faster-whisper small or medium int8 on the server CPU. Base Whisper performs very badly on Sinhala without fine-tuning (108.9% word error rate on one test set), so a cloud engine is the likely result.

**Build:**
- Port `SpeechToText`. The `message.posted` consumer inserts a pending job; the F-09a worker transcribes outside any transaction.
- The transcript stores its engine name and the label "Machine transcript, check audio". It is never the decision record.
- Transcripts follow P-33 retention and are cleared with their audio.
- Only notes with no kind chosen, trimmed to 15 s, are sent. Non-urgent ones use the cheaper next-day batch tier.
- **Degrade:** "No transcript, play audio".
- The browser text-to-speech player (now in `roles/driver/ui.tsx`) moves to `shared/ui`, so every text message can be heard.

## FF-06 Fuel log and Fuel Pass QR

### FF-06a Fuel log (dropped 2026-10-07)

Dropped. The booklet's fuel rule is a planning constraint (R-PLN-16, R-PLN-24), already built from planned distance; it never asks for actual litres pumped. The government's Fuel Pass enforces the real quota at the pump and changes it from time to time, so a second quota record here would only go stale and disagree with it.

### FF-06b Fuel Pass QR (built 2026-10-07)

- **Step 0, answered:** the government issues one QR per vehicle and enforces the quota at the pump. Waypoint carries the official image only, so a driver needs no paper copy; it holds no quota.
- **Admin:** adds, replaces or withdraws the image per vehicle from the vehicle screen (`roles/admin/access/FuelPassDialog.tsx`). The upload is a PUT with a screen-minted pass id and the vehicle's pass count as expected version (`vehicle:SetFuelPass`); withdrawing is the command `vehicle:RevokeFuelPass` with a reason. A replaced or withdrawn pass is revoked, never deleted. The image is never shown back to the administrator.
- **Stored in** `execution.fuel_passes` (PostgreSQL, like proof photos), moving with them when M-01 adds object storage.
- **A bearer credential (R-EXE-27):** only the driver assigned to the vehicle today or tomorrow reads it (`delivery:ReadFuelPass`), every read and refusal is audited, it is sent `no-store` and never cached by the service worker.
- **Driver:** read with the run while there is a signal and kept in the account's IndexedDB to the end of its last valid day, so it shows at a pump with none; a withdrawn pass or a refusal deletes the kept copy. With no pass, the popup says to ask the depot instead of drawing a code the pump would refuse (EXE-34, EXE-35). Signing out deletes it, with every other credential kept under `credential:` (`forgetCredentials`).
- **Policies:** P-FP-1 (lost or changed QR: withdraw, upload the new one) and P-FP-2 (a laminated card stays in the vehicle).

## FF-07 Profile photos

**Status.** Built 2026-10-09, ahead of the pilot, on database storage (moves to the object store with M-01). The administrator adds a photo from a member's Edit sheet with the consent note; the store sees its driver's photo beside the name on the day of the delivery; everyone else sees initials. R-IAM-39, SEC-47. Not yet shown on the dispatcher's, driver's or loader's screens, and the browser's private five-minute cache stands in for the IndexedDB copy below.

**As planned:**

- **Upload:** the admin uploads a photo per account, cropped square and encoded to WebP. Stored through `AvatarStore`.
- **Command:** `iam:SetProfilePhoto` and `iam:RemoveProfilePhoto`.
- **Who sees it:** only people who work with that person that day. Showing the driver's photo to the receiving store is a security benefit.
- **Consent:** written staff consent, and removal on request (P-PD-1).
- **UI:**
  - A shared `Avatar` component replaces the per-role `initials` helpers.
  - Served through `/api`, cached in the account's IndexedDB, cleared on sign-out, because loader devices are shared.

## Sources

- [Sri Lanka National Fuel Pass guide](https://www.dailymirror.lk/breaking-news/Sri-Lanka-Fuel-QR-Code-System-Complete-Guide-to-Register-Download-and-Fuel-Quotas/108-335342)
- [Weekly fuel limits, March 2026](https://newsfirst.lk/2026/03/15/qr-weekly-fuel-limits-motorcycles-5l-cars-15l)
- [Google Speech-to-Text supported languages](https://docs.cloud.google.com/speech-to-text/docs/speech-to-text-supported-languages)
- [Whisper on Sinhala](https://arxiv.org/pdf/2503.20212)
- [Fine-tuned Whisper small for Sinhala](https://huggingface.co/hlasith/whisper-sinhala-small)
- [Tamil dialect ASR](https://aclanthology.org/2026.dravidianlangtech-1.17/)
- [AI proof-of-delivery verification](https://verify.switchlabs.dev/use-cases/proof-of-delivery)
- [Voice AI in trucking](https://spot.numeo.ai/blog/voice-ai-in-trucking)
