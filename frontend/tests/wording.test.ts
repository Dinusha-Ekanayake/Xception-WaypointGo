import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { test } from "node:test";

// The wording guardrail (issues #127 and #134). Visible text in the role screens,
// the shared UI and the shell follows docs/architecture/GLOSSARY.md: no retired
// word, no raw code from the data, no 12-hour clock, no em or en dash.
//
// What is still in the screens today is counted in tests/wording-baseline.json,
// per file and rule. A count above its baseline is new drift and fails. A count
// below it fails too, until the baseline is lowered to match, so a fix is
// locked in and cannot quietly come back. #128 works the baseline down to empty.
//
//   UPDATE_WORDING_BASELINE=1 node --test --experimental-strip-types tests/wording.test.ts
//
// rewrites the baseline from the current tree. Lower it freely; raising it needs
// a reason in the pull request.
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const BASELINE = join(ROOT, "tests", "wording-baseline.json");
const SCANNED = ["src/roles", "src/shared/ui", "src/app-shell"];

export const RULES: Record<string, RegExp> = {
  "retired: cases or packages (say units)": /\b(cases?|packages?|pieces?)\b/i,
  "retired: reefer (say refrigerated)": /\breefers?\b/i,
  "retired: ETA (say expected arrival)": /\bETA\b/,
  "retired: drop or shop": /(?<![-\w])(drops?|shops?)(?![-\w])/i,
  "retired: route for a trip (say trip)": /(?<![-\w])routes?(?![-\w])/i,
  "raw code on screen": /\b(rear_dock|mall_bay|van_only|mall_dock)\b/,
  "12-hour clock": /\b\d{1,2}(:\d{2})?\s?(AM|PM)\b/,
};

/** Formatting that bypasses shared/wording: a 12-hour or device-zone clock. */
const CODE_RULES: Record<string, RegExp> = {
  "time formatted outside shared/wording": /hour12:\s*true|toLocaleTimeString\(|"en-US",\s*\{\s*hour/,
  // AGENTS.md: no em or en dash anywhere, comments and multi-line strings included.
  "em or en dash": /[\u2013\u2014]/,
};

/** The strings and JSX text on a line that a person could read; class names and imports are not. */
export function visibleText(line: string): string[] {
  const trimmed = line.trim();
  if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("import ")) return [];
  const cleaned = line.replace(/className=("[^"]*"|\{`[^`]*`\})/g, "");
  // JSX text alone on its line, such as `How many units?` between tags on the lines around it.
  if (/^[A-Za-z][^{}()<>=;"'`]*$/.test(trimmed) && /\s/.test(trimmed) === /\s/.test(trimmed)) {
    if (!/^(return|const|let|export|type|case|default|else|if|for|while)\b/.test(trimmed) && !/[:,]$/.test(trimmed)) return [trimmed];
  }
  const found: string[] = [];
  for (const m of cleaned.matchAll(/"([^"\n]{2,})"|'([^'\n]{2,})'|`([^`\n]{2,})`|>([^<>{}\n]{2,})</g)) {
    // An interpolation is code, not text: `${route}` names a variable.
    const raw = m[1] ?? m[2] ?? m[3] ?? m[4] ?? "";
    const text = raw.replace(/\$\{[^}]*\}/g, " ");
    // A bare identifier, code or path, such as a key, a compared code or a test id, is not text a person
    // reads; text built around an interpolation always is.
    const between = m[4] !== undefined;
    if (!between && !raw.includes("${") && /^[\w./@:#-]+$/.test(text.trim())) continue;
    if (!text.trim()) continue;
    // Two strings paired across code, such as `"a"].includes(x)) go("`, are code.
    if (/\]\.|\)\)|\.\w+\(|=>|;\s/.test(text)) continue;
    // A class list is styling, not text.
    if (/(^|\s)(flex|grid|text-|bg-|px-|py-|rounded|items-|gap-)/.test(text)) continue;
    found.push(text);
  }
  return found;
}

export function countRules(source: string): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const line of source.split("\n")) {
    for (const text of visibleText(line)) {
      for (const [rule, pattern] of Object.entries(RULES)) {
        if (pattern.test(text)) counts[rule] = (counts[rule] ?? 0) + 1;
      }
    }
    for (const [rule, pattern] of Object.entries(CODE_RULES)) {
      if (pattern.test(line)) counts[rule] = (counts[rule] ?? 0) + 1;
    }
  }
  return counts;
}

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

function scanTree(): Record<string, Record<string, number>> {
  const result: Record<string, Record<string, number>> = {};
  for (const dir of SCANNED) {
    for (const path of files(join(ROOT, dir))) {
      const counts = countRules(readFileSync(path, "utf8"));
      if (Object.keys(counts).length > 0) result[relative(ROOT, path).replaceAll("\\", "/")] = counts;
    }
  }
  return Object.fromEntries(Object.entries(result).sort(([a], [b]) => a.localeCompare(b)));
}

test("the guardrail recognises each rule it enforces", () => {
  assert.deepEqual(countRules(`<p>3 cases of chilled</p>`), { "retired: cases or packages (say units)": 1 });
  assert.deepEqual(countRules(`const t = "Dock: rear_dock";`), { "raw code on screen": 1 });
  assert.deepEqual(countRules(`if (dock === "rear_dock") return;`), {}, "a code compared in code is not on screen");
  assert.deepEqual(countRules(`window.location.hash = \`#\${route}\`;`), {}, "an interpolated variable is not text");
  assert.deepEqual(countRules(`const pill = "flex rounded-full drop-shadow-[0_5px]";`), {}, "a class list is styling");
  assert.deepEqual(countRules("detail: `ETA ${clock(at)}`,"), { "retired: ETA (say expected arrival)": 1 }, "text around an interpolation");
  assert.deepEqual(countRules("  ? `Having trouble.\\nDon't worry\u2014${n} ${"), { "em or en dash": 1 }, "a dash in a string that spans lines");
  assert.deepEqual(countRules(`            How many cases?`), { "retired: cases or packages (say units)": 1 }, "JSX text on its own line");
  assert.deepEqual(countRules(`label: "Today 4:00 PM",`), { "12-hour clock": 1 });
  assert.deepEqual(countRules(`x.toLocaleTimeString("en-US")`), { "time formatted outside shared/wording": 1 });
  assert.deepEqual(countRules(`<span className="items-center">12 units</span>`), {}, "class names and the right word pass");
  assert.deepEqual(countRules(`// a comment about cases`), {}, "comments are not screen text");
});

test("no new wording drift: visible text holds to the glossary or its baseline", () => {
  const current = scanTree();
  if (process.env.UPDATE_WORDING_BASELINE === "1") {
    writeFileSync(BASELINE, `${JSON.stringify(current, null, 2)}\n`);
    return;
  }
  const baseline = JSON.parse(readFileSync(BASELINE, "utf8")) as Record<string, Record<string, number>>;
  const problems: string[] = [];
  for (const file of new Set([...Object.keys(current), ...Object.keys(baseline)])) {
    for (const rule of new Set([...Object.keys(current[file] ?? {}), ...Object.keys(baseline[file] ?? {})])) {
      const now = current[file]?.[rule] ?? 0;
      const was = baseline[file]?.[rule] ?? 0;
      if (now > was) problems.push(`${file}: ${rule}: ${now} (baseline ${was}). Use the glossary's word, or raise the baseline with a reason`);
      if (now < was) problems.push(`${file}: ${rule}: ${now}, below the baseline's ${was}. Lower the baseline to lock the fix in`);
    }
  }
  assert.deepEqual(problems, []);
});
