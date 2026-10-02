"use client";

/** The day a screen is looking at, in the header of every dated dispatcher screen. */
export default function DayPicker({ date, onDate }: { date: string; onDate: (date: string) => void }): React.JSX.Element {
  return (
    <label className="flex shrink-0 items-center gap-1.5 rounded-[20px] bg-white px-3.5 py-2.5 text-[13px] font-medium text-go-ink">
      <span className="text-go-secondary">Day</span>
      <input type="date" value={date} onChange={(event) => event.target.value && onDate(event.target.value)} className="bg-transparent outline-none" />
    </label>
  );
}
