"use client";

import { useEffect, useState } from "react";
import type { ModelKind, ModelVersionView } from "@shared/domain/intelligence";
import { friendlyError } from "@shared/api/problem";
import { dayLabel } from "@shared/wording";
import { Badge, card, field, primary, secondary } from "./components";
import { activateModel, fetchModels, KIND_LABEL, metricLabel, metricValue, retireModel, trainingPeriod } from "../data/models";

// Roadmap MF-01: the model registry. One model per kind answers at a time
// (R-ML-03); a retired model can never come back. Until a model trained on our
// own deliveries passes its backtest, production answers with the built-in
// estimate and says so on every screen that shows a prediction (R-ML-04).

const KINDS: ModelKind[] = ["delivery_risk", "demand_forecast"];
const STATUS: Record<ModelVersionView["status"], { text: string; tone: "green" | "neutral" | "red" }> = {
  ACTIVE: { text: "In use", tone: "green" },
  REGISTERED: { text: "Ready", tone: "neutral" },
  RETIRED: { text: "Retired", tone: "red" },
};

export function ModelsScreen() {
  const [models, setModels] = useState<ModelVersionView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [retiring, setRetiring] = useState<ModelVersionView | null>(null);
  const [reason, setReason] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    fetchModels(controller.signal)
      .then((items) => {
        setModels(items);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(friendlyError(e));
      });
    return () => controller.abort();
  }, [reload]);

  const act = async (key: string, run: () => Promise<unknown>) => {
    setBusy(key);
    setError(null);
    try {
      await run();
      setRetiring(null);
      setReason("");
      setReload((n) => n + 1);
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="flex flex-col gap-5" aria-label="Models">
      <p className="text-sm text-go-muted">Predictions are advice only: they never change a plan.</p>
      {error && (
        <div role="alert" className="rounded-2xl border border-go-warning-text bg-white p-4 text-sm">
          {error}
        </div>
      )}
      {models === null && !error && <p className="text-sm text-go-muted">Loading models…</p>}
      {models &&
        KINDS.map((kind) => {
          const versions = models.filter((m) => m.kind === kind);
          const active = versions.find((m) => m.status === "ACTIVE");
          return (
            <div key={kind} className={`${card} flex flex-col gap-4 p-5`}>
              <div>
                <h3 className="text-lg font-semibold">{KIND_LABEL[kind].title}</h3>
                <p className="text-sm text-go-muted">{KIND_LABEL[kind].what}</p>
                <p className="mt-1 text-sm">
                  {active ? `In use: ${active.name} ${active.version}` : "No model in use: the built-in estimate answers, and screens say so."}
                </p>
              </div>
              {versions.length === 0 && <p className="text-sm text-go-muted">No versions registered yet.</p>}
              <ul className="flex flex-col gap-3">
                {versions.map((m) => {
                  const key = `${m.name}@${m.version}`;
                  return (
                    <li key={key} className="rounded-go-card-s border border-go-rule p-4" aria-label={`${m.name} ${m.version}`}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{m.name}</span>
                        <span className="text-sm text-go-muted">{m.version}</span>
                        <Badge tone={STATUS[m.status].tone}>{STATUS[m.status].text}</Badge>
                      </div>
                      <p className="mt-1 text-sm text-go-muted">
                        {trainingPeriod(m.trainedFrom, m.trainedTo)}
                        {m.activatedAt ? ` · in use since ${dayLabel(m.activatedAt.slice(0, 10))}` : ""}
                        {m.retiredReason ? ` · retired: ${m.retiredReason}` : ""}
                      </p>
                      {Object.keys(m.metrics).length > 0 && (
                        <dl className="mt-3 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                          {Object.entries(m.metrics).map(([name, value]) => (
                            <div key={name} className="flex justify-between gap-3">
                              <dt className="text-go-muted">{metricLabel(name)}</dt>
                              <dd className="tabular-nums">{metricValue(name, value)}</dd>
                            </div>
                          ))}
                        </dl>
                      )}
                      {m.status !== "RETIRED" && (
                        <div className="mt-3 flex flex-wrap gap-2">
                          {m.status === "REGISTERED" && (
                            <button type="button" className={primary} disabled={busy !== null} onClick={() => act(key, () => activateModel(m))}>
                              {busy === key ? "Saving…" : "Use this model"}
                            </button>
                          )}
                          <button type="button" className={secondary} disabled={busy !== null} onClick={() => setRetiring(m)}>
                            Retire
                          </button>
                        </div>
                      )}
                      {retiring && `${retiring.name}@${retiring.version}` === key && (
                        <form
                          className="mt-3 flex flex-col gap-2"
                          onSubmit={(e) => {
                            e.preventDefault();
                            void act(key, () => retireModel(m, reason.trim()));
                          }}
                        >
                          <label className="text-sm font-medium" htmlFor={`reason-${key}`}>
                            Why retire it? A retired model can never be used again.
                          </label>
                          <input id={`reason-${key}`} className={field} value={reason} onChange={(e) => setReason(e.target.value)} required minLength={3} />
                          <div className="flex gap-2">
                            <button type="submit" className={primary} disabled={busy !== null || reason.trim().length < 3}>
                              {busy === key ? "Saving…" : "Retire model"}
                            </button>
                            <button type="button" className={secondary} onClick={() => setRetiring(null)}>
                              Cancel
                            </button>
                          </div>
                        </form>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
    </section>
  );
}
