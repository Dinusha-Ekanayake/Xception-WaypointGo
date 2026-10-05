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
- **Done:** a native-speaker sign-off is recorded for each language.

## FF-01 Shared report sheet

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

**Goal.** During a trip, the dispatcher and the stores still waiting for their goods coordinate in one place, by text or voice. The driver hears and reads it without having to chat.

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
| Driver | No | An issue to the dispatcher only, typed or voice. Decisions come back to the driver |

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

**Driver safety gate.** A new R-EXE rule takes the next free number; R-EXE-24 and R-EXE-25 are taken.
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

## FF-08 Dispatcher reply suggestions

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

**Status.** Spike in Phase 3. Build only after the pilot shows enough voice volume and the data processing agreement is signed.

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

### FF-06a Fuel log (Phase 1.5)

- **Command:** Execution owns `execution:LogFuel`, with two fields: litres and odometer.
- **When:** prompted right after the Fuel Pass is shown. It never blocks the run; a missing entry is flagged.
- **Report:** a weekly reconciliation per vehicle compares litres with distance, against `planning.fuel_usage`. It does not claim to detect fuel pumped into another vehicle.
- **KPI:** litres per 100 km; weekly quota overruns.

### FF-06b Fuel Pass QR (after step 0)

- **Step 0:** confirm with Finance that the National Fuel Pass is enforced for fleet vehicles. Also resolve the quota conflict: the data says 340-620 L a week per vehicle, while the national cap is 200 L per lorry and 50 L per van.
- **Admin:**
  - Uploads the official QR image per vehicle in the vehicle directory (`roles/admin/access/operations.tsx`).
  - Commands `reference:SetFuelPass` and `reference:RevokeFuelPass`.
  - The image is stored lossless.
- **The QR is a bearer credential:**
  - The new `ref` table revokes the default module read grants in the same migration file, with a test.
  - The image is streamed through the backend only, never cached by the service worker, never exposed to MCP or the LLM, and every download is audited.
- **Driver:**
  - Gets it for their vehicle today or tomorrow, fetched with the run.
  - It is kept in the account's IndexedDB with a hard expiry at the end of the service date, so it clears even on a phone that stays offline. It is also cleared on sign-out.
  - There is no share button.
  - It is shown with the weekly quota used and remaining (`GET /api/plans/fuel`).
- **Policies:** P-FP-1 (lost or changed QR) and P-FP-2 (a laminated card stays in the vehicle).

## FF-07 Profile photos

**Status.** After the pilot.

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
