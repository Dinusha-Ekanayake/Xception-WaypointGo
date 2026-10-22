"use client";

import { useState } from "react";
import { friendlyError } from "@shared/api/problem";
import { IssueCommandKind, type DecisionView, type HeardView } from "@shared/domain/types";
import { clockOn } from "@shared/wording";
import { useCommand } from "../data/useCommand.ts";

// R-ML-45: a store that answers the room's question by writing in the chat ("ba,
// ada ganna ba", "yes till 3") is heard here. Nothing is recorded from the words
// by itself: the dispatcher records the answer with one tap, the store's words
// kept as its reason, or says it is not an answer. Words shown masked, as read.

const READS: Record<HeardView["reads"], string> = { YES: "reads as Yes", NO: "reads as No", LATER: "reads as a later time" };
const WORDS_SHOWN = 120;

export default function HeardAnswers({
  heard,
  decisions,
  online,
  onRecorded,
}: {
  heard: HeardView[];
  decisions: DecisionView[];
  online: boolean;
  onRecorded: () => void;
}): React.JSX.Element | null {
  const { run, busy } = useCommand();
  const [waved, setWaved] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const shown = heard.filter((h) => !waved.includes(h.messageId) && decisions.some((d) => d.requestId === h.requestId && d.state === "sent"));
  if (shown.length === 0) return null;

  const record = async (h: HeardView, answer: "yes" | "no") => {
    const asked = decisions.find((d) => d.requestId === h.requestId);
    if (!asked) return;
    setError(null);
    const sent = await run(IssueCommandKind.answerDecision, { requestId: h.requestId, answer, inChat: true, reason: `"${h.words}"` }, asked.rowVersion);
    if (sent.ok) onRecorded();
    else setError(friendlyError(sent.error));
  };
  const quiet = "min-h-8 rounded-full bg-go-card px-3 text-[12px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-40";
  const primary = "min-h-8 rounded-full bg-go-ink px-3 text-[12px] font-medium text-go-card disabled:opacity-40";
  const off = !online || busy;

  return (
    <ul aria-label="Answers written in the chat" className="flex flex-col gap-1.5">
      {shown.map((h) => {
        const words = h.words.length > WORDS_SHOWN ? `${h.words.slice(0, WORDS_SHOWN)}…` : h.words;
        return (
          <li key={h.messageId} className="flex flex-col gap-1.5 rounded-xl bg-go-card px-3 py-2">
            <p className="text-[12px] text-go-ink">
              {`${h.outletId} wrote "${words}" · ${clockOn(h.at)} · `}
              <span className="text-go-secondary">{`${READS[h.reads]}, read automatically`}</span>
            </p>
            <span className="flex flex-wrap gap-1.5">
              <button type="button" disabled={off} className={h.reads === "YES" ? primary : quiet} onClick={() => void record(h, "yes")}>
                Record as Yes
              </button>
              <button type="button" disabled={off} className={h.reads === "NO" ? primary : quiet} onClick={() => void record(h, "no")}>
                Record as No
              </button>
              <button type="button" className={quiet} onClick={() => setWaved((w) => [...w, h.messageId])}>
                Not an answer
              </button>
            </span>
          </li>
        );
      })}
      {error && <li role="alert" className="text-[12px] text-go-danger">{error}</li>}
    </ul>
  );
}
