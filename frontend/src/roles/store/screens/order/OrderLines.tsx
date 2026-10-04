"use client";

import type { LineAvailability, ProductView, Temperature } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import type { Usual } from "../../data/lines.ts";
import { Muted, Stepper } from "../../ui.tsx";
import ItemPicker from "./ItemPicker.tsx";
import { useT } from "../../i18n.tsx";

// The order's lines, as in "03 Place order" and "03d Item added": a tab per
// class, the picker, then one row per item beside its usual quantity. A count
// above the usual turns teal; the row just added stays tinted until the next.

export default function OrderLines({
  classes,
  temperature,
  onTemperature,
  itemCount,
  rows,
  products,
  usual,
  quantities,
  short,
  fresh,
  loading,
  onLine,
  onAdd,
}: {
  classes: Temperature[];
  temperature: Temperature;
  onTemperature: (t: Temperature) => void;
  /** Lines with a quantity, per class, for the tabs. */
  itemCount: (t: Temperature) => number;
  rows: string[];
  products: ProductView[];
  usual: ReadonlyMap<string, Usual>;
  quantities: Readonly<Record<string, number>>;
  /** Lines the warehouse could not supply in full (D-F). */
  short: LineAvailability[];
  /** The row added last. */
  fresh: string | null;
  loading: boolean;
  onLine: (productId: string, quantity: number) => void;
  onAdd: (productId: string) => void;
}): React.JSX.Element {
  const tr = useT();
  return (
    <div className="flex min-w-0 flex-col gap-4 lg:rounded-[26px] lg:bg-white lg:p-6">
      {classes.length > 1 && (
        <div className="flex gap-2 rounded-full bg-white/60 p-1 lg:self-start lg:bg-go-canvas" role="tablist" aria-label={tr("Temperature")}>
          {classes.map((t) => {
            const n = itemCount(t);
            return (
              <button
                key={t}
                type="button"
                role="tab"
                aria-selected={t === temperature}
                onClick={() => onTemperature(t)}
                className={cx(
                  "flex min-h-12 flex-1 items-center justify-center gap-2 rounded-full px-4 text-[15px] whitespace-nowrap lg:text-[16px]",
                  t === temperature ? "bg-white font-medium text-black shadow-[0_5px_20px_rgba(0,0,0,0.09)]" : "text-go-muted",
                )}
              >
                <Icon name={t === "chilled" ? "chilled" : "box"} />
                <span>
                  {tr(t === "chilled" ? "Chilled" : "Ambient")}
                  <span className="hidden lg:inline">{tr(" order")}</span> · {tr(n === 1 ? "{n} item" : "{n} items", { n })}
                </span>
              </button>
            );
          })}
        </div>
      )}

      <ItemPicker temperature={temperature} products={products} rows={rows} loading={loading} onAdd={onAdd} />

      {/* Desktop column heads, as in "03 Place order". */}
      <div aria-hidden className="hidden grid-cols-[minmax(0,1fr)_72px_168px] gap-2 px-4 text-[13px] text-go-muted lg:grid">
        <span>{tr("Item")}</span>
        <span>{tr("Usual")}</span>
        <span className="text-center">{tr("Order")}</span>
      </div>
      <ul className="flex flex-col gap-2.5">
        {rows.map((id) => {
          const n = quantities[id] ?? 0;
          const s = short.find((x) => x.productId === id);
          const u = usual.get(id)?.quantity;
          // Unknown while the catalogue cannot be read, so labelled inferred to be safe.
          const inferred = !products.find((p) => p.productId === id)?.verifiedRealSku;
          return (
            <li
              key={id}
              className={cx(
                "flex items-center gap-2 rounded-[16px] py-2.5 pr-2.5 pl-3.5 lg:border",
                id === fresh ? "bg-go-mint/25 lg:border-go-mint" : "bg-white lg:border-[#dfe7e6]",
                s && "outline-2 outline-go-danger",
              )}
            >
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[16px] font-medium text-black">{id}</span>
                <span className="text-[13px] text-go-muted">
                  {inferred && <span title={tr("Reconstructed from order totals, not a confirmed product")}>{tr("inferred")}</span>}
                  <span className="lg:hidden">
                    {inferred && " · "}
                    {u !== undefined ? tr("usual {n}", { n: u }) : tr("not ordered before")}
                  </span>
                </span>
                {s && (
                  <span className="text-[13px] font-medium text-go-danger-strong">
                    {tr("Only {n} available", { n: s.available })} ·{" "}
                    <button type="button" className="underline" onClick={() => onLine(id, s.available)}>
                      {tr("use {n}", { n: s.available })}
                    </button>
                  </span>
                )}
              </div>
              <span className="hidden w-[72px] shrink-0 text-[15px] text-go-muted lg:block">{u ?? "·"}</span>
              <Stepper value={n} label={id} highlight={u !== undefined && n > u} onChange={(v) => onLine(id, v)} />
            </li>
          );
        })}
      </ul>
      {rows.length === 0 ? (
        <Muted>{tr(loading ? "Loading the catalogue…" : "Nothing of this kind ordered before. Search above to add an item.")}</Muted>
      ) : (
        <p className="text-[13px] text-go-muted">{tr("Teal = above usual")}</p>
      )}
    </div>
  );
}
