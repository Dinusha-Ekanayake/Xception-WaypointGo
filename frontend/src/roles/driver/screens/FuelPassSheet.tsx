"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { cx } from "@shared/ui";
import qrcode from "qrcode-generator";

const EXIT_MS = 200;

/**
 * Figma "Fuel QR Popup (Light)": the vehicle's fuel pass as a code to scan at
 * the pump, centred over the phone. The code carries the vehicle id; the
 * pass number and fuel type of the design are not in the data the phone holds.
 * Always light, in dark mode too: a code is scanned dark on white.
 */
export default function FuelPassSheet({
  vehicleId,
  kind,
  onClose,
}: {
  vehicleId: string;
  /** "Truck" or "Van". */
  kind: string;
  onClose: () => void;
}): React.JSX.Element {
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

  const { size, dark } = useMemo(() => {
    const code = qrcode(0, "M");
    code.addData(vehicleId);
    code.make();
    const count = code.getModuleCount();
    const cells: Array<[number, number]> = [];
    for (let row = 0; row < count; row += 1) {
      for (let column = 0; column < count; column += 1) {
        if (code.isDark(row, column)) cells.push([column, row]);
      }
    }
    return { size: count, dark: cells };
  }, [vehicleId]);

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
        <div className="w-full rounded-[28px] border-2 border-[#B7F2ED] bg-white p-3.5">
          <svg
            role="img"
            aria-label={`Fuel pass code for ${vehicleId}`}
            viewBox={`0 0 ${size} ${size}`}
            shapeRendering="crispEdges"
            className="block aspect-square w-full"
          >
            <rect width={size} height={size} fill="#fff" />
            {dark.map(([x, y]) => (
              <rect key={`${x}-${y}`} x={x} y={y} width="1" height="1" fill="#000" />
            ))}
          </svg>
        </div>
      </div>
    </div>,
    host
  );
}
