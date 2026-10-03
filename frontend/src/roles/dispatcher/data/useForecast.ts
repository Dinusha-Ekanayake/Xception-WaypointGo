import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { ForecastOverviewView } from "@shared/domain/types";
import { WEEKS } from "./forecast.ts";

const POLL_MS = 5 * 60_000;

/** One read per depot from /api/ml/forecast/overview (#16); the server answers 403 for a depot outside scope. */
export function useForecast(depots: string[], weeks = WEEKS): Resource<ForecastOverviewView[]> {
  const load =
    depots.length === 0
      ? null
      : (signal: AbortSignal) =>
          Promise.all(
            depots.map((depot) =>
              request<ForecastOverviewView>(
                `/api/ml/forecast/overview?depot=${encodeURIComponent(depot)}&weeks=${weeks}`,
                { signal },
              ),
            ),
          );
  return useResource(load, `forecast|${depots.join(",")}|${weeks}`, POLL_MS);
}
