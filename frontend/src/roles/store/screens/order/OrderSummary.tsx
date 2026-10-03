import type { Temperature } from "@shared/domain/types";
import { productLines, units } from "../../data/format.ts";
import { Button } from "../../ui.tsx";

// The summary card of "03 Place order": a bottom sheet on a phone, a card beside
// the list on a desktop. Cases only: weight and volume are the warehouse's,
// returned once the order is placed, never summed from catalogue lines here.

export default function OrderSummary({
  delivery,
  classes,
  lines,
  amend,
  busy,
  canSubmit,
  onCancel,
  onSaveDraft,
  onSubmit,
}: {
  /** "Tue 29 Sep · 05:00-07:30". */
  delivery: string;
  classes: Temperature[];
  /** Product lines with a quantity and their units, per class. */
  lines: (t: Temperature) => { items: number; units: number };
  amend: boolean;
  busy: boolean;
  canSubmit: boolean;
  onCancel: () => void;
  onSaveDraft: () => void;
  onSubmit: () => void;
}): React.JSX.Element {
  const total = classes.reduce((s, t) => s + lines(t).units, 0);
  const orders = classes.filter((t) => lines(t).items > 0).length;
  return (
    <div className="fixed inset-x-0 bottom-0 z-30 flex justify-center lg:sticky lg:top-8 lg:z-auto">
      <div className="flex w-full max-w-[720px] flex-col gap-3 rounded-t-[32px] bg-white px-6 pt-5 pb-7 shadow-[0_-5px_20px_rgba(0,0,0,0.06)] lg:rounded-[26px] lg:shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
        <h2 className="hidden text-[20px] font-medium text-black lg:block">Order summary</h2>
        <div className="flex flex-col rounded-[16px] bg-go-mint/70 px-4 py-3">
          <span className="text-[12px] text-go-muted">Delivery</span>
          <span className="text-[16px] font-medium text-black">{delivery}</span>
        </div>
        {/* Desktop: one line per class with its own count, as in "03 Place order". */}
        <ul className="hidden flex-col gap-2 text-[14px] lg:flex">
          {classes.map((t) => {
            const { items, units: n } = lines(t);
            return (
              <li key={t} className="flex justify-between gap-3">
                <span className="text-black">{t === "chilled" ? "Chilled order" : "Ambient order"}</span>
                <span className="text-go-muted">
                  {productLines(items)} · {units(n)}
                </span>
              </li>
            );
          })}
        </ul>
        <p className="flex items-baseline justify-between gap-3 border-t border-[#dfe7e6] pt-3 text-[28px] leading-tight font-semibold text-black lg:text-[18px]">
          <span className="hidden text-[15px] font-medium lg:inline">Total</span>
          <span>
            {units(total)}
            <span className="lg:hidden">{!amend && ` · ${orders} ${orders === 1 ? "order" : "orders"}`}</span>
          </span>
        </p>
        <div className="flex gap-2.5 lg:flex-col-reverse">
          <Button tone="plain" large onClick={onCancel}>
            Cancel
          </Button>
          {!amend && (
            <Button tone="plain" large disabled={total === 0} onClick={onSaveDraft}>
              Save draft
            </Button>
          )}
          <Button large disabled={total === 0 || busy || !canSubmit} onClick={onSubmit}>
            {busy ? "Sending…" : amend ? "Save change" : "Submit order"}
          </Button>
        </div>
      </div>
    </div>
  );
}
