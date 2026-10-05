# Role UI scope: what to change and what to leave alone

Set by @jv-ransika. Read before changing anything under `frontend/src/roles/`.

## Driver and loader: nearly finished, leave them alone

The driver UI (`src/roles/driver/`) and the loader UI (`src/roles/loader/`) are close to done and match the Figma design. Do not restyle, restructure or reword them.

Change them only when one of these is true, and then keep the change as small as the fix:
- a flow is broken (a step cannot be completed, a command fails, data is wrong);
- a shared rule is broken on screen: a raw code or id instead of words, a glossary term used wrongly, an em or en dash;
- a shared helper they import (`src/shared/`) changes, and they need the matching one-line update.

Say in the commit and the development log why the change was needed. A change to their look or layout needs @jv-ransika's agreement first.

## Dispatcher and store manager: where the work is

The dispatcher UI (`src/roles/dispatcher/`) and the store manager UI (`src/roles/store/`) are the ones to make functional, clean and professional, aligned with the Challenge Booklet, the Figma design and the system as built.

## Across all roles

- Keep the main domain, the role subdomains (`dispatcher.`, `loader.`, `driver.`, `store.`, `admin.`, `auditor.`, and their `-preview` forms) and the main URL paths. Internal paths and component structure may change.
- One theme and one vocabulary. Use the design tokens in `src/shared/ui/` and the words in `src/shared/wording/` and `docs/architecture/GLOSSARY.md`: "1 stop" not "1 stops", 24-hour times, day names instead of ISO dates, no raw codes or ids.
- Where the documents disagree, decide from the side of the person using that role, and choose what is easiest for them.
- Suggestions are advice. The store's "a better day for this order" (R-ORD-15) and the dispatcher's suggested stop order (R-PLN-43) never act on their own: the person applies them, and the result is checked like any change they make by hand.

## Working practice

- Commit and push in small steps as work lands, not in one batch at the end.
- Show visible UI changes with screenshots.
- Before finishing, check the role you touched end to end in a browser, including its edge cases, and run that role's browser suite (`playwright.<role>.config.ts`).
