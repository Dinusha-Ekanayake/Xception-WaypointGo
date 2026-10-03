# AI tool disclosure

AI coding assistants assisted with implementation and review of the TypeScript/React frontend, Java/Spring backend, PostgreSQL persistence, supplied seed-data packaging, tests, deployment configuration and documentation. AI assistance included proposing implementation details, drafting code and explanations, inspecting source files, running local commands and interpreting test results. Earlier repository notes also record AI code-review assistance and browser automation.

| Tool | Evidence in the repository or from the team |
| --- | --- |
| Codex | Named by the team in the first version of this disclosure |
| Claude (Claude Code) | `Co-Authored-By: Claude` trailers on commits in `dev`; used by team members for module, interface and documentation work |
| Cursor | Named by a team member as the editor used for part of the planning work (issue #9) |

Commit trailers are not a complete record: the repository's own rules ask contributors not to add AI attribution trailers, so most assisted commits carry none. Every team member should add any other tool they used before submission.

Human input supplied the competition materials, existing project, Designathon/Hackathon scope, requested changes and authorization to implement and publish work. This document does not claim that every design decision originated with a human or that every AI suggestion received individual human review. The team must verify and explain the final solution and add any other tools used across the complete project history before submission.

The application uses the competition's synthetic CSVs, a documented subset of historical order rows and explicitly labelled demonstration scenarios. No real operational outcomes, user interviews, GPS measurements, predictive accuracy or business savings are claimed. Sample proof/signature images existed before this documentation audit; their original authorship has not been independently established here.

Verification uses shell commands, Maven, TypeScript, Node tests and Playwright. A successful build is distinct from full integration testing or a verified public deployment. The [verification record](verification.md) separates historical results, current observations and outstanding checks. The September 26 documentation review corrected stale runtime descriptions, setup paths and unsupported review claims without changing application behavior.

The application serves the team's trained Datathon models read-only from `ml-server/` (issue #16); it does not train models, and the training itself and the Datathon submission are outside this build. The final design export, video, disclosure approval and official submission remain team responsibilities.
