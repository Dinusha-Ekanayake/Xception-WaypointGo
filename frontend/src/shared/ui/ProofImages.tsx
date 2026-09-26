"use client";

import { useEffect, useState } from "react";
import type { Order } from "@shared/domain/types";
import { api, read, write } from "@shared/offline/storage";

export function ProofImages({ accountId, order }: { accountId: string; order: Order }) {
  const photoId = order.proof?.photo_id;
  const signatureId = order.proof?.signature_id;
  const identity = `${accountId}:${order.id}:${photoId || ""}:${signatureId || ""}`;
  const [saved, setSaved] = useState<{ identity: string; photo?: string; signature?: string; message: string }>();

  useEffect(() => {
    let active = true;
    let loading = false;
    async function load() {
      if (loading) return;
      loading = true;
      const images: { photo?: string; signature?: string } = {};
      let unavailable = false;
      let durable = true;
      try {
        for (const [kind, imageId] of [["photo", photoId], ["signature", signatureId]] as const) {
          if (!imageId) continue;
          const key = `proof:${accountId}:${imageId}`;
          let data: string | undefined;
          try { data = await read<string>(key); } catch { durable = false; }
          if (!data && navigator.onLine) {
            try {
              data = (await api<{ data: string }>(`proof?order_id=${encodeURIComponent(order.id)}&image_id=${imageId}`)).data;
              if (!active || localStorage.getItem("waypoint-user") !== accountId) return;
              try { await write(key, data); } catch { durable = false; }
            } catch { unavailable = true; }
          }
          if (data) images[kind] = data;
          else unavailable = true;
        }
        if (active) setSaved({ identity, ...images, message: unavailable ? "Proof image unavailable on this device. Connect and reopen the receipt to download it." : durable ? "Proof saved on this device." : "Proof shown online; could not save it on this device." });
      } finally { loading = false; }
    }
    void load();
    window.addEventListener("online", load);
    return () => { active = false; window.removeEventListener("online", load); };
  }, [identity, accountId, order.id, photoId, signatureId]);

  const current = saved?.identity === identity ? saved : undefined;
  const photo = order.proof?.photo || current?.photo;
  const signature = order.proof?.signature || current?.signature;
  if (!photo && !signature && !photoId && !signatureId) return null;
  return <div>
    {photo && <img className="mt-2 w-full rounded-xl border border-[var(--ui-line)] object-cover" src={photo} alt="Delivery proof" />}
    {signature && <img className="mt-2 w-full rounded-xl border border-[var(--ui-line)] bg-[#edf3f1] object-cover" src={signature} alt="Receiver signature" />}
    {(photoId || signatureId) && <p className="mt-2 text-caption text-muted" role="status">{current?.message || "Opening proof images…"}</p>}
  </div>;
}
