"use client";

import { useEffect, useRef, useState } from "react";
import { z } from "zod";
import { read, remove, write } from "./storage";

const DraftSchema = z.object({
  outcome: z.enum(["delivered", "partial", "failed"]),
  receiver: z.string(),
  count: z.string(),
  note: z.string(),
  signature: z.string(),
  photo: z.string(),
});
export type DeliveryDraft = z.infer<typeof DraftSchema>;

export function useDeliveryDraft(key: string, initial: DeliveryDraft) {
  const [draft, setDraft] = useState(initial);
  const latest = useRef(initial);
  const [ready, setReady] = useState(false);
  const [status, setStatus] = useState("Opening saved draft…");
  const writes = useRef<Promise<void>>(Promise.resolve());
  const revision = useRef(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    void read(key).then((value) => {
      if (!active) return;
      const saved = DraftSchema.safeParse(value);
      if (saved.success) { latest.current = saved.data; setDraft(saved.data); }
      setStatus(saved.success ? "Saved draft restored. Review before submitting." : "Drafts save on this device as you work.");
      setReady(true);
    }).catch(() => {
      if (!active) return;
      setStatus("Could not open device storage. Keep this form open until submitted.");
      setReady(true);
    });
    return () => { active = false; mounted.current = false; };
  }, [key]);

  function update(patch: Partial<DeliveryDraft>): void {
    const next = { ...latest.current, ...patch };
    latest.current = next;
    setDraft(next);
    const currentRevision = ++revision.current;
    setStatus("Saving draft…");
    writes.current = writes.current.catch(() => {}).then(() => write(key, next));
    void writes.current.then(() => {
      if (mounted.current && revision.current === currentRevision)
        setStatus("Draft saved on this device · not submitted");
    }).catch(() => {
      if (mounted.current && revision.current === currentRevision)
        setStatus("Draft could not be saved. Keep this form open until submitted.");
    });
  }

  async function clear(): Promise<void> {
    await writes.current.catch(() => {});
    await remove(key);
  }

  return { draft, update, ready, status, clear };
}
