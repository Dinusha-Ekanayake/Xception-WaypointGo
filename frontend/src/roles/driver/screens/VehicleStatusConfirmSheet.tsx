"use client";

import { useEffect, useState } from "react";
import type { ReportedVehicleStatus } from "@shared/domain/types";
import { cx } from "@shared/ui";
import { useT } from "../i18n.tsx";

// The "Available" switch on Home reports the vehicle to the dispatcher, who
// stops planning it while it is in the workshop. One tap by accident made that
// event, so the switch asks first. The sheet is the sign-out sheet's look
// (Figma "Driver: Home"), with the same scrim, card and two buttons.

export default function VehicleStatusConfirmSheet({
  vehicleId,
  next,
  isNight = false,
  onConfirm,
  onClose,
}: {
  vehicleId: string;
  /** What the switch would report: at the workshop, or available again. */
  next: ReportedVehicleStatus;
  isNight?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const t = useT();
  const [visible, setVisible] = useState(false);
  const toWorkshop = next !== "available";

  useEffect(() => {
    const raf = requestAnimationFrame(() => requestAnimationFrame(() => setVisible(true)));
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [onClose]);

  return (
    <div role="dialog" aria-modal="true" aria-label={t("Vehicle status")} className="absolute inset-0 z-50 flex flex-col justify-end overflow-hidden">
      <div
        className={cx("absolute inset-0 bg-black/35 backdrop-blur-[6px] transition-opacity duration-[250ms] ease-out", visible ? "opacity-100" : "opacity-0")}
        onClick={onClose}
      />
      <div
        className={cx(
          "relative z-10 flex w-full flex-col items-center gap-3 rounded-t-[40px] px-5 pt-5 pb-9 font-go shadow-2xl select-none",
          "transition-transform duration-[250ms] ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          visible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black",
        )}
      >
        <div className={cx("flex w-full flex-col gap-1.5 rounded-[22px] p-[18px]", isNight ? "bg-[#121212]" : "bg-[#E7F3F2]")}>
          <div className="flex items-center gap-1.5">
            <span className={cx("size-2 rounded-full", toWorkshop ? "bg-[#c08a3e]" : isNight ? "bg-[#00BF6A]" : "bg-[#0E766D]")} />
            <span className={cx("text-[13px] leading-[16px] font-medium", toWorkshop ? "text-[#c08a3e]" : isNight ? "text-[#00BF6A]" : "text-[#0E766D]")}>
              {vehicleId}
            </span>
          </div>
          <h3 className={cx("mt-0.5 text-[24px] leading-[30px] font-medium tracking-tight", isNight ? "text-white" : "text-black")}>
            {toWorkshop ? t("Mark the vehicle in the workshop?") : t("Mark the vehicle available?")}
          </h3>
          <p className={cx("text-[15px] leading-[21px] font-normal", isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]")}>
            {toWorkshop
              ? t("The dispatcher sees it at once and stops planning trips on it.")
              : t("The dispatcher sees it at once and can plan trips on it again.")}
          </p>
        </div>
        <div className="mt-[20px] flex w-full flex-col items-center gap-3">
          <button
            type="button"
            onClick={onConfirm}
            className={cx(
              "flex h-[64px] w-full max-w-[295px] items-center justify-center rounded-[22px] text-[20px] leading-[25px] font-medium shadow-sm transition-all active:scale-[0.99]",
              isNight ? "bg-[#00BF6A] text-black" : "bg-[#031B08] text-white",
            )}
          >
            {toWorkshop ? t("Yes, in the workshop") : t("Yes, available")}
          </button>
          <button
            type="button"
            onClick={onClose}
            className={cx(
              "flex h-[64px] w-full max-w-[295px] items-center justify-center rounded-[22px] border text-[20px] leading-[25px] font-medium transition-all active:scale-[0.99]",
              isNight ? "border-[#3A3A3A] text-white" : "border-[#031B08] text-black",
            )}
          >
            {t("Cancel")}
          </button>
        </div>
      </div>
    </div>
  );
}
