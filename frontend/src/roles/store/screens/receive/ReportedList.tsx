import { Icon } from "@shared/ui";
import type { Report } from "../../data/receive.ts";
import { Card, Chip } from "../../ui.tsx";

// "Reported · N packages" (Figma 06-3, 06-5). The store's own reports can be
// taken back until the count is sent; what the loader kept back at the dock is
// listed beside them, greyed and fixed, because it is already with dispatch.

export default function ReportedList({
  reports,
  known,
  onRemove,
}: {
  reports: Report[];
  known: ReadonlyMap<string, number>;
  onRemove: (index: number) => void;
}): React.JSX.Element | null {
  const keptBack = [...known.entries()];
  const total = reports.length + keptBack.length;
  if (total === 0) return null;
  return (
    <Card label="Reported">
      <h2 className="text-[18px] font-medium text-black">
        Reported · {total} {total === 1 ? "item" : "items"}
      </h2>
      <ul className="flex flex-col gap-2">
        {reports.map((x, i) => (
          <li key={`${x.productId}-${x.kind}-${i}`} className="flex items-center gap-2 text-[14px] text-black">
            <span className="min-w-0 flex-1 truncate">
              {x.productId}
              {x.units > 0 && ` · ${x.units}`}
              {x.photoIds.length > 0 && ` · ${x.photoIds.length} ${x.photoIds.length === 1 ? "photo" : "photos"}`}
            </span>
            <Chip tone={x.kind === "Missing" || x.kind === "Other" ? "warn" : "danger"}>{x.kind}</Chip>
            <button type="button" aria-label={`Remove ${x.kind} for ${x.productId}`} onClick={() => onRemove(i)} className="flex size-12 items-center justify-center">
              <Icon name="close" />
            </button>
          </li>
        ))}
        {keptBack.map(([productId, units]) => (
          <li key={`known-${productId}`} className="flex items-center gap-2 text-[14px] text-go-muted">
            <span className="min-w-0 flex-1 truncate">
              {productId} · {units}
            </span>
            <Chip tone="muted">Short at loading</Chip>
            <span className="size-12" aria-hidden />
          </li>
        ))}
      </ul>
      {keptBack.length > 0 && <p className="text-[13px] text-go-muted">Short at loading is already with dispatch; it is not reported again.</p>}
    </Card>
  );
}
