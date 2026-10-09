"use client";

import { useState } from "react";
import { initialsOf } from "./SettingsPanel.tsx";

// Roadmap FF-07: a person's photo, so the people who work with them that day
// recognise them. The server decides who may see it and answers 403 or 404
// otherwise; either way, and with no signal, the circle shows the initials. The
// browser keeps the image privately for five minutes at most, so a removed photo
// stops showing within that time.

export function PersonPhoto({
  src,
  name,
  size = 48,
}: {
  src: string | null;
  name: string;
  size?: number;
}): React.JSX.Element {
  const [failed, setFailed] = useState<string | null>(null);
  const style = { width: size, height: size, fontSize: Math.round(size * 0.36) };
  if (!src || failed === src) {
    return (
      <span
        aria-hidden="true"
        style={style}
        className="flex shrink-0 items-center justify-center rounded-full bg-go-mint font-semibold text-black"
      >
        {initialsOf(name)}
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element -- a private, per-person image from the API, not a static asset
    <img
      src={src}
      alt={name}
      width={size}
      height={size}
      style={style}
      className="shrink-0 rounded-full bg-go-mint object-cover"
      onError={() => setFailed(src)}
    />
  );
}
