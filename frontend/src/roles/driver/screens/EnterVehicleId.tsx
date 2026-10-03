"use client";

import { useState, useRef, useCallback } from "react";
import { cx } from "@shared/ui";

export type EnterVehicleIdProps = {
  onBack?: () => void;
  onScanQrInstead: () => void;
  onContinue?: (vehicleId: string) => void;
  depotName?: string;
  isNight?: boolean;
  hideHeader?: boolean;
  /** The IDs this driver may take; the design's sample vehicles when absent. */
  knownIds?: string[];
  /** What to say for any other ID. */
  unknownMessage?: (vehicleId: string) => string;
};

const SAMPLE_IDS = ["VEH001", "VEH002", "VEH003", "VEH004", "VEH005"];

/**
 * "Enter vehicle ID" screen -- pixel-accurate to Figma spec.
 *
 * Light:  bg #E7F3F2, card #FFFFFF, shadow 0px 5px 20px rgba(0,0,0,.09), input bg #E7F3F2
 * Dark:   bg #161616, card #292929, no shadow, input bg #121212
 * Radius: card 41px, input 22px
 * Input:  h-64px, px-20px, font 16px/300, placeholder #A9A9A9
 * Helper: 13px/300, #6B7280 | error 13px/500, #E5484D
 * Focus border:  #0E766D (light) / #00BF6A (dark)
 * Error border:  #E5484D (both modes)
 */
export default function EnterVehicleId({
  onBack,
  onScanQrInstead,
  onContinue,
  depotName = "Kandy depot",
  isNight = false,
  hideHeader = false,
  knownIds = SAMPLE_IDS,
  unknownMessage,
}: EnterVehicleIdProps): React.JSX.Element {
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isFocused, setIsFocused] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);

  /** Normalise: uppercase + strip non-alphanumeric */
  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "");
    setValue(raw);
    if (error) setError(null);
  };

  const handleContinue = useCallback(async () => {
    const trimmed = value.trim();
    if (!trimmed) {
      setError("Please enter a vehicle ID.");
      inputRef.current?.focus();
      return;
    }
    setIsSubmitting(true);
    setError(null);
    // Simulate network lookup -- replace with real API call
    await new Promise<void>((resolve) => setTimeout(resolve, 500));
    if (!knownIds.includes(trimmed)) {
      setError(unknownMessage ? unknownMessage(trimmed) : `No vehicle ${trimmed} at ${depotName}. Check the sticker.`);
      setIsSubmitting(false);
      inputRef.current?.focus();
      return;
    }
    setIsSubmitting(false);
    onContinue?.(trimmed);
  }, [value, depotName, onContinue, knownIds, unknownMessage]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") void handleContinue();
  };

  // Border: transparent default -> teal on focus -> red on error
  const inputBorderStyle: React.CSSProperties = error
    ? { border: "1.5px solid #E5484D" }
    : isFocused
      ? { border: `1.5px solid ${isNight ? "#00BF6A" : "#0E766D"}` }
      : { border: "1.5px solid transparent" };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
      )}
    >
      {/* Top header */}
      <div className={cx(hideHeader ? "px-[43px] shrink-0" : "pt-[27px] px-[43px] shrink-0")}>
        {!hideHeader && (
          <div className="flex items-center justify-between">
            {/* Back button: 20px/500, h:30px */}
            <button
              type="button"
              onClick={() => {
                inputRef.current?.blur();
                onBack?.();
              }}
              className={cx(
                "flex items-center gap-[9px] text-[20px] font-medium leading-[25px] h-[30px] transition-opacity active:opacity-70",
                isNight ? "text-white" : "text-black"
              )}
              aria-label="Go back"
            >
              <svg
                width="9"
                height="14"
                viewBox="0 0 9 14"
                fill="none"
                className="shrink-0"
              >
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

            {/* Step label: 15px/300, right-aligned */}
            <span
              className={cx(
                "text-[15px] font-light leading-[19px] text-right",
                isNight ? "text-white" : "text-black"
              )}
            >
              Get vehicle
            </span>
          </div>
        )}

        {/* Title: Figma top:143px; 143-77=66px below Back row top -> mt-[39px] */}
        <div className="mt-[39px] flex flex-col gap-[2px]">
          <h1
            className={cx(
              "text-[40px] font-medium leading-[50px] tracking-tight",
              isNight ? "text-white" : "text-black"
            )}
          >
            Enter vehicle ID
          </h1>
          {/* Subtitle: Figma top:195px, gap from title bottom (193px) = 2px */}
          <p
            className={cx(
              "text-[15px] font-light leading-[19px]",
              isNight ? "text-white" : "text-black"
            )}
          >
            It&apos;s on the sticker by the driver&apos;s door.
          </p>
        </div>
      </div>

      {/* Vehicle ID card */}
      <div className="mt-[48px] mx-[25px] shrink-0">
        <div
          className={cx(
            "flex flex-col rounded-[41px] transition-colors",
            isNight ? "bg-[#292929]" : "bg-white"
          )}
          style={{
            padding: "24px 24px 24px",
            gap: "14px",
            boxShadow: isNight ? "none" : "0px 5px 20px rgba(0, 0, 0, 0.09)",
          }}
        >
          {/* Input: h:64px, radius:22px, px:20px
              Light bg: #E7F3F2 (same as page), Dark bg: #121212 */}
          <div
            className={cx(
              "flex items-center rounded-[22px] h-[64px] transition-all overflow-hidden",
              isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
            )}
            style={{ ...inputBorderStyle, paddingLeft: "20px", paddingRight: "20px" }}
          >
            <input
              ref={inputRef}
              id="vehicle-id-input"
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoCorrect="off"
              autoComplete="off"
              spellCheck={false}
              maxLength={12}
              placeholder="e.g. VEH003"
              value={value}
              onChange={handleChange}
              onFocus={() => setIsFocused(true)}
              onBlur={() => setIsFocused(false)}
              onKeyDown={handleKeyDown}
              aria-label="Vehicle ID"
              aria-describedby={error ? "vehicle-id-error" : undefined}
              aria-invalid={!!error}
              style={{
                outline: "none",
                border: "none",
                boxShadow: "none",
                "--autofill-bg": isNight ? "#121212" : "#E7F3F2",
                "--autofill-text": isNight ? "#ffffff" : "#000000",
              } as React.CSSProperties}
              className={cx(
                "driver-login-input w-full h-full bg-transparent border-0 border-none outline-none focus:outline-none focus-visible:outline-none focus:ring-0 focus-visible:ring-0 shadow-none text-[16px] font-light leading-[20px] caret-current",
                isNight ? "text-white" : "text-black",
                "placeholder:text-[#A9A9A9] placeholder:font-light"
              )}
            />
          </div>

          {/* Validation error if present */}
          {error && (
            <div className="pl-[6px] -mt-[2px]">
              <p
                id="vehicle-id-error"
                className="text-[13px] leading-[16px] font-medium text-[#E5484D]"
                role="alert"
              >
                {error}
              </p>
            </div>
          )}

          {/* Continue CTA inside card */}
          <button
            type="button"
            id="vehicle-id-continue-btn"
            onClick={() => void handleContinue()}
            disabled={isSubmitting}
            className={cx(
              "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] disabled:opacity-60",
              isNight
                ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                : "bg-[#031B08] text-white hover:bg-[#062613]"
            )}
            aria-label="Continue with entered vehicle ID"
          >
            {isSubmitting ? (
              <svg
                className="animate-spin"
                width="22"
                height="22"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
              >
                <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
              </svg>
            ) : (
              "Continue"
            )}
          </button>
        </div>
      </div>

      {/* Flexible spacer */}
      <div className="flex-1" />

      {/* Bottom controls: Scan QR button */}
      <div className="pb-[48px] px-[49px] shrink-0">
        <button
          type="button"
          id="vehicle-id-scan-qr-btn"
          onClick={() => {
            inputRef.current?.blur();
            onScanQrInstead();
          }}
          className={cx(
            "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99]",
            isNight
              ? "bg-[#292929] text-white hover:bg-[#333333]"
              : "bg-[#B7F2ED] text-black hover:bg-[#a8eae4]"
          )}
          aria-label="Scan QR code"
        >
          Scan QR
        </button>
      </div>
    </div>
  );
}