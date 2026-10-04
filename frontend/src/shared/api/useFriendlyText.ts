"use client";

import { useEffect, useState } from "react";

// The explanation said more naturally by a language model, when the server has
// one (app/explain/route.ts). Asked once for a plan version or an order and
// kept on this device after that, so opening it again costs nothing. Null
// while it is on the way and whenever there is none: the screen then shows the
// rule-based explanation alone.

const PREFIX = "wp:explain:";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(PREFIX + key);
  } catch {
    return null;
  }
}

function write(key: string, text: string): void {
  try {
    window.localStorage.setItem(PREFIX + key, text);
  } catch {
    // Not kept on this device; the server still holds it.
  }
}

export function useFriendlyText(key: string | null, facts: unknown): string | null {
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    if (!key) {
      setText(null);
      return;
    }
    const had = read(key);
    setText(had);
    if (had) return;
    const stop = new AbortController();
    fetch("/explain", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ key, facts }), signal: stop.signal })
      .then((answer) => (answer.ok ? (answer.json() as Promise<{ text: string | null }>) : { text: null }))
      .then((answer) => {
        if (!answer.text) return;
        write(key, answer.text);
        setText(answer.text);
      })
      .catch(() => undefined);
    return () => stop.abort();
    // The facts belong to the key: a new plan version is a new key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return text;
}
