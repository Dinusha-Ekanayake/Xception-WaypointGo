"use client";

import { useState } from "react";
import { cx } from "./primitives.tsx";
import { Sheet } from "./Sheet.tsx";

// "Connect AI assistant": how to add Waypoint to the assistant a user already
// has (Claude, ChatGPT). Waypoint holds no chatbot; this only shows the
// read-only MCP address and the steps. The address is always the shared host,
// because a token is bound to that one resource (docs/deployment.md).

function SparkIcon(): React.JSX.Element {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-[18px] fill-none stroke-current stroke-2">
      <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6" strokeLinecap="round" />
    </svg>
  );
}

/** The steps sheet. Shown by every role's button. */
export function McpSheet({ url, onClose }: { url: string; onClose: () => void }): React.JSX.Element {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(url).then(() => setCopied(true), () => setCopied(false));
  };
  return (
    <Sheet label="Connect AI assistant" onClose={onClose}>
      <h2 className="text-[22px] font-semibold">Connect your AI assistant</h2>
      <p className="text-[14px] text-go-muted">
        Let Claude or ChatGPT answer questions from the Waypoint records you can already see. It can only read, never change anything, and
        only within your role and scope.
      </p>
      <div className="flex items-center gap-2 rounded-[14px] bg-go-canvas p-2 pl-3">
        <code className="min-w-0 flex-1 truncate text-[14px]">{url}</code>
        <button type="button" onClick={copy} className="min-h-11 shrink-0 rounded-full bg-go-card px-4 text-[14px] font-medium text-go-teal">
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-[14px]">
        <li>
          <b>Claude:</b> Settings · Connectors · Add custom connector. <b>ChatGPT:</b> Settings · Apps and Connectors · add a remote MCP
          server with OAuth.
        </li>
        <li>Paste the address above and connect.</li>
        <li>Sign in to Waypoint on the page that opens, check the assistant named there, and approve.</li>
        <li>Ask: &quot;Use Waypoint to show my current access.&quot;</li>
      </ol>
      <p className="text-[13px] text-go-muted">Your password is never shared. To stop access, remove the connector in your assistant.</p>
      <button type="button" onClick={onClose} className="min-h-12 rounded-full bg-go-canvas text-[15px] font-medium">
        Done
      </button>
    </Sheet>
  );
}

/** A button that opens the steps sheet; `compact` is icon-only, like the other header pills. */
export function McpButton({ url, compact = false, className }: { url: string | null; compact?: boolean; className?: string }): React.JSX.Element | null {
  const [open, setOpen] = useState(false);
  if (!url) return null;
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="Connect AI assistant"
        title="Connect AI assistant"
        className={cx(
          className ??
            "flex min-h-12 shrink-0 items-center justify-center gap-2 rounded-full bg-white px-3.5 text-[14px] text-go-muted shadow-[0_5px_20px_rgba(0,0,0,0.09)]",
          compact && "size-12 px-0",
        )}
      >
        <SparkIcon />
        {!compact && <span>Connect AI</span>}
      </button>
      {open && <McpSheet url={url} onClose={() => setOpen(false)} />}
    </>
  );
}
