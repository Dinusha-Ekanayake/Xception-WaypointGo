# Live check: one delivery day across the four roles

`live_check.py` drives a real delivery day through the command endpoint of a
running local stack and measures how quickly each step reaches the other roles:
the dispatcher (Live screen reads, the change stream, the live map stream), the
driver of one trip, the store managers of that trip's first two stops, and the
loader. It is a check of the running system, not a unit test: it changes the
local database and moves the demo business clock forward.

## What it checks

In order, each step a real command, each followed by the reads and streams of
the other roles, timed from the moment the command was sent:

1. `plan:Generate` and its job, then `plan:Publish` (dispatcher).
2. `loading:Start`, a `loading:Check` of every item and `loading:Release` of one
   trip with two or more stops (loader, unlocked with the PIN operator flow).
3. `delivery:RecordPositions`, `delivery:Start` at the first stop (driver).
4. The first stop's late estimate (R-EXE-29): the business clock is moved 40
   minutes past the first stop's planned time before any arrival, and the run
   waits up to 130 s for the eta refresh job to give that store an
   `expectedArrival`, an `eta` change and an `eta.changed` notification.
5. `delivery:RecordArrival` with `deviceArrivedAt` from the business clock, as
   the driver UI sends it, then `delivery:Record` DELIVERED, then positions on
   the way to the second stop (the delivered store must stop receiving them).
6. `receipt:Confirm` (store manager); the driver must see the answer.
7. `message:Post` from the dispatcher to the driver, `notification:MarkRead` by
   the driver and `seenByDriver` for the dispatcher; a store manager's post on
   the trip channel.

The store manager is granted the first two stops' outlets through
`iam:GrantScope` when it does not already hold them.

## What it needs

- The local stack from `scripts/dev.sh` with `DEMO_MODE=1` (the backend on
  `:8080`; the frontend is not used).
- The demo accounts (`scripts/dev.sh setup` or the `demo-accounts` command) with
  the password in `SEED_PASSWORD` (default `Waypoint2026!`).
- A delivery day: `seed-delivery-day` once, which also sets the loader PIN
  (`DEMO_LOADER_PIN`, default `2468`). Later runs need a fresh day; the script
  asks the admin for one with `demo:ResetDay` (Peliyagoda only).
- Python 3.9 or later with `requests`.

## How to run

```sh
python3 scripts/live-check/live_check.py
python3 scripts/live-check/live_check.py --date 2026-10-12 --no-reset
```

Options: `--base` (default `http://127.0.0.1:8080`), `--depot` (default
`Peliyagoda`), `--date` (default: the first day within seven days that has
orders, no published plan and vehicles for the demo driver, else a fresh day
from `demo:ResetDay`), `--password`, `--loader-pin`, `--no-reset` to stop
and say what to run instead of preparing a day, and `--allow-remote`: without
it the script refuses any backend not on this machine, since it resets a day,
grants scopes and moves the demo clock. A given `--date` that already
has a published plan is replaced by a fresh day unless `--no-reset` is set.

A run takes about four minutes, most of it waiting for the eta refresh job. It
leaves the demo clock where it moved it, on the service day.

## How to read the output

Each command prints its status and time. Each check prints one line:

```
  PASS  store_manager OUT060 delivery shows arrived (poll)  0.08 s
```

- `PASS`: seen within 3 s (within 30 s for plan generation, within 130 s for the
  eta refresh job checks, which run every two minutes).
- `SLOW`: seen, but later than that.
- `FAIL`: not seen within 30 s (120 s for generation, 130 s for the refresh job),
  with the last read on the next line.
- `poll` means a read of the endpoint the screen uses, repeated every 250 ms;
  `stream` means the server-sent event arrived on the open stream.

The run ends with a summary table and the counts. The exit code is 0 only when
no check failed. A command the rest depends on that is refused stops the run,
with the request, the status and the response body printed.
