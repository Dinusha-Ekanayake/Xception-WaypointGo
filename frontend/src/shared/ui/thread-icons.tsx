// The thread's small icons (issue #136), drawn in currentColor so each takes
// the colour of the button it sits in, in light and dark.

type P = { className?: string };
const base = (className?: string) => ({
  className: className ?? "size-5",
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
});

export function MicIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
    </svg>
  );
}

export function SendIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <path d="M4 12 20 4l-6 16-3-7-7-1Z" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function PlayIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5Z" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function PauseIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <rect x="6.5" y="5" width="4" height="14" rx="1.2" fill="currentColor" stroke="none" />
      <rect x="13.5" y="5" width="4" height="14" rx="1.2" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function StopIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <rect x="6" y="6" width="12" height="12" rx="2.5" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function TrashIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <path d="M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13M10 11v6M14 11v6" />
    </svg>
  );
}

export function LockIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <rect x="5" y="11" width="14" height="10" rx="2.5" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

export function ChevronUpIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <path d="m6 15 6-6 6 6" />
    </svg>
  );
}

export function ChevronLeftIcon({ className }: P): React.JSX.Element {
  return (
    <svg {...base(className)}>
      <path d="m15 6-6 6 6 6" />
    </svg>
  );
}
