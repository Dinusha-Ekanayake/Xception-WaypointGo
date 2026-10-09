import { Icon } from "@shared/ui";
import type { Report } from "../../data/receive.ts";
import { productLabel } from "../../data/format.ts";
import { Card, Chip } from "../../ui.tsx";
import { useT } from "../../i18n.tsx";

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
  const t = useT();
  const keptBack = [...known.entries()];
  const total = reports.length + keptBack.length;
  if (total === 0) return null;
  return (
    <Card label={t("Reported")}>
      <h2 className="text-[18px] font-medium text-black">
        {total === 1 ? t("Reported · 1 item") : t("Reported · {n} items", { n: total })}
      </h2>
      <ul className="flex flex-col gap-2">
        {reports.map((x, i) => (
          <li key={`${x.productId}-${x.kind}-${i}`} className="flex items-center gap-2 text-[14px] text-black">
            <span className="min-w-0 flex-1 truncate">
              {productLabel(x.productId)}
              {x.units > 0 && ` · ${x.units}`}
              {x.photoIds.length > 0 && (x.photoIds.length === 1 ? t(" · 1 photo") : t(" · {n} photos", { n: x.photoIds.length }))}
            </span>
            <Chip tone={x.kind === "Missing" || x.kind === "Other" ? "warn" : "danger"}>{t(x.kind)}</Chip>
            <button type="button" aria-label={t("Remove {kind} for {product}", { kind: t(x.kind), product: productLabel(x.productId) })} onClick={() => onRemove(i)} className="flex size-12 items-center justify-center">
              <Icon name="close" />
            </button>
          </li>
        ))}
        {keptBack.map(([productId, units]) => (
          <li key={`known-${productId}`} className="flex items-center gap-2 text-[14px] text-go-muted">
            <span className="min-w-0 flex-1 truncate">
              {productLabel(productId)} · {units}
            </span>
            <Chip tone="muted">{t("Short at loading")}</Chip>
            <span className="size-12" aria-hidden />
          </li>
        ))}
      </ul>
      {keptBack.length > 0 && <p className="text-[13px] text-go-muted">{t("Short at loading is already with dispatch; it is not reported again.")}</p>}
    </Card>
  );
}
