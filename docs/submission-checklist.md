# Submission readiness

## Designathon, September 29, 2026 at 23:59 Sri Lanka time

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

The local implementation, seed dataset, tests and supporting documentation are prepared in this repository. No public deployment, external design-file modification, video upload or competition-form submission was performed. Vercel + Neon configuration is documented in deployment.md. Docker execution depends on access to the host daemon; see verification.md for the observed status. The repository is named `Xception-WaypointGo`; check the booklet's exact naming convention before submission. Final design-file fidelity, hosted availability, videos and submitted links are not verified by this documentation update. See [the latest verification results](verification.md) for current local blockers.
