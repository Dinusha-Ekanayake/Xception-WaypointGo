"use client";

import { useState } from "react";

/**
 * A password field with a show/hide button, so a person can check what they
 * typed. The text goes back to hidden on submit, as the field is cleared or
 * reused; the button never takes the field's label.
 */
export default function PasswordInput({
  className,
  value,
  onChange,
  disabled,
  label,
  placeholder,
}: {
  className: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  /** The field's accessible name when no wrapping label gives one. */
  label?: string;
  placeholder?: string;
}): React.JSX.Element {
  const [shown, setShown] = useState(false);
  return (
    <div className="relative">
      <input
        className={`${className} pr-14`}
        type={shown ? "text" : "password"}
        aria-label={label}
        placeholder={placeholder}
        autoComplete="current-password"
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        required
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      />
      <button
        type="button"
        onClick={() => setShown((s) => !s)}
        disabled={disabled}
        aria-label={shown ? "Hide password" : "Show password"}
        aria-pressed={shown}
        title={shown ? "Hide password" : "Show password"}
        className="absolute inset-y-0 right-1 my-auto flex h-11 w-11 items-center justify-center rounded-full text-go-muted hover:text-go-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-go-teal disabled:opacity-50"
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
          {shown && <line x1="3" y1="3" x2="21" y2="21" />}
        </svg>
      </button>
    </div>
  );
}
