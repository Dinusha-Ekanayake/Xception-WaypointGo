# Submission readiness

## Designathon, September 29, 2026 at 23:59 Sri Lanka time

This deadline has passed. The list is kept as the record of what the design submission had to contain.

- Use design-rationale.md as source material for distinct pages in the team's design file: framing, four personas, connected flows, per-screen rationale, named degradation journey, prototype, tradeoff and AI disclosure.
- Include fully developed phone screens for driver and loader, especially offline save/reload/recovery.
- Compare the working app to the final design. Preserve the Day 5 export for continuity evidence.
- Export with the team's required base name, compress the design file, supply a shareable prototype link and an unlisted 3-5 minute YouTube video.

## Hackathon, October 4, 2026 at 23:59 Sri Lanka time

- Name the GitHub monorepo according to the booklet's TeamName_SolutionName convention. Preserve genuine development history.
- Push the finished source on `main`, tracked seed data, Docker Compose, .env.example and docs before the deadline. `dev` replaces the old `master` branch; confirm the submitted commit is on the branch judges will inspect.
- Start from a fresh clone and database; run the numbered README walkthrough and the documented tests.
- Deploy one instance behind HTTPS with a separate PostgreSQL database, a private seeded password and secure cookies. Keep the URL live during judging.
- Record a 5-8 minute unlisted demo showing all four roles, failure recovery and brief architecture explanation.
- Provide repository, deployed URL, four judge accounts and video through the official form. Test every shareable link in an unauthenticated browser.

## Completed here versus remaining

State on 2026-10-02. What is built is in [STATUS.md](development-docs/STATUS.md); what was last run and what it showed is in [verification.md](verification.md).

Done in this repository:

- Four role applications (store manager, dispatcher, loader, driver) over ten backend modules, with the tests recorded in verification.md.
- A deployment on a VPS behind HTTPS: production from `main` at `https://waypointgo.live`, a preview from `dev` at `https://preview.waypointgo.live`, each with its own database. See [deployment.md](deployment.md).
- `docker compose up --build` as the judge path, with reference data and an administrator created by the `init` step.

Still to do before the Hackathon deadline, in this order:

1. **Release `dev` to `main`.** Production runs `main`, which is 114 commits behind `dev`: it has none of Planning, Loading, Execution, Receipt, Issues or the rebuilt role screens.
2. **Walk a fresh clone.** `docker compose down -v`, `docker compose up --build`, then one order through all four roles. This path has not been re-walked since the modules landed, and there is no seed that takes a fresh install to a released trip, so the walk starts from an empty day.
3. **Judge accounts.** One per role with a private `SEED_PASSWORD`, each holding a depot or outlet scope, checked by signing in on the production address.
4. **Confirm the repository name** against the booklet's TeamName_SolutionName convention. It is `Xception-WaypointGo`.
5. **The demo video, the AI disclosure review and the official form.** These are team actions and nothing in this repository performs them. [ai-disclosure.md](ai-disclosure.md) should be re-read against the tools actually used before it is submitted.
