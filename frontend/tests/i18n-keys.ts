import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

// The texts a role's screens pass through its translation (roadmap FF-00), read
// from the source: the string literals in the first argument of every t( or tr(
// call, both branches of a ternary inside it, and t0( for a default that is
// translated where it is drawn. A literal compared in a condition, and an empty
// fallback, are not text.

export function keysIn(source: string): string[] {
  const keys: string[] = [];
  const call = /(?<![\w.])(?:t|tr|t0)\(/g;
  for (let m = call.exec(source); m; m = call.exec(source)) {
    let depth = 1;
    let i = m.index + m[0].length;
    let quoted = false;
    let raw = "";
    let compared = false;
    while (i < source.length && depth > 0) {
      const c = source[i]!;
      if (quoted) {
        if (c === "\\") {
          raw += c + source[i + 1];
          i += 2;
          continue;
        }
        if (c === '"') {
          const literal = JSON.parse(`"${raw}"`) as string;
          if (!compared && literal !== "") keys.push(literal);
          quoted = false;
        } else raw += c;
      } else if (c === '"') {
        quoted = true;
        raw = "";
        compared = /[!=]==?\s*$/.test(source.slice(Math.max(0, i - 6), i));
      } else if (c === "(" || c === "{" || c === "[") depth++;
      else if (c === ")" || c === "}" || c === "]") depth--;
      else if (c === "," && depth === 1) break;
      i++;
    }
  }
  return keys;
}

export function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? sourceFiles(join(dir, e.name)) : /\.tsx?$/.test(e.name) ? [join(dir, e.name)] : [],
  );
}

/** Every key a role's screens translate, with the file it is in. */
export function keysOfRole(root: string): Array<{ key: string; file: string }> {
  return sourceFiles(root)
    .filter((file) => !file.endsWith("strings.ts"))
    .flatMap((file) => keysIn(readFileSync(file, "utf8")).map((key) => ({ key, file: file.slice(root.length + 1) })));
}
