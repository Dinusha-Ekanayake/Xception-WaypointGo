"use client";

import { useCallback, useEffect, useState } from "react";
import type { FuelPassStatusView } from "@shared/domain/execution";
import { friendlyError } from "@shared/api/problem";
import { dayLabel } from "@shared/wording";
import { Modal, field, primary, secondary } from "./components";
import { FUEL_PASS_TYPES, fetchFuelPasses, fileProblem, revokeFuelPass, sizeText, statusFor, uploadFuelPass } from "../data/fuelPasses";

// Roadmap FF-06b: the administrator adds, replaces or withdraws a vehicle's
// National Fuel Pass QR. The driver of the vehicle gets it on the phone with the
// day's run and can show it at a pump with no signal. The image is not shown
// back here: it is a credential, and only the driver needs to see it.

export function FuelPassDialog({ vehicleId, onClose }: { vehicleId: string; onClose: () => void }): React.JSX.Element {
  const [status, setStatus] = useState<FuelPassStatusView | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      setStatus(statusFor(await fetchFuelPasses(signal), vehicleId));
    } catch (e) {
      if (!signal?.aborted) setError(friendlyError(e));
    }
  }, [vehicleId]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const problem = file ? fileProblem(file) : null;

  const upload = async () => {
    if (!status || !file || problem) return;
    setBusy(true);
    setError(null);
    try {
      await uploadFuelPass(vehicleId, file, status.version);
      setDone(status.passId ? "Fuel Pass replaced. The driver gets the new one with the next run." : "Fuel Pass added. The driver gets it with the next run.");
      setFile(null);
      await load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async () => {
    if (!status?.passId || reason.trim().length < 3) return;
    setBusy(true);
    setError(null);
    try {
      await revokeFuelPass(status, reason.trim());
      setDone("Fuel Pass withdrawn. It is removed from the driver's phone the next time it has signal.");
      setWithdrawing(false);
      setReason("");
      await load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={`Fuel Pass · ${vehicleId}`} onClose={onClose}>
      <div className="flex flex-col gap-4 text-sm">
        <p className="text-go-secondary">
          Upload the vehicle&apos;s official National Fuel Pass QR. The government sets the fuel quota; this only puts the QR on the driver&apos;s phone so no paper copy is needed.
        </p>

        <div className="rounded-2xl bg-go-surface px-4 py-3">
          {status === null ? (
            <p className="text-go-secondary">{error ? "Could not read the Fuel Pass." : "Reading…"}</p>
          ) : status.passId ? (
            <p>
              <span className="font-semibold">Fuel Pass added</span>
              <span className="text-go-secondary">
                {status.uploadedAt ? ` · ${dayLabel(status.uploadedAt.slice(0, 10))}` : ""}
                {status.sizeBytes !== null ? ` · ${sizeText(status.sizeBytes)}` : ""}
              </span>
            </p>
          ) : (
            <p className="font-semibold">No Fuel Pass added. The driver sees &quot;Ask the depot&quot;.</p>
          )}
        </div>

        {done && <p role="status" className="rounded-xl bg-go-subtle p-3 text-go-ink">{done}</p>}
        {error && <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-red-700">{error}</p>}

        <label className="flex flex-col gap-1.5 font-medium">
          {status?.passId ? "Replace with a new image" : "QR image"}
          <input
            type="file"
            accept={FUEL_PASS_TYPES.join(",")}
            className={field}
            onChange={(e) => {
              setDone(null);
              setFile(e.target.files?.[0] ?? null);
            }}
          />
        </label>
        {problem && <p className="text-go-danger">{problem}</p>}

        <div className="flex flex-wrap gap-2">
          <button type="button" className={primary} disabled={!status || !file || !!problem || busy} onClick={() => void upload()}>
            {busy && !withdrawing ? "Uploading…" : status?.passId ? "Replace Fuel Pass" : "Add Fuel Pass"}
          </button>
          {status?.passId && !withdrawing && (
            <button type="button" className={secondary} disabled={busy} onClick={() => setWithdrawing(true)}>
              Withdraw
            </button>
          )}
        </div>

        {withdrawing && (
          <div className="flex flex-col gap-2 rounded-2xl border border-go-rule p-4">
            <label className="flex flex-col gap-1.5 font-medium">
              Why is it withdrawn?
              <input className={field} value={reason} maxLength={300} placeholder="For example: QR reissued, vehicle sold" onChange={(e) => setReason(e.target.value)} />
            </label>
            <div className="flex gap-2">
              <button type="button" className={primary} disabled={busy || reason.trim().length < 3} onClick={() => void withdraw()}>
                {busy ? "Withdrawing…" : "Withdraw Fuel Pass"}
              </button>
              <button type="button" className={secondary} onClick={() => setWithdrawing(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
