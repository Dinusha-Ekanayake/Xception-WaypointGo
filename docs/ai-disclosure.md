# AI tool disclosure

All product decisions, scope boundaries and acceptance were human. The team defined the problem framing, the four-role scope, the explainable-allocation approach, the planning and recovery constraints, the visual system, the demo strategy and what was explicitly out of scope. AI acted only as an implementation assistant working under that human direction.

Codex assisted with drafting and revising TypeScript/React changes, packaging the supplied seed data, drafting tests and documentation, and investigating failures through local execution and browser automation. An independent AI code-review agent provided additional correctness suggestions. Playwright and shell tools were used for verification. No design decision was accepted without human review.

The data originates from the competition's supplied synthetic CSVs. The app copies reference records and a documented subset of historical order rows, and adds explicitly labelled demonstration scenarios. No real operational outcomes, user research, GPS data, predictive accuracy or business savings are claimed. The proof and signature sample images were already present before these changes; their original authorship has not been verified in this session.

Human input supplied the competition materials, existing implementation, scope limited to Designathon and Hackathon, approval to implement the reviewed improvements, and competition goals. The team reviewed every AI suggestion, verified behavior through manual role flows, owns the final code and design, and can explain it to judges. This disclosure does not assert that earlier work was human-authored: the team should add any other tools used before this session and verify the final disclosure against its complete workflow.

Every AI suggestion was checked by a human against source code, operating constraints, disposable PostgreSQL tests, a production build and browser tests. Verification findings and unverified deployment steps are recorded in verification.md. No model training or Datathon submission is part of this build.
