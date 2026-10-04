import { NextRequest, NextResponse } from "next/server";

// A friendlier wording of an explanation the app has already worked out. The
// facts come from the planning module and are arranged by rules in the browser;
// this route only asks a language model to say them more naturally. It runs on
// the server, so the key never reaches a browser. The model gets the facts of
// one plan or one order and nothing else: no database, no tools, no free text
// from users. Whatever fails here (no key, the model is down, an answer that
// adds a number the facts do not hold) answers "no text", and the screen keeps
// the rule-based explanation it is already showing.

export const dynamic = "force-dynamic";

const KEY = process.env.GROQ_API_KEY ?? "";
const MODEL = process.env.GROQ_MODEL || "llama-3.3-70b-versatile";
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const TIMEOUT_MS = 12_000;
const MAX_FACTS = 6_000;
const MAX_KEPT = 500;

// One answer per plan version or order: asked once, then served from here.
const kept = new Map<string, string>();

const SYSTEM = [
  "You explain a delivery plan to a dispatcher in plain, friendly English.",
  "Use only the facts given. Never add a number, a name, a vehicle, a store, a time or a reason that is not in them.",
  "No rule codes, no technical or programming words, no maths. Short sentences.",
  "Format as Markdown: one short opening paragraph, then at most five bullet points, using **bold** for what matters and *italic* for the planner's own reasons.",
  "Never use an em dash or an en dash. Under 130 words.",
].join(" ");

const numbers = (text: string) => text.match(/\d+(?:[.:]\d+)?/g) ?? [];

/** The answer is used only when every figure in it is one the facts hold. */
function faithful(answer: string, facts: string): boolean {
  const known = new Set(numbers(facts));
  return numbers(answer).every((n) => known.has(n));
}

async function signedIn(request: NextRequest): Promise<boolean> {
  const cookie = request.headers.get("cookie");
  if (!cookie) return false;
  try {
    const session = await fetch(`${BACKEND_URL}/api/session`, { headers: { cookie }, signal: AbortSignal.timeout(5_000) });
    return session.ok;
  } catch {
    return false;
  }
}

export async function POST(request: NextRequest): Promise<NextResponse> {
  const none = NextResponse.json({ text: null });
  if (!KEY) return none;
  let body: { key?: unknown; facts?: unknown };
  try {
    body = (await request.json()) as { key?: unknown; facts?: unknown };
  } catch {
    return NextResponse.json({ text: null }, { status: 400 });
  }
  const key = typeof body.key === "string" ? body.key.slice(0, 200) : "";
  const facts = JSON.stringify(body.facts ?? null);
  if (!key || facts.length > MAX_FACTS) return NextResponse.json({ text: null }, { status: 400 });
  if (!(await signedIn(request))) return NextResponse.json({ text: null }, { status: 401 });

  const had = kept.get(key);
  if (had) return NextResponse.json({ text: had });

  try {
    const answer = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { authorization: `Bearer ${KEY}`, "content-type": "application/json" },
      body: JSON.stringify({
        model: MODEL,
        temperature: 0.2,
        max_tokens: 320,
        messages: [
          { role: "system", content: SYSTEM },
          { role: "user", content: `Facts:\n${facts}` },
        ],
      }),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!answer.ok) return none;
    const data = (await answer.json()) as { choices?: Array<{ message?: { content?: string } }> };
    const text = (data.choices?.[0]?.message?.content ?? "").replace(/\s*[–—]\s*/g, ", ").trim();
    if (!text || text.length > 1_500 || !faithful(text, facts)) return none;
    if (kept.size >= MAX_KEPT) kept.delete(kept.keys().next().value as string);
    kept.set(key, text);
    return NextResponse.json({ text });
  } catch {
    return none;
  }
}
