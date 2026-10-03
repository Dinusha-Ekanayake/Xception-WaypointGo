import type { ReactNode } from "react";
import { Button, Card } from "../../ui.tsx";

// "Receipt summary" (Figma 06): what arrived, what is wrong, and the two ways to
// finish: submit the count, or say something else is wrong (a dispute).

export default function SummaryCard({
  expected,
  received,
  problems,
  sends,
  closed,
  dispute,
  note,
  onNote,
  busy,
  onSubmit,
  onToggleDispute,
  children,
}: {
  expected: number;
  received: number;
  /** Items the store reported plus those the loader kept back. */
  problems: number;
  /** Whether submitting tells the dispatcher something new. */
  sends: boolean;
  closed: boolean;
  dispute: boolean;
  note: string;
  onNote: (note: string) => void;
  busy: boolean;
  onSubmit: () => void;
  onToggleDispute: () => void;
  /** The handover card of an answered receipt. */
  children?: ReactNode;
}): React.JSX.Element {
  return (
    <Card label="Receipt summary" className="lg:sticky lg:top-8">
      <h2 className="text-[20px] font-medium text-black">Receipt summary</h2>
      <div className="grid grid-cols-2 gap-2.5">
        <div className="flex flex-col rounded-[16px] bg-go-canvas px-3.5 py-3">
          <span className="text-[12px] text-go-muted">Packages</span>
          <span className="text-[28px] leading-tight font-semibold text-black">{expected}</span>
        </div>
        <div className="flex flex-col rounded-[16px] bg-go-canvas px-3.5 py-3">
          <span className="text-[12px] text-go-muted">Received OK</span>
          <span className="text-[28px] leading-tight font-semibold text-black">{received}</span>
        </div>
      </div>
      <p className="flex justify-between text-[14px]">
        <span className="text-go-muted">Package issues</span>
        <span className={problems > 0 ? "font-medium text-go-danger-strong" : "text-black"}>
          {problems} {problems === 1 ? "package" : "packages"}
        </span>
      </p>
      {!closed && <p className="text-[13px] text-go-muted">{sends ? "Sent to the dispatcher" : "Nothing new for the dispatcher"}</p>}
      {!closed && (dispute || problems > 0) && (
        <label className="flex flex-col gap-1 text-[13px] text-go-muted">
          {dispute ? "What is wrong? (required)" : "Note for the dispatcher (optional)"}
          <textarea value={note} onChange={(e) => onNote(e.target.value)} rows={2} className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black" />
        </label>
      )}
      {children}
      {!closed && (
        <>
          <Button large disabled={busy || (dispute && !note.trim())} onClick={onSubmit}>
            {busy ? "Sending…" : dispute ? "Send dispute" : "Submit count"}
          </Button>
          <Button tone="plain" onClick={onToggleDispute}>
            {dispute ? "Back to the count" : "Something else is wrong"}
          </Button>
        </>
      )}
    </Card>
  );
}
