"use client";

import { useEffect, useRef, useState } from "react";
import { toBlob } from "../data/image.ts";

/**
 * The receiver signs with a finger. Pointer events, so a finger, a stylus and a
 * mouse all work; the page does not scroll while signing. The ink is always
 * dark on white, whatever the theme, because the image is evidence and must
 * read the same wherever it is shown.
 */
export default function SignaturePad({ onChange }: { onChange: (signature: Blob | null) => void }): React.JSX.Element {
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const [signed, setSigned] = useState(false);
  const changed = useRef(onChange);
  changed.current = onChange;

  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    // Match the backing store to the display size once, so strokes are crisp.
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    el.width = Math.round(el.clientWidth * ratio);
    el.height = Math.round(el.clientHeight * ratio);
    const context = el.getContext("2d");
    if (!context) return;
    context.scale(ratio, ratio);
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, el.clientWidth, el.clientHeight);
    context.strokeStyle = "#031b08";
    context.lineWidth = 2.5;
    context.lineCap = "round";
    context.lineJoin = "round";
  }, []);

  const point = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    return { x: event.clientX - box.left, y: event.clientY - box.top };
  };

  const down = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const context = canvas.current?.getContext("2d");
    if (!context) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drawing.current = true;
    const { x, y } = point(event);
    context.beginPath();
    context.moveTo(x, y);
    // A tap leaves a dot.
    context.lineTo(x + 0.1, y + 0.1);
    context.stroke();
  };

  const move = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current) return;
    const context = canvas.current?.getContext("2d");
    if (!context) return;
    const { x, y } = point(event);
    context.lineTo(x, y);
    context.stroke();
  };

  const up = () => {
    if (!drawing.current || !canvas.current) return;
    drawing.current = false;
    setSigned(true);
    void toBlob(canvas.current, "image/png").then((blob) => changed.current(blob)).catch(() => changed.current(null));
  };

  const clear = () => {
    const el = canvas.current;
    const context = el?.getContext("2d");
    if (!el || !context) return;
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, el.clientWidth, el.clientHeight);
    setSigned(false);
    changed.current(null);
  };

  return (
    <div className="flex flex-col gap-2">
      <canvas
        ref={canvas}
        aria-label="Signature of the person receiving the goods"
        role="img"
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={up}
        className="h-36 w-full touch-none rounded-[14px] border border-go-rule bg-white"
      />
      <div className="flex items-center justify-between text-[13px] text-go-muted">
        <span>{signed ? "Signed" : "Sign inside the box"}</span>
        <button type="button" onClick={clear} disabled={!signed} className="min-h-12 px-3 text-[15px] font-medium text-go-teal disabled:opacity-40">
          Clear
        </button>
      </div>
    </div>
  );
}
