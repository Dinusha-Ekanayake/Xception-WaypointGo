"use client";

import { cx } from "@shared/ui";

export type NoTripPlanScreenProps = {
  onBack: () => void;
  onBackToHome: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  vehicleId?: string;
  depotName?: string;
};

export default function NoTripPlanScreen({
  onBack,
  onBackToHome,
  isNight = false,
  onToggleTheme,
  vehicleId = "VEH043",
  depotName = "Kandy depot",
}: NoTripPlanScreenProps): React.JSX.Element {
  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden justify-between",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
      )}
    >
      {/* =================================================================== */}
      {/* TOP HEADER: Back + Synced Pill + Theme toggle                      */}
      {/* =================================================================== */}
      <div className="pt-[20px] px-[25px] sm:px-[33px] flex items-center justify-between shrink-0 z-20">
        {/* Back button */}
        <button
          type="button"
          onClick={onBack}
          className={cx(
            "flex items-center gap-[9px] text-[20px] font-medium leading-[25px] h-[30px] transition-opacity active:opacity-70",
            isNight ? "text-white" : "text-black"
          )}
          aria-label="Go back"
        >
          <svg width="9" height="14" viewBox="0 0 9 14" fill="none">
            <path
              d="M7.75 1L1.25 7L7.75 13"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <span>Back</span>
        </button>

        {/* Right header controls */}
        <div className="flex items-center gap-4">
          {/* Sync Pill (w:125px, h:43px, radius:25px) */}
          <div
            className={cx(
              "w-[125px] h-[43px] rounded-[25px] flex items-center justify-center transition-colors",
              isNight
                ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                : "bg-white text-black shadow-[0px_5px_20px_7px_rgba(0,0,0,0.02)]"
            )}
          >
            <span className="text-[16px] font-medium leading-[20px] tracking-tight">
              Synced 05:31
            </span>
          </div>

          {/* Theme toggle */}
          <button
            type="button"
            onClick={onToggleTheme}
            className={cx(
              "size-[43px] rounded-[25px] flex items-center justify-center transition-all active:scale-95",
              isNight
                ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                : "bg-white text-black shadow-[0px_5px_20px_2px_rgba(0,0,0,0.09)]"
            )}
            aria-label="Toggle theme"
          >
            {isNight ? (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
              </svg>
            ) : (
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="5" />
                <line x1="12" y1="1" x2="12" y2="3" />
                <line x1="12" y1="21" x2="12" y2="23" />
                <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                <line x1="1" y1="12" x2="3" y2="12" />
                <line x1="21" y1="12" x2="23" y2="12" />
                <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
              </svg>
            )}
          </button>
        </div>
      </div>

      {/* =================================================================== */}
      {/* CONTENT AREA                                                        */}
      {/* =================================================================== */}
      <div className="flex-1 flex flex-col justify-start px-[39px] pt-[24px] pb-6 overflow-y-auto no-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {/* Calendar Off Icon Badge (72px x 72px, radius: 999px) */}
        {/* Figma: left:40px, top:146px, light bg: #B7F2ED, dark bg: #263A2E */}
        <div
          className={cx(
            "w-[72px] h-[72px] rounded-full flex items-center justify-center shrink-0 mb-[18px] transition-colors",
            isNight ? "bg-[#263A2E]" : "bg-[#B7F2ED]"
          )}
        >
          {/* fa/calendar-xmark */}
          <svg
            width="28"
            height="32"
            viewBox="0 0 24 28"
            fill="none"
            className="shrink-0"
          >
            {/* Calendar header binding pins */}
            <path
              d="M7 2V6M17 2V6"
              stroke={isNight ? "#1DC96B" : "#0F766E"}
              strokeWidth="2.5"
              strokeLinecap="round"
            />
            {/* Calendar body */}
            <rect
              x="2.5"
              y="4.5"
              width="19"
              height="20"
              rx="4"
              stroke={isNight ? "#1DC96B" : "#0F766E"}
              strokeWidth="2.3"
            />
            {/* Header line */}
            <line
              x1="2.5"
              y1="10"
              x2="21.5"
              y2="10"
              stroke={isNight ? "#1DC96B" : "#0F766E"}
              strokeWidth="2"
            />
            {/* Calendar X mark inside */}
            <path
              d="M8.5 14.5L15.5 21.5M15.5 14.5L8.5 21.5"
              stroke={isNight ? "#1DC96B" : "#0F766E"}
              strokeWidth="2.5"
              strokeLinecap="round"
            />
          </svg>
        </div>

        {/* Depot & Vehicle label */}
        <span
          className={cx(
            "text-[15px] font-light leading-[19px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          {vehicleId} • {depotName}
        </span>

        {/* Title */}
        <h1
          className={cx(
            "text-[40px] font-medium leading-[50px] tracking-tight mt-[3px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          No run planned
        </h1>

        {/* Subtitle */}
        <p
          className={cx(
            "text-[15px] font-light leading-[19px] mt-[4px]",
            isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
          )}
        >
          Dispatch hasn’t published another run for you today.
        </p>

        {/* Plan status card */}
        {/* Figma: height:155px, radius:25px, padding: 6px 20px, top: 370px */}
        <div
          className={cx(
            "w-full rounded-[25px] mt-[24px] px-[20px] py-[8px] flex flex-col justify-between transition-colors",
            isNight
              ? "bg-[#292929] shadow-none"
              : "bg-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
          )}
        >
          {/* Row 1 · Last run */}
          <div className="flex items-center justify-between py-[12px] border-b border-black/5 dark:border-white/10">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Last run
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              3 of 3 delivered • 06:02
            </span>
          </div>

          {/* Row 2 · Next run */}
          <div className="flex items-center justify-between py-[12px] border-b border-black/5 dark:border-white/10">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Next run
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              Not published yet
            </span>
          </div>

          {/* Row 3 · Vehicle */}
          <div className="flex items-center justify-between py-[12px]">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Vehicle
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              {vehicleId} • returned
            </span>
          </div>
        </div>

        {/* Next step notification prompt */}
        {/* Figma: left:40px, top:547px, font-size:15px, font-weight:300 */}
        <p
          className={cx(
            "text-[15px] font-light leading-[19px] mt-[22px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          You’ll get a notification as soon as a new run is assigned.
        </p>
      </div>

      {/* =================================================================== */}
      {/* BOTTOM ACTION BUTTON: "Back to home"                                */}
      {/* =================================================================== */}
      {/* Figma: left:49px, right:49px, bottom:48px, height:64px, radius:22px */}
      <div className="px-[49px] pb-[48px] shrink-0">
        <button
          type="button"
          onClick={onBackToHome}
          className={cx(
            "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-lg",
            isNight
              ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
              : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
          )}
        >
          Back to home
        </button>
      </div>
    </div>
  );
}
