# Handover to @jv-ransika before the pilot

State of `dev` on 2026-10-08 (`fabbd69`), what is verified, and what only you can unblock. Detail lives in [STATUS](../development-docs/STATUS.md), the [roadmap](README.md) and the [log](../development-docs/log/Oxshadha.md); this page is the checklist.

## 1. Blocking everything: GitHub Actions does not start

Every run on `dev` since `19cd451` (2026-10-08 11:50) failed with no job started: "recent account payments have failed or your spending limit needs to be increased". No code ran; the last run that did start (`26ca8d2`) passed. Fix it under the owner account's Billing & plans, then re-run Checks on the `dev` head. Until then the only verification is local:

| Check | Result on `dev` |
| --- | --- |
| Backend `mvn test`, Java 17 as CI, fresh test database | 1,223 run, 0 failed, 1 skipped (the Datathon replay, which needs the licensed data) |
| Frontend typecheck, build, Node tests | pass, 386 of 386 |
| Browser suites, after the last change to each role | shell 22, dispatcher 94, driver 46, loader 33, store 63 |
| `promtool test rules`, `amtool check-config` | pass |

Use Java 17 locally: under Java 25 Mockito cannot mock and 41 tests fail before they start.

## 2. Production is 118 commits behind `dev`

`main` lacks 42 migrations (`20261006T1000` to `20261008T1700`). The production deploy dumps the database and then the `init` container migrates, so no manual step is needed, but do it through the release pull request only after Checks are green on the `dev` head. Two behaviour changes arrive with it, both effective-dated from 2026-10-09:

- Priority policy version 2 (`20261008T1600`): unserved orders rank right after chilled ones.
- Rule set version 2 (`20261008T1700`): a second trip waits for the drive back and a 20-minute reload (R-PLN-47). **The 20 minutes is a placeholder**: ask the depot for the real reload time and set it on System constraints.

## 3. Only you can do these

| Item | What | Where it is written |
| --- | --- | --- |
| H-02 | Rotate every secret the history exposed, the administrator password first | [deployment.md, Rotating passwords and secrets](../deployment.md#rotating-passwords-and-secrets) |
| H-03 | One GitHub Environment per deployment (preview, production) with its secrets | [02 Phase 0](02-FOUNDATION-FIXES.md) |
| H-05 | Off-server backup and a timed restore drill (RPO 24 h, RTO 4 h) | [02 Phase 0](02-FOUNDATION-FIXES.md) |
| Alerts | Make a Slack-compatible incoming webhook for the team's channel, put it in `.env` as `ALERT_WEBHOOK_URL` on the server, then `docker compose --profile observability up -d alertmanager`. Until then alerts show in Prometheus and on Grafana's Alerting page only | [deployment.md, Logs](../deployment.md#logs) |
| B-04 production figures | Command p95, plan generation time and rows per day from the server's Prometheus; the dev-stack figures are recorded | [01 B-04](01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline) |
| M-01 | The object store needs a cloud account (R2) | [02 Phase 2b](02-FOUNDATION-FIXES.md) |

## 4. Waiting for your agreement (driver UI)

The role rule keeps driver changes to fixes unless you agree. Two roadmap items need it:

- **FF-00, reply templates in the reader's language.** Ready replies are stored as English text, so a driver working in Sinhala or Tamil still reads dispatch's ready replies in English. Showing them in the driver's language means the phone renders a reply from its template id and values.

Language scope, decided by @Oxshadha (2026-10-08, revised 2026-10-09): Sinhala and Tamil for the driver, the loader and the store manager, each complete; the dispatcher stays in English. The store and loader were completed on 2026-10-09 (FF-00). **The driver's screens are now built in Sinhala and Tamil** (every screen, following the language chosen in Settings; [FF-00](04-FIELD-FEATURES.md#ff-00-language)). It changes no layout or flow, only the words, and the English screens are unchanged (driver suite 48 of 48).

One driver change was made without asking, as a fix: a driver with nothing released today made 85 requests a minute against the gate of 60, so the week ahead is now checked every 2.5 minutes instead of every 30 s. Nothing on screen changed (`useRun.ts`, test `request-rate.spec.ts`).

## 5. Before the pilot starts

- **B-03, the fresh-install walk, is not done.** It stopped because the development machine's disk is full (Docker could not unpack a layer). The walk found one real problem on the way, fixed in `1600978`: the model service image pulled NVIDIA's GPU library (352 MB) through `xgboost`; Linux now installs `xgboost-cpu` of the same version. Building that image has not been verified yet. Run on a machine with 10 GB free: a clean clone, `git lfs pull`, `cp .env.example .env`, `docker compose -p judge up --build`, then the [README walkthrough](../../README.md#judge-walkthrough) as the four named accounts.
- **Native-speaker review** of the Sinhala and Tamil drafts in `frontend/src/roles/{driver,loader,store}/data/strings.ts` (FF-00).
- **B-05**: the depot managers start the 4-week KPI baseline (on-time Fresh, deferrals, disputes, write-offs, calls per trip, stops recorded in the app).
- **D-00**: the team's Datathon package (notebook, video, AI disclosure). The Task 2B write-up and its evidence are done: [TASK-2B-POLICY.md](../datathon/TASK-2B-POLICY.md).

## 6. Built and waiting for pilot data, by design

No model is on by default until the trust gates pass on four weeks of labelled live stops ([05](05-DATATHON-MODELS.md)). The reply ranker (AI-J5) trains on the reply logs being collected now. Voice transcription (FF-05) is a spike after the pilot and needs the data processing agreement. Profile photos (FF-07) come after the pilot. The pool split (F-09b) waits for evidence; the pool shows nothing waiting.

## 7. Decided against, with a trigger to reopen

One live stream per session (F-07): production is HTTP/2, a tab holds at most three streams, each already beats within 25 s. Reopen on the conditions in [03](03-INFRA-DECISIONS.md#one-live-stream-per-session-not-now-decided-2026-10-08).
