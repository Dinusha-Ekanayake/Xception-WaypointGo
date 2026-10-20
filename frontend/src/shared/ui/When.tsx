import type { IsoDate } from "@shared/domain/types";
import { clock, dayOf, shortDay } from "@shared/wording";

/**
 * A time with its day in small letters beside it ("05:10 21 Oct"), so a time
 * is never read as today's by mistake. An instant takes its own depot day; a
 * wall-clock time needs `day`, and without one shows the time alone.
 */
export function When({ at, day, className }: { at: string | Date | null | undefined; day?: IsoDate | null; className?: string }): React.JSX.Element {
  const date = dayOf(at, day);
  return (
    <span className={`whitespace-nowrap${className ? ` ${className}` : ""}`}>
      {clock(at)}
      {date && <span className="ml-1 text-[0.75em] font-normal opacity-70">{shortDay(date)}</span>}
    </span>
  );
}
