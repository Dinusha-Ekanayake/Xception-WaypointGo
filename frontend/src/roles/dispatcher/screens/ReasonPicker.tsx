"use client";

// A reason a dispatcher can read back later (rule 8): a few common ones to pick
// and a field for their own words. The server needs at least three characters.

const COMMON = ["Outlet asked to skip", "No vehicle with room", "Stock not ready", "Vehicle fault", "Store manager agreed"];

export const MIN_REASON = 3;

export function reasonReady(reason: string): boolean {
  return reason.trim().length >= MIN_REASON;
}

export default function ReasonPicker({
  label,
  value,
  onChange,
  placeholder = "Recorded with the plan",
}: {
  label: string;
  value: string;
  onChange: (reason: string) => void;
  placeholder?: string;
}): React.JSX.Element {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
        {label}
        <input
          value={value}
          maxLength={300}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal"
        />
      </label>
      <div role="group" aria-label="Common reasons" className="flex flex-wrap gap-1.5">
        {COMMON.map((reason) => (
          <button
            key={reason}
            type="button"
            aria-pressed={value === reason}
            onClick={() => onChange(reason)}
            className={`rounded-full px-2.5 py-1 text-xs font-medium ${value === reason ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink"}`}
          >
            {reason}
          </button>
        ))}
      </div>
    </div>
  );
}
