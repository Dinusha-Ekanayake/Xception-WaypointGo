# AI tool disclosure

The team designed and built Waypoint Dispatch. The problem framing, the architecture, the module boundaries, the data model, the planning approach, the role flows and the decisions recorded in the development log are the team's own work, and the team can explain and defend every part of the final solution.

AI assistants were used as supporting tools, for a smaller share of the work:

| Tool | What it was used for |
| --- | --- |
| Claude (Claude Code) | Drafting and reviewing parts of the code, tests and documentation, running local commands and reading test output. Some commits on `dev` carry a `Co-Authored-By: Claude` trailer |
| ChatGPT | Questions, explanations and small drafts |
| Codex | Small implementation and review tasks |

Every AI suggestion was a proposal. Team members chose what to keep, adapted it to the project's rules and verified it with the project's tests. Commit trailers are not a complete record of assistance, because the repository's own rules ask contributors not to add AI attribution trailers.

The application uses the competition's synthetic CSVs, a documented subset of historical order rows and explicitly labelled demonstration scenarios. No real operational outcomes, user interviews, GPS measurements, predictive accuracy or business savings are claimed. The original authorship of the sample proof and signature images has not been independently established.

Verification uses shell commands, Maven, TypeScript, Node tests and Playwright, and the [verification record](verification.md) separates historical results, current observations and outstanding checks. The application serves the team's trained Datathon models read-only from `ml-server/` (issue #16); it does not train models.
