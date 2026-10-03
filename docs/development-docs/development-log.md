# Development log

Why things changed and what state they left behind. Git history says what changed; this says why, and what is still open. Several people and agents work here in parallel without seeing each other's sessions, so read the recent entries before starting.

## One file per person

The log is split by GitHub user, one file each in [`log/`](log/), so that two pull requests never add to the same file and never conflict:

| GitHub user | File |
| --- | --- |
| @Dinusha-Ekanayake | [log/Dinusha-Ekanayake.md](log/Dinusha-Ekanayake.md) |
| @jv-ransika | [log/jv-ransika.md](log/jv-ransika.md) |
| @kavindamihiran | [log/kavindamihiran.md](log/kavindamihiran.md) |
| @Oxshadha | [log/Oxshadha.md](log/Oxshadha.md) |
| @ranathungaWK | [log/ranathungaWK.md](log/ranathungaWK.md) |
| @tharushaudana | [log/tharushaudana.md](log/tharushaudana.md) |

- **Write only in the file of the GitHub user who owns the work**, `log/<github-user>.md`, with the login spelled exactly as on GitHub (`gh api user -q .login` prints it; `git config user.name` is not always the login). Never add to someone else's file.
- Someone new creates their own file with the same heading as the others; adding their row above is welcome but not required, since `log/` is the list.
- To read what others did recently, read the top entry of every file, or `git log -p -5 -- docs/development-docs/log/`.

## How to write an entry

Short imperative subject, then a few terse lines. Newest first, at the top of your file under its heading. Credit the GitHub user who owns the work; never record agent, tool or model names. Skip typo and formatting fixes.

```markdown
## YYYY-MM-DD - type: short imperative subject

`<branch>` · @<github-user>

What changed, one or two lines.
Why: one line.
Verified: commands and result, or "not verified" and why.
Open: what is left, or "nothing".
```

Entries are separated by a line holding `---`. Entries before 2026-09-26 are in `git log`.
