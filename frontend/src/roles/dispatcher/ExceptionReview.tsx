import { useState, type FormEvent } from "react";
import { Modal, Btn, inputClass } from "@shared/ui/components";
import type { Order, ActFn } from "@shared/domain/types";

export default function ExceptionReview({ order, act, busy, onClose }: {
  order: Order; act: ActFn; busy: boolean; onClose: () => void;
}) {
  const [decision, setDecision] = useState("redeliver");
  const [error, setError] = useState("");
  const remaining = order.status === "disputed" ? order.units : order.units - (order.proof?.count || 0);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const ok = await act("resolve_exception", { order_id: order.id, version: order.version,
      decision, note: String(data.get("note")),
      ...(decision !== "close" ? { count: Number(data.get("count")) } : {}),
      ...(decision === "redeliver" ? { weight: Number(data.get("weight")), volume: Number(data.get("volume")) } : {}),
    });
    if (ok) onClose(); else setError("Resolution was not saved. Review the latest record and retry.");
  }
  return <Modal title="Resolve delivery exception" onClose={onClose}>
    <form onSubmit={submit} className="flex flex-col gap-4">
      <p>{order.id} · {order.units} ordered · {order.proof?.count || 0} delivered</p>
      <label>Decision<select className={inputClass} value={decision} onChange={e => setDecision(e.target.value)}>
        <option value="redeliver">Create replacement delivery</option>
        <option value="returned">Record completed return</option>
        <option value="close">Close with explanation</option>
      </select></label>
      <p className="text-caption text-muted">The original delivery evidence is retained. A replacement enters the next eligible planning queue; its arrival is not yet confirmed.</p>
      {decision !== "close" && <label>{decision === "returned" ? "Cases physically returned" : "Replacement cases"}<input className={inputClass} name="count" type="number" min="1" max={decision === "returned" ? order.units : remaining} defaultValue="1" required /></label>}
      {decision === "redeliver" && <>
        <label>Replacement weight (kg)<input className={inputClass} name="weight" type="number" min="0.01" max="1000000" step="any" required /></label>
        <label>Replacement volume (m³)<input className={inputClass} name="volume" type="number" min="0.001" max="10000" step="any" required /></label>
      </>}
      <label>Agreement and follow-up details<textarea className={inputClass} name="note" rows={4} maxLength={500} required /></label>
      {error && <p role="alert">{error}</p>}
      <Btn type="submit" variant="primary" disabled={busy}>Confirm resolution</Btn>
    </form>
  </Modal>;
}
