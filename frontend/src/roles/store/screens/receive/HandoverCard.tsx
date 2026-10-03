import type { HandoverView } from "@shared/domain/types";
import { clock } from "../../data/format.ts";
import { Button } from "../../ui.tsx";

/**
 * Where the handover PIN stands on a receipt answered earlier (R-RCP-09). The
 * PIN itself is never shown again (only a hash is kept), so a lost one is
 * replaced.
 */
export default function HandoverCard({
  handover,
  missing,
  error,
  onNewPin,
}: {
  handover: HandoverView | null;
  missing: boolean;
  error: string | null;
  onNewPin: (h: HandoverView) => void;
}): React.JSX.Element {
  const words = !handover
    ? missing
      ? "No handover PIN was issued for this delivery."
      : "Checking the handover…"
    : handover.status === "CONFIRMED"
      ? `Confirmed with PIN${handover.confirmedAt ? ` at ${clock(handover.confirmedAt)}` : ""}.`
      : handover.status === "AWAITING"
        ? `Waiting for the driver to enter the PIN · expires ${clock(handover.expiresAt)}.`
        : handover.status === "LOCKED"
          ? "Locked after five wrong entries."
          : "The PIN expired before the driver entered it.";
  return (
    <section aria-label="Handover" className="flex flex-col gap-2 rounded-[16px] bg-go-canvas px-4 py-3">
      <h3 className="text-[15px] font-medium text-black">Handover</h3>
      <p className="text-[14px] text-black">{words}</p>
      {error && <p className="text-[13px] font-medium text-go-danger-strong">{error}</p>}
      {handover && handover.status !== "CONFIRMED" && (
        <Button tone="plain" onClick={() => onNewPin(handover)}>
          Get a new PIN
        </Button>
      )}
    </section>
  );
}
