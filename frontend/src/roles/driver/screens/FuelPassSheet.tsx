"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { cx } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { fuelPassSrc, type FuelPassState } from "../data/fuelPass.ts";

const EXIT_MS = 200;

/**
 * Figma "Fuel QR Popup (Light)": the vehicle's official National Fuel Pass QR,
 * as the depot uploaded it, to scan at the pump (FF-06b). With no pass, the
 * popup says so in words rather than draw a code the pump would refuse.
 * Always light, in dark mode too: a code is scanned dark on white.
 */
export default function FuelPassSheet({
  vehicleId,
  kind,
  load,
  onClose,
}: {
  vehicleId: string;
  /** "Truck" or "Van". */
  kind: string;
  /** Reads the pass from the server, or the copy kept on this phone. */
  load: (vehicleId: string) => Promise<FuelPassState>;
  onClose: () => void;
}): React.JSX.Element {
  const [state, setState] = useState<FuelPassState | null>(null);
  useEffect(() => {
    let live = true;
    void load(vehicleId)
      .then((next) => live && setState(next))
      .catch(() => live && setState({ kind: "unavailable" }));
    return () => {
      live = false;
    };
  }, [load, vehicleId]);

  // Closing plays the way out first, then tells the owner to unmount.
  const [leaving, setLeaving] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const close = useCallback(() => {
    if (timer.current) return;
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    setLeaving(true);
    timer.current = setTimeout(onClose, still ? 0 : EXIT_MS);
  }, [onClose]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    []
  );

  // Drawn over the whole phone, header included, so one blur covers everything
  // behind it at once and lifts at once. Inside the home screen it sat under the
  // header's own layer and the header was blurred apart from the rest.
  const host = document.getElementById("driver-content") ?? document.body;
  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Fuel QR"
      data-full-frame
      className={cx(
        "absolute inset-0 z-50 flex items-center justify-center bg-black/35 p-5 backdrop-blur-[6px]",
        leaving ? "animate-fade-out" : "animate-fade-in"
      )}
      onClick={close}
    >
      <div
        onClick={(event) => event.stopPropagation()}
        className={cx(
          "flex w-full max-w-[300px] flex-col items-center gap-3 rounded-[32px] bg-white p-5 text-black shadow-2xl motion-reduce:animate-none",
          leaving ? "animate-pop-out" : "animate-pop-in"
        )}
      >
        <div className="flex flex-col items-center gap-0.5 pt-1">
          <h2 className="text-[22px] font-medium leading-[28px] tracking-tight">National Fuel Pass</h2>
          <p className="text-[13px] font-light leading-[18px] text-[#8A94A6]">
            {vehicleId} · {kind}
          </p>
        </div>
        {state?.kind === "ready" ? (
          <>
            <div className="w-full rounded-[28px] border-2 border-[#B7F2ED] bg-white p-3.5">
              <img src={fuelPassSrc(state.pass)} alt={`Fuel pass code for ${vehicleId}`} className="block aspect-square w-full object-contain [image-rendering:pixelated]" />
            </div>
            {state.fromDevice && (
              <p className="text-center text-[12px] leading-[16px] text-[#8A94A6]">Saved on this phone · for {dayLabel(state.pass.validUntil)}</p>
            )}
          </>
        ) : (
          <p role="status" className="flex min-h-[160px] w-full items-center justify-center rounded-[28px] border-2 border-[#B7F2ED] bg-white p-5 text-center text-[15px] leading-[21px]">
            {state === null ? "Getting the Fuel Pass…" : PASS_MISSING[state.kind]}
          </p>
        )}
      </div>
    </div>,
    host
  );
}

/** What the popup says when there is no code to show, in plain words. */
const PASS_MISSING = {
  none: "No Fuel Pass has been added for this vehicle yet. Ask the depot.",
  refused: "This Fuel Pass is only for the driver of this vehicle today.",
  unavailable: "No signal, and the Fuel Pass is not saved on this phone. Open it once with signal before you leave.",
} as const;
