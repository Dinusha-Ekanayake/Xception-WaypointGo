"use client";

import { cx } from "./primitives.tsx";

/**
 * An on and off switch with its label beside it, as the plan screen draws
 * "Lock" and "Only trips it can fit". A real button with role switch, so a
 * screen reader says on or off and the keyboard toggles it.
 */
export function Switch({
  label,
  checked,
  onChange,
  disabled = false,
  hint,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  /** A short line after the label, in the secondary colour. */
  hint?: string;
}): React.JSX.Element {
  return (
    <span className="flex items-center gap-2 text-[13px] text-go-ink">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cx(
          "relative h-[22px] w-[38px] shrink-0 rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-40",
          checked ? "bg-go-teal" : "bg-go-rule",
        )}
      >
        <span
          aria-hidden
          className={cx(
            "absolute top-[3px] size-4 rounded-full bg-go-card transition-[left]",
            checked ? "left-[19px]" : "left-[3px]",
          )}
        />
      </button>
      <span className="font-medium">{label}</span>
      {hint && <span className="text-go-secondary">{hint}</span>}
    </span>
  );
}
