"use client";

import { useEffect, useState } from "react";
import { lastPersistence, useInstall } from "../pwa/index.ts";

// "Install app" in each role's settings, beside "Connect AI assistant" (issue
// #201). Installed, the role opens full screen from its own icon and keeps
// working with no coverage. Gone once installed; on iPhone and iPad, where the
// browser has no prompt, it says how to add it instead.

const asIs = (text: string) => text;

/** `t` translates the row for a role that has its own dictionary (the loader); English otherwise. */
export function InstallApp({ className, t = asIs }: { className: string; t?: (text: string) => string }): React.JSX.Element | null {
  const { kind, install } = useInstall();
  const [steps, setSteps] = useState(false);
  const [mayClear, setMayClear] = useState(false);
  useEffect(() => setMayClear(lastPersistence() === false), []);

  if (kind === "installed" || kind === "none") {
    return mayClear ? <p className="text-[12px] text-go-muted">{t("This browser may clear work saved offline when the device runs low on space.")}</p> : null;
  }
  return (
    <div className="flex flex-col gap-2">
      <button type="button" className={className} aria-expanded={kind === "ios" ? steps : undefined} onClick={() => (kind === "prompt" ? void install() : setSteps((s) => !s))}>
        {t("Install app")}
      </button>
      {kind === "ios" && steps && (
        <p className="rounded-[14px] bg-go-canvas p-3 text-[13px] text-go-ink">
          {t("In Safari, tap Share, then Add to Home Screen. The app then opens from its own icon and keeps working with no signal.")}
        </p>
      )}
      {mayClear && <p className="text-[12px] text-go-muted">{t("Installing keeps work saved offline when the device runs low on space.")}</p>}
    </div>
  );
}
