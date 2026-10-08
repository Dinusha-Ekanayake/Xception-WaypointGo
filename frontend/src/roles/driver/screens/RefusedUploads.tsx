"use client";

import type { StoredUpload } from "@shared/offline";
import type { Stop } from "../data/run.ts";
import { Panel } from "../ui.tsx";
import { useT } from "../i18n.tsx";

/**
 * Proof files the server refused. They stay on the phone until the driver
 * drops them: nothing is discarded or resent automatically, and a refused file
 * would otherwise keep sign-out blocked with no way to see why.
 */
export default function RefusedUploads({
  uploads,
  stops,
  onDiscard,
}: {
  uploads: StoredUpload[];
  stops: Stop[];
  onDiscard: (id: string) => void;
}): React.JSX.Element | null {
  const t = useT();
  const refused = uploads.filter((upload) => upload.needsReview);
  if (refused.length === 0) return null;
  return (
    <Panel label={t("Proof files Waypoint refused")}>
      <h2 className="text-[19px] font-medium text-go-ink">
        {refused.length === 1 ? t("A proof file was refused") : t("{n} proof files were refused", { n: refused.length })}
      </h2>
      <p className="mt-1 text-[15px] text-go-muted">{t("The delivery itself is recorded. Open the stop to add the proof again, then remove the refused file here.")}</p>
      <ul className="mt-3 flex flex-col">
        {refused.map((upload) => {
          const stop = stops.find((candidate) => candidate.deliveryId === upload.subject);
          return (
            <li key={upload.id} className="flex min-h-14 items-center gap-3 border-t border-go-rule py-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[16px] font-medium text-go-ink">
                  {upload.path.includes("kind=signature") ? t("Signature") : t("Photo")}
                  {stop ? t(" · stop {n} {name}", { n: String(stop.sequence).padStart(2, "0"), name: stop.outletId }) : ""}
                </span>
                <span className="block text-[13px] text-go-muted">{upload.lastError ?? "Refused by Waypoint"}</span>
              </span>
              <button type="button" onClick={() => onDiscard(upload.id)} className="min-h-12 shrink-0 px-3 text-[15px] font-medium text-go-danger-strong">
                {t("Remove")}
              </button>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
