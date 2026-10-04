"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import type { GenerationJobView } from "@shared/domain/types";

// Planning v2 (R-PLN-41): Generate queues a job and answers at once; the plan
// is written by a worker. This follows the job until it is done or failed, and
// picks up a job already running when the screen opens, so a reload or a
// second dispatcher sees the same progress rather than starting another.

const POLL_MS = 1000;
/** Long enough for the slowest day the engine allows; past it the screen says so rather than waits forever. */
const GIVE_UP_MS = 5 * 60_000;
const q = encodeURIComponent;

export type Following = { job: GenerationJobView; elapsed: number } | null;

export function useGeneration(depot: string, date: string) {
  const [following, setFollowing] = useState<Following>(null);
  const live = useRef(true);
  useEffect(() => () => void (live.current = false), []);

  const follow = useCallback(
    async (first: GenerationJobView): Promise<GenerationJobView> => {
      const started = Date.now();
      let job = first;
      while (job.status === "QUEUED" || job.status === "RUNNING") {
        if (!live.current) return job;
        setFollowing({ job, elapsed: Math.floor((Date.now() - started) / 1000) });
        if (Date.now() - started > GIVE_UP_MS) {
          throw new Error("Planning is taking longer than expected. It carries on in the background; refresh to see the draft when it is ready.");
        }
        await new Promise((resolve) => setTimeout(resolve, POLL_MS));
        job = await request<GenerationJobView>(`/api/plans/jobs/${q(job.jobId)}?depot=${q(depot)}`);
      }
      setFollowing(null);
      return job;
    },
    [depot],
  );

  // A job already queued or running for this day: follow it instead of offering Generate again.
  const [resumed, setResumed] = useState<GenerationJobView | null>(null);
  useEffect(() => {
    let cancelled = false;
    setResumed(null);
    request<GenerationJobView>(`/api/plans/jobs?depot=${q(depot)}&date=${q(date)}`)
      .then((job) => !cancelled && (job.status === "QUEUED" || job.status === "RUNNING") && setResumed(job))
      .catch((e: unknown) => {
        // 404: there has been no generation for the day, which is the usual case.
        if (!(e instanceof ApiError && e.status === 404)) return;
      });
    return () => void (cancelled = true);
  }, [depot, date]);

  return { following, follow, resumed, clearResumed: () => setResumed(null) };
}

/** What the button says while a job runs. */
export function progressLabel(following: Following): string | null {
  if (!following) return null;
  return following.job.status === "QUEUED" ? "Queued…" : `Planning… ${following.elapsed} s`;
}

/**
 * The same for every depot in view: one job per depot and day. `following`
 * is the first job still running; `resumed` lists jobs already running when
 * the screen opened, each with its depot.
 */
export function useGenerations(depots: string[], date: string) {
  const [following, setFollowing] = useState<Record<string, Following>>({});
  const live = useRef(true);
  useEffect(() => () => void (live.current = false), []);

  const follow = useCallback(async (depot: string, first: GenerationJobView): Promise<GenerationJobView> => {
    const started = Date.now();
    let job = first;
    while (job.status === "QUEUED" || job.status === "RUNNING") {
      if (!live.current) return job;
      setFollowing((all) => ({ ...all, [depot]: { job, elapsed: Math.floor((Date.now() - started) / 1000) } }));
      if (Date.now() - started > GIVE_UP_MS) {
        throw new Error("Planning is taking longer than expected. It carries on in the background; refresh to see the draft when it is ready.");
      }
      await new Promise((resolve) => setTimeout(resolve, POLL_MS));
      job = await request<GenerationJobView>(`/api/plans/jobs/${q(job.jobId)}?depot=${q(depot)}`);
    }
    setFollowing((all) => ({ ...all, [depot]: null }));
    return job;
  }, []);

  const key = depots.join(",");
  const [resumed, setResumed] = useState<Array<{ depot: string; job: GenerationJobView }>>([]);
  useEffect(() => {
    let cancelled = false;
    setResumed([]);
    void Promise.all(
      key.split(",").filter(Boolean).map((depot) =>
        request<GenerationJobView>(`/api/plans/jobs?depot=${q(depot)}&date=${q(date)}`)
          .then((job) => (job.status === "QUEUED" || job.status === "RUNNING" ? { depot, job } : null))
          .catch((e: unknown) => {
            // 404: no generation for the day, the usual case.
            if (!(e instanceof ApiError && e.status === 404)) return null;
            return null;
          }),
      ),
    ).then((jobs) => !cancelled && setResumed(jobs.filter((j): j is { depot: string; job: GenerationJobView } => j !== null)));
    return () => void (cancelled = true);
  }, [key, date]);

  const running = Object.values(following).find((f) => f !== null) ?? null;
  return { following: running, follow, resumed, clearResumed: () => setResumed([]) };
}
