import { cx } from "@shared/ui";

// One line of the Publish step's checks: a sign, what it is, why, and an action.

export default function Row({
  tone,
  title,
  note,
  action,
}: {
  tone: "ok" | "warning" | "danger";
  title: string;
  note: string;
  action?: { label: string; onClick: () => void };
}): React.JSX.Element {
  return (
    <li className="flex items-center gap-3 border-t border-go-rule py-3.5">
      <span
        aria-hidden
        className={cx(
          "flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold",
          tone === "ok" ? "bg-go-success-tint text-go-teal" : tone === "warning" ? "bg-go-warning-tint text-go-warning-text" : "bg-go-danger-tint text-go-danger-strong",
        )}
      >
        {tone === "ok" ? "✓" : "!"}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-medium text-go-ink">{title}</span>
        <span className="block text-xs text-go-secondary">{note}</span>
      </span>
      {action && (
        <button type="button" onClick={action.onClick} className="shrink-0 text-[13px] font-medium text-go-teal">
          {`${action.label} ›`}
        </button>
      )}
    </li>
  );
}
