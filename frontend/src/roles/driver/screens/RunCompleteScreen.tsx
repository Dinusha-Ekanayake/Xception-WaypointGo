"use client";

import { cx } from "@shared/ui";

export type RunCompleteScreenProps = {
  /** "Synced HH:MM" from the live run; the design's sample time when absent. */
  syncedLabel?: string;
  /** The run's outcome line; the design's sample when absent. */
  summary?: string;
  onBack: () => void;
  onBackToHome: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  vehicleId?: string;
  depotName?: string;
};

export default function RunCompleteScreen({
  syncedLabel = "Synced 05:31",
  summary = "All 7 stops delivered • finished 07:45",
  onBack,
  onBackToHome,
  isNight = false,
  onToggleTheme,
  vehicleId = "VEH043",
  depotName = "Kandy depot",
}: RunCompleteScreenProps): React.JSX.Element {
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
              {syncedLabel}
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
        {/* Success check badge (72px x 72px, radius: 81.8px) */}
        {/* Figma: left:40px, top:146px, shadow: 0px 16.36px 65.45px rgba(0,0,0,0.09) */}
        <div className="w-[72px] h-[72px] rounded-full bg-white shadow-[0px_16.36px_65.45px_rgba(0,0,0,0.09)] flex items-center justify-center shrink-0 mb-[18px]">
          <svg
            width="34"
            height="34"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#09B824"
            strokeWidth="3.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <polyline points="20 6 9 17 4 12" />
          </svg>
        </div>

        {/* Run label */}
        <span
          className={cx(
            "text-[15px] font-light leading-[19px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          {depotName.replace(/ depot$/, "")} run • {vehicleId}
        </span>

        {/* Title */}
        <h1
          className={cx(
            "text-[40px] font-medium leading-[50px] tracking-tight mt-[3px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          Run complete
        </h1>

        {/* Subtitle */}
        <p
          className={cx(
            "text-[15px] font-light leading-[19px] mt-[4px]",
            isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
          )}
        >
          {summary}
        </p>

        {/* Run summary card */}
        {/* Figma: h:203px, left:42.5px, right:42.5px, top:360px, radius:25px, padding: 6px 20px */}
        <div
          className={cx(
            "w-full rounded-[25px] mt-[24px] px-[20px] py-[8px] flex flex-col justify-between transition-colors",
            isNight
              ? "bg-[#292929] shadow-none"
              : "bg-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
          )}
        >
          {/* Row 1 · Stops delivered */}
          <div className="flex items-center justify-between py-[12px] border-b border-black/5 dark:border-white/10">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Stops delivered
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              7 of 7
            </span>
          </div>

          {/* Row 2 · Units delivered */}
          <div className="flex items-center justify-between py-[12px] border-b border-black/5 dark:border-white/10">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Units delivered
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              55 of 56
            </span>
          </div>

          {/* Row 3 · Issues reported */}
          <div className="flex items-center justify-between py-[12px] border-b border-black/5 dark:border-white/10">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Issues reported
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              2 damaged • 1 missing
            </span>
          </div>

          {/* Row 4 · Proof of delivery */}
          <div className="flex items-center justify-between py-[12px]">
            <span
              className={cx(
                "text-[15px] font-light leading-[19px]",
                isNight ? "text-[#A1A1AA]" : "text-[#6B7280]"
              )}
            >
              Proof of delivery
            </span>
            <span
              className={cx(
                "text-[15px] font-medium leading-[19px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              3 saved • syncing
            </span>
          </div>
        </div>

        {/* Next step prompt */}
        {/* Figma: left:40px, top:585px, font-size:15px, font-weight:300 */}
        <p
          className={cx(
            "text-[15px] font-light leading-[19px] mt-[22px]",
            isNight ? "text-white" : "text-black"
          )}
        >
          Next: return {vehicleId} to {depotName}, Dock 2.
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
