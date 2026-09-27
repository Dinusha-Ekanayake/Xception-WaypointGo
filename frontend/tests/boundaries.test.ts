import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

// Guards the folder layout documented in docs/code-structure.md. Rules that are
// only written down decay; this one fails the build instead.

const root = path.join(import.meta.dirname, "..");

function sources(dir: string): string[] {
  const abs = path.join(root, dir);
  let entries: string[];
  try {
    entries = readdirSync(abs);
  } catch {
    return [];
  }
  const out: string[] = [];
  for (const entry of entries) {
    const rel = path.join(dir, entry);
    if (statSync(path.join(root, rel)).isDirectory()) out.push(...sources(rel));
    else if (/\.tsx?$/.test(entry)) out.push(rel);
  }
  return out;
}

function imports(rel: string): string[] {
  const text = readFileSync(path.join(root, rel), "utf8");
  return [...text.matchAll(/(?:from|import)\s*\(?\s*["']([^"']+)["']/g)].map((m) => m[1]!);
}

const posix = (rel: string) => rel.split(path.sep).join("/");

// A rule that matches nothing passes vacuously, which is worse than no rule.
// ArchUnit fails on the backend for exactly this reason; mirror it here.
function requireFiles(files: string[], what: string): string[] {
  assert.ok(files.length > 0, `no ${what} found to check; the boundary test would pass vacuously`);
  return files;
}

test("the source tree exists and is being checked", () => {
  requireFiles(sources("src"), "files under src/");
  requireFiles(sources("app"), "files under app/");
});

test("a role never imports another role", () => {
  const roles = ["dispatcher", "loader", "driver", "store"];
  for (const file of requireFiles(sources("src/roles"), "role files")) {
    const owner = posix(file).split("/")[2];
    for (const spec of imports(file)) {
      const target = /^@roles\/([^/]+)/.exec(spec)?.[1];
      if (target && roles.includes(target)) {
        assert.equal(
          target,
          owner,
          `${posix(file)} imports @roles/${target}; cross-role composition belongs in src/app-shell`,
        );
      }
    }
  }
});

test("shared depends on neither roles nor the app shell", () => {
  for (const file of requireFiles(sources("src/shared"), "shared files")) {
    for (const spec of imports(file)) {
      assert.ok(
        !spec.startsWith("@roles/") && !spec.startsWith("@app-shell/"),
        `${posix(file)} imports ${spec}; shared must not depend on its consumers`,
      );
    }
  }
});

test("the bundled app does not reach into the legacy Node service", () => {
  for (const file of [...sources("src"), ...sources("app")]) {
    for (const spec of imports(file)) {
      assert.ok(
        !/(^|\/)lib\//.test(spec),
        `${posix(file)} imports ${spec}; lib/ holds the legacy Node service only`,
      );
    }
  }
});

test("routing files delegate to the app shell", () => {
  for (const file of sources("app")) {
    for (const spec of imports(file)) {
      // only our own aliases, not scoped npm packages such as @fontsource/*
      if (/^@(app-shell|roles|shared)\//.test(spec)) {
        assert.ok(
          spec.startsWith("@app-shell/"),
          `${posix(file)} imports ${spec}; app/ routes render the shell and nothing deeper`,
        );
      }
    }
  }
});

test("node-executed code avoids tsconfig path aliases", () => {
  // lib/, tests/ and scripts/ run under `node --experimental-strip-types`,
  // which does not read tsconfig paths, so an alias there fails at runtime.
  for (const file of [...sources("lib"), ...sources("tests"), ...sources("scripts")]) {
    for (const spec of imports(file)) {
      assert.ok(
        !/^@(app-shell|roles|shared)\//.test(spec),
        `${posix(file)} imports ${spec}; node cannot resolve tsconfig aliases, use a relative .ts path`,
      );
    }
  }
});
