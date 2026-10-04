import type { OrderView, ReceiptLineView } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import { temperatureLabel, units as unitsText } from "../../data/format.ts";
import { KINDS, LOWERS_COUNT, type Kind, type Report } from "../../data/receive.ts";
import { Card, Stepper } from "../../ui.tsx";

// "Report a package issue" (Figma 06, 06-1 to 06-7). An item line stands where
// Figma shows a package: the receipt counts per product (D-E). What the loader
// kept back at the dock is marked on its item and is not the store's to report.

export type PendingPhoto = { id: string; url: string };

export default function ReportCard({
  orderId,
  siblings,
  onSwitch,
  lines,
  known,
  reports,
  selected,
  onSelect,
  kind,
  onKind,
  units,
  room,
  onUnits,
  photos,
  onTakePhoto,
  onOpenPhoto,
  onAdd,
  addError,
  closed,
}: {
  orderId: string;
  /** This vehicle's orders still waiting to be counted, this one included (06-4). */
  siblings: OrderView[];
  onSwitch: (orderId: string) => void;
  lines: ReceiptLineView[];
  known: ReadonlyMap<string, number>;
  reports: Report[];
  selected: string | null;
  onSelect: (productId: string) => void;
  kind: Kind | null;
  onKind: (kind: Kind) => void;
  units: number;
  room: number;
  onUnits: (n: number) => void;
  photos: PendingPhoto[];
  onTakePhoto: () => void;
  onOpenPhoto: (id: string) => void;
  onAdd: () => void;
  addError: string | null;
  closed: boolean;
}): React.JSX.Element {
  const expected = lines.reduce((s, l) => s + l.expectedQuantity, 0);
  return (
    <Card label="Report an issue">
      <h2 className="text-[18px] font-medium text-black">Report a problem with a product line</h2>

      <label className="flex flex-col gap-1.5 text-[13px] text-go-muted">
        Order
        <span className="relative flex">
          <select
            value={orderId}
            disabled={siblings.length < 2}
            onChange={(e) => onSwitch(e.target.value)}
            className="min-h-14 w-full appearance-none rounded-[16px] border border-[#dfe7e6] bg-go-canvas px-4 pr-10 text-[15px] font-medium text-black disabled:opacity-100"
          >
            {siblings.map((o) => (
              <option key={o.orderId} value={o.orderId}>
                {o.orderRef} · {temperatureLabel(o.temperature)} · {unitsText(o.itemCount)}
              </option>
            ))}
          </select>
          {siblings.length > 1 && (
            <span aria-hidden className="pointer-events-none absolute top-1/2 right-4 -translate-y-1/2">
              <Icon name="chevron-down" />
            </span>
          )}
        </span>
      </label>

      <p className="text-[13px] text-go-muted">
        Product line · {unitsText(expected)}
      </p>
      <ul className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3" aria-label="Items">
        {lines.map((l) => {
          const flagged = reports.filter((x) => x.productId === l.productId);
          const keptBack = known.get(l.productId) ?? 0;
          const bad = flagged.some((x) => x.kind === "Damaged" || x.kind === "Wrong item");
          return (
            <li key={l.productId}>
              <button
                type="button"
                disabled={closed}
                aria-pressed={selected === l.productId}
                onClick={() => onSelect(l.productId)}
                className={cx(
                  "flex min-h-14 w-full flex-col rounded-[14px] px-3 py-2 text-left",
                  bad ? "bg-go-danger-tint" : flagged.length ? "bg-go-warning-tint" : "bg-go-canvas",
                  selected === l.productId && "outline-2 outline-[#0f766e]",
                )}
              >
                <span className={cx("text-[11px]", bad ? "text-go-danger-strong" : "text-go-muted")}>
                  {l.expectedQuantity} expected
                  {keptBack > 0 && ` · Short at loading (${keptBack})`}
                  {flagged.length > 0 && ` · ${flagged.map((x) => x.kind).join(", ")}`}
                </span>
                <span className="truncate text-[14px] font-medium text-black">{l.productId}</span>
              </button>
            </li>
          );
        })}
      </ul>

      {!closed && (
        <>
          <p className="text-[13px] text-go-muted">Issue</p>
          <div role="radiogroup" aria-label="Issue" className="flex flex-wrap gap-2">
            {KINDS.map((k) => (
              <button
                key={k}
                type="button"
                role="radio"
                aria-checked={kind === k}
                onClick={() => onKind(k)}
                className={cx("min-h-12 rounded-full px-4 text-[14px]", kind === k ? "bg-[#031a0c] text-white" : "bg-go-canvas text-black")}
              >
                {k}
              </button>
            ))}
          </div>
          {addError && (
            <p role="alert" className="text-[13px] font-medium text-go-danger-strong">
              {addError}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" onClick={onTakePhoto} className="min-h-12 rounded-full border border-dashed border-[#b9c6c4] px-4 text-[14px] text-go-muted">
              + Photo (optional)
            </button>
            {photos.map((p, i) => (
              <button key={p.id} type="button" aria-label={`Photo ${i + 1}`} onClick={() => onOpenPhoto(p.id)} className="size-12 overflow-hidden rounded-[10px]">
                {/* A local object URL of a photo just taken; nothing for next/image to optimise. */}
                <img src={p.url} alt="" className="size-full object-cover" />
              </button>
            ))}
            {kind && LOWERS_COUNT[kind] && selected && (
              <Stepper value={units} label="Units affected" onChange={(n) => onUnits(Math.min(Math.max(1, n), Math.max(1, room)))} />
            )}
            <span className="flex-1" />
            <button type="button" onClick={onAdd} className="min-h-12 rounded-[22px] bg-go-mint px-6 text-[15px] font-medium text-black">
              Add issue
            </button>
          </div>
        </>
      )}
    </Card>
  );
}
