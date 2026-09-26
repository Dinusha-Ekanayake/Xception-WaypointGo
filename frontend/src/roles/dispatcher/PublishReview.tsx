"use client";

import { useState } from "react";
import { Btn, Modal } from "@shared/ui/components";
import type { ActFn, Order, Plan } from "@shared/domain/types";

export default function PublishReview({ plan, orders, act, busy, onClose }: {
  plan: Plan;
  orders: Order[];
  act: ActFn;
  busy: boolean;
  onClose: () => void;
}): React.JSX.Element {
  const [failed, setFailed] = useState(false);
  const assigned = new Set(plan.routes.flatMap((r) => r.order_ids));
  const deferred = new Set(plan.deferred.map((d) => d.order_id));
  const depots = [...new Set(orders.map((o) => o.depot))].sort();
  return (
    <Modal title="Review publication" onClose={onClose}>
      <p className="text-copy text-muted">{plan.day} · Draft {plan.revision}</p>
      <h3 className="mt-5 text-lg font-semibold">All depots</h3>
      <p className="text-copy text-muted">Publication includes the entire day's plan, regardless of the depot filter.</p>
      <table className="w-full text-left text-copy">
        <caption className="sr-only">Orders included in this publication</caption>
        <thead className="border-b border-line text-muted">
          <tr><th className="py-3">Depot</th><th>Assigned</th><th>Deferred</th></tr>
        </thead>
        <tbody>{depots.map((depot) => (
          <tr key={depot} className="border-b border-line">
            <th className="py-3 font-medium">{depot}</th>
            <td>{orders.filter((o) => o.depot === depot && assigned.has(o.id)).length}</td>
            <td>{orders.filter((o) => o.depot === depot && deferred.has(o.id)).length}</td>
          </tr>
        ))}</tbody>
      </table>
      <p className="mt-4 text-copy">{assigned.size} orders across {plan.routes.length} trips will be released for loading. {plan.routes.reduce((sum, r) => sum + r.fuel, 0).toFixed(1)} L planned fuel, including return travel.</p>
      {deferred.size > 0 && <p className="rounded-xl bg-canvas p-3 text-copy">{deferred.size} deferred orders will move to the next open operating run. This is queue eligibility, not a promised arrival.</p>}
      <p className="text-copy text-muted">Published plans are locked. The server checks the draft and constraints again before saving.</p>
      {failed && <p role="alert" className="rounded-xl bg-red-50 p-3 text-copy text-red-800">Publication was not completed. Return to the draft to read the error, refresh the workspace and review again.</p>}
      <div className="mt-5 flex flex-wrap justify-end gap-2">
        <Btn disabled={busy} onClick={onClose}>Back to draft</Btn>
        <Btn variant="primary" disabled={busy || failed} onClick={async () => {
          if (await act("publish", { day: plan.day, revision: plan.revision })) onClose();
          else setFailed(true);
        }}>Confirm publication</Btn>
      </div>
    </Modal>
  );
}
