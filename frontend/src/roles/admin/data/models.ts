import { request } from "../../../shared/api/client.ts";
import { newCommand, send } from "../../../shared/api/commands.ts";
import type { BacktestView, ModelBacktestView, ModelKind, ModelVersionView } from "../../../shared/domain/intelligence.ts";

// The model registry (roadmap MF-01): which trained model answers each kind of
// prediction. Reads GET /api/ml/models; activating and retiring go through the
// command bus with the version's rowVersion, so two administrators cannot
// overwrite each other.

export function fetchModels(signal?: AbortSignal) {
  return request<ModelVersionView[]>("/api/ml/models", { signal });
}

/** Roadmap MF-04: each model's stored predictions over the last finished weeks beside what happened. */
export function fetchBacktest(weeks = 8, signal?: AbortSignal) {
  return request<BacktestView>(`/api/ml/models/backtest?weeks=${weeks}`, { signal });
}

export const BUILT_IN = "deterministic";

const VERDICT: Record<ModelBacktestView["verdict"], string> = {
  BETTER: "better than the built-in estimate",
  WORSE: "worse than the built-in estimate",
  MIXED: "neither clearly better nor worse than the built-in estimate",
  NOT_ENOUGH: "not enough labelled stops to judge",
  BASELINE: "the figure every model is judged against",
};

/**
 * One line for a model's backtest, in words: how many stops it answered, how
 * many had a trustworthy arrival, its late error beside the estimate's, and
 * the verdict. A model that answered nothing says so rather than showing zeros.
 */
export function backtestLine(view: BacktestView, label: string): string {
  const weeks = Math.round((Date.parse(view.to) - Date.parse(view.from)) / (7 * 86_400_000));
  const m = view.models.find((x) => x.modelLabel === label);
  if (!m) return `Last ${weeks} weeks · no predictions yet, so nothing to judge`;
  const base = view.models.find((x) => x.modelLabel === BUILT_IN);
  const pct = Math.round(Number(m.coverage) * 100);
  const parts = [`Last ${weeks} weeks`, `${m.labelled} of ${m.stops} stops labelled (${pct}%)`];
  if (m.brier !== null) {
    const own = Number(m.brier).toFixed(3);
    parts.push(base && base !== m && base.brier !== null ? `late error ${own} (estimate ${Number(base.brier).toFixed(3)})` : `late error ${own}`);
  }
  const verdict = m.verdict === "NOT_ENOUGH" ? `${VERDICT.NOT_ENOUGH} (needs ${view.minLabels})` : VERDICT[m.verdict];
  parts.push(verdict.charAt(0).toUpperCase() + verdict.slice(1));
  return parts.join(" · ");
}

export function activateModel(model: Pick<ModelVersionView, "name" | "version" | "rowVersion">) {
  return send<{ name: string; version: string; status: string; rowVersion: number }>(
    newCommand("ml:ActivateModel", { name: model.name, version: model.version }, model.rowVersion),
  );
}

export function retireModel(model: Pick<ModelVersionView, "name" | "version" | "rowVersion">, reason: string) {
  return send<{ name: string; version: string; status: string; rowVersion: number }>(
    newCommand("ml:RetireModel", { name: model.name, version: model.version, reason }, model.rowVersion),
  );
}

export const KIND_LABEL: Record<ModelKind, { title: string; what: string }> = {
  delivery_risk: { title: "Late risk and unloading time", what: "Predicts how likely each stop is to arrive after its window, and how long unloading takes." },
  demand_forecast: { title: "Demand forecast", what: "Predicts weekly order volume per depot and brand, for planning vehicles and refrigerated capacity." },
};

/** Validation figures in words. Unknown names are shown with their underscores as spaces, never as raw codes. */
const METRIC_LABEL: Record<string, string> = {
  late_auc_same_season: "Late flags, ranking (same season)",
  late_logloss_same_season: "Late flags, error (same season)",
  late_logloss_last6w: "Late flags, error (last 6 weeks)",
  service_mae_min_same_season: "Unloading time, average miss in minutes (same season)",
  service_mae_min_last6w: "Unloading time, average miss in minutes (last 6 weeks)",
  fallback_late_logloss_same_season: "Without road data, late error (same season)",
  fallback_late_logloss_last6w: "Without road data, late error (last 6 weeks)",
  total_wape: "Total volume, typical miss",
  chilled_wape: "Chilled volume, typical miss",
};

export function metricLabel(name: string): string {
  return METRIC_LABEL[name] ?? name.replace(/_/g, " ");
}

/** A share such as WAPE reads as a percentage; everything else as a short number. */
export function metricValue(name: string, value: string | number): string {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  return name.endsWith("wape") ? `${(n * 100).toFixed(1)}%` : n.toFixed(n < 10 ? 3 : 1);
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** A training period spans years, so it reads as month and year: "Jan 2024 to Feb 2026". */
export function trainingPeriod(from: string | null, to: string | null): string {
  const month = (iso: string) => `${MONTHS[Number(iso.slice(5, 7)) - 1]} ${iso.slice(0, 4)}`;
  return from && to ? `Trained on ${month(from)} to ${month(to)}` : "Training period not recorded";
}

/** R-ML-27: where the language models and Jev failed over the last days. Administrators only. */
export function fetchAiHealth(days: number, signal?: AbortSignal) {
  return request<import("@shared/domain/intelligence").AiHealthView>(`/api/ml/ai-health?days=${days}`, { signal });
}
