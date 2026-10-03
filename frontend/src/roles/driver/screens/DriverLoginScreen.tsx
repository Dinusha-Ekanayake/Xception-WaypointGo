"use client";

import { useState, useRef } from "react";
import { cx } from "@shared/ui";

export type DriverLoginScreenProps = {
  onLoginSuccess: (staffId: string) => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  defaultStaffId?: string;
};

export default function DriverLoginScreen({
  onLoginSuccess,
  isNight = false,
  onToggleTheme,
  defaultStaffId = "",
}: DriverLoginScreenProps): React.JSX.Element {
  const [staffId, setStaffId] = useState(defaultStaffId);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isStaffFocused, setIsStaffFocused] = useState(false);
  const [isPassFocused, setIsPassFocused] = useState(false);

  const passwordInputRef = useRef<HTMLInputElement | null>(null);

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    const cleanId = staffId.trim().toUpperCase();
    const cleanPw = password.trim();

    if (!cleanId || !cleanPw) {
      setError("Please enter your staff ID and password.");
      return;
    }

    setIsLoading(true);
    setError(null);

    // Verify specified credentials: staff id DRV-00021, pw 1234
    setTimeout(() => {
      if (cleanId === "DRV-00021" && cleanPw === "1234") {
        setIsLoading(false);
        onLoginSuccess("DRV-00021");
      } else {
        setIsLoading(false);
        setError("Invalid staff ID or password. (Use DRV-00021 / 1234)");
      }
    }, 280);
  };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
      )}
    >
      {/* ================================================================= */}
      {/* 1. Background Vector Street Map (Layer_1 from Figma)               */}
      {/* ================================================================= */}
      <div className="absolute inset-x-0 top-0 h-[480px] pointer-events-none overflow-hidden">
        <svg
          viewBox="0 0 393 480"
          className="w-full h-full object-cover"
          fill="none"
          preserveAspectRatio="xMidYMid slice"
        >
          {/* Subtle grid and street vectors */}
          <g
            stroke={isNight ? "rgba(255, 255, 255, 0.05)" : "rgba(14, 118, 109, 0.08)"}
            strokeWidth="1.2"
          >
            {/* Diagonal arterials */}
            <path d="M-60 120 L450 40 M-40 220 L440 90 M-80 310 L460 180 M-30 420 L440 260" strokeWidth="2.5" />
            <path d="M120 -40 L280 500 M-20 40 L160 520 M240 -30 L400 480" strokeWidth="2" />
            {/* Grid street networks */}
            <path d="M40 80 L180 180 L290 140 L380 220 M110 200 L230 290 L340 240 M-10 280 L140 370 L260 320" />
            <path d="M90 60 L140 10 M170 110 L220 50 M250 160 L300 110 M330 200 L380 160" />
            <path d="M70 190 L110 130 M150 240 L190 180 M230 300 L270 240 M310 350 L350 290" />
            <path d="M-20 180 L70 240 L50 320 M140 30 L200 80 L180 160 M270 10 L340 70 L320 150" />
            <path d="M30 340 L110 390 M180 360 L260 410 M290 280 L370 330" />
          </g>

          {/* City building footprints */}
          <g fill={isNight ? "rgba(255, 255, 255, 0.03)" : "rgba(14, 118, 109, 0.04)"}>
            <polygon points="50,90 80,80 75,110 45,120" />
            <polygon points="120,60 150,50 145,80 115,90" />
            <polygon points="200,90 230,80 225,120 195,130" />
            <polygon points="80,180 110,170 105,200 75,210" />
            <polygon points="170,170 200,160 195,200 165,210" />
            <polygon points="260,160 290,150 285,190 255,200" />
            <polygon points="130,260 170,250 165,290 125,300" />
            <polygon points="220,250 260,240 255,280 215,290" />
          </g>
        </svg>

        {/* Rectangle 20 Gradient Mask (fades from transparent to solid background) */}
        <div
          className="absolute inset-0 pointer-events-none"
          style={{
            background: isNight
              ? "linear-gradient(180deg, rgba(22, 22, 22, 0) 0%, rgba(22, 22, 22, 0.55) 25%, #161616 85%)"
              : "linear-gradient(180deg, rgba(231, 243, 242, 0) 0%, rgba(231, 243, 242, 0.53) 25%, #E7F3F2 85%)",
          }}
        />
      </div>

      {/* ================================================================= */}
      {/* 2. Top Bar: GO Brand Logo + Theme Toggle (Aligned with Home Header)*/}
      {/* ================================================================= */}
      <header className="w-full flex items-center justify-between px-6 pt-5 pb-3 shrink-0 select-none z-10 relative">
        {/* GO Logo - matching DriverHeader exactly */}
        <span
          className={cx(
            "text-[40px] font-extrabold tracking-tight leading-none font-go transition-colors",
            isNight ? "text-white" : "text-black"
          )}
        >
          GO
        </span>

        {/* Theme Toggle - matching DriverHeader button styling and positioning */}
        {onToggleTheme && (
          <button
            type="button"
            onClick={onToggleTheme}
            className={cx(
              "w-[42px] h-[42px] rounded-[22px] flex items-center justify-center border shadow-[0_5px_20px_rgba(0,0,0,0.09)] active:scale-95 transition-all",
              isNight
                ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
                : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
            )}
            title={isNight ? "Switch to day mode" : "Switch to night mode"}
            aria-label={isNight ? "Switch to day mode" : "Switch to night mode"}
          >
            {isNight ? (
              /* Moon icon */
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
              </svg>
            ) : (
              /* Sun icon */
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
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
        )}
      </header>

      {/* Flexible Spacer to position Welcome Back above card */}
      <div className="flex-1" />

      {/* ================================================================= */}
      {/* 3. Welcome back Title + Subtitle                                  */}
      {/* ================================================================= */}
      <div className="relative px-[36px] mb-[20px] flex flex-col gap-[2px] z-10 shrink-0">
        <h1
          className={cx(
            "text-[40px] font-medium leading-[50px] tracking-tight transition-colors",
            isNight ? "text-white" : "text-black"
          )}
        >
          Welcome back
        </h1>
        <p
          className={cx(
            "text-[15px] font-light leading-[19px] transition-colors",
            isNight ? "text-[#A9A9A9]" : "text-black"
          )}
        >
          Sign in to open your workspace.
        </p>
      </div>

      {/* ================================================================= */}
      {/* 4. Login Card (Figma: rounded-41px, p-6, gap-4)                   */}
      {/* ================================================================= */}
      <form
        onSubmit={handleSubmit}
        className="relative mx-[25px] shrink-0 z-10"
      >
        <div
          className={cx(
            "flex flex-col rounded-[41px] p-[24px] gap-[16px] transition-colors",
            isNight ? "bg-[#292929]" : "bg-white"
          )}
          style={{
            boxShadow: isNight ? "none" : "0px 5px 20px rgba(0, 0, 0, 0.05)",
          }}
        >
          {/* Staff ID Input Field */}
          <div
            className={cx(
              "flex items-center rounded-[22px] h-[64px] px-[20px] transition-all overflow-hidden",
              isNight ? "bg-[#161616]" : "bg-[#E7F3F2]",
              error && "border border-[#E5484D]",
              isStaffFocused && !error && (isNight ? "border border-[#00BF6A]" : "border border-[#0E766D]")
            )}
          >
            <input
              id="login-staff-id"
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoCorrect="off"
              autoComplete="username"
              placeholder="Enter your staff ID"
              value={staffId}
              onChange={(e) => {
                setStaffId(e.target.value.toUpperCase());
                if (error) setError(null);
              }}
              onFocus={() => setIsStaffFocused(true)}
              onBlur={() => setIsStaffFocused(false)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  passwordInputRef.current?.focus();
                }
              }}
              style={{
                outline: "none",
                border: "none",
                boxShadow: "none",
                "--autofill-bg": isNight ? "#161616" : "#E7F3F2",
                "--autofill-text": isNight ? "#ffffff" : "#000000",
              } as React.CSSProperties}
              className={cx(
                "driver-login-input w-full h-full bg-transparent border-0 border-none outline-none focus:outline-none focus-visible:outline-none focus:ring-0 focus-visible:ring-0 shadow-none text-[16px] font-light leading-[20px] caret-current",
                isNight ? "text-white" : "text-black",
                "placeholder:text-[#A9A9A9] placeholder:font-light"
              )}
            />
          </div>

          {/* Password Input Field */}
          <div
            className={cx(
              "flex items-center rounded-[22px] h-[64px] px-[20px] transition-all overflow-hidden",
              isNight ? "bg-[#161616]" : "bg-[#E7F3F2]",
              error && "border border-[#E5484D]",
              isPassFocused && !error && (isNight ? "border border-[#00BF6A]" : "border border-[#0E766D]")
            )}
          >
            <input
              ref={passwordInputRef}
              id="login-password"
              type="password"
              autoComplete="current-password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (error) setError(null);
              }}
              onFocus={() => setIsPassFocused(true)}
              onBlur={() => setIsPassFocused(false)}
              style={{
                outline: "none",
                border: "none",
                boxShadow: "none",
                "--autofill-bg": isNight ? "#161616" : "#E7F3F2",
                "--autofill-text": isNight ? "#ffffff" : "#000000",
              } as React.CSSProperties}
              className={cx(
                "driver-login-input w-full h-full bg-transparent border-0 border-none outline-none focus:outline-none focus-visible:outline-none focus:ring-0 focus-visible:ring-0 shadow-none text-[16px] font-light leading-[20px] caret-current",
                isNight ? "text-white" : "text-black",
                "placeholder:text-[#A9A9A9] placeholder:font-light"
              )}
            />
          </div>

          {/* Validation Error Message */}
          {error && (
            <p className="text-[13px] leading-[16px] font-medium text-[#E5484D] px-[6px] -mt-[4px]">
              {error}
            </p>
          )}

          {/* Forgot Password Row */}
          <div className="flex items-center gap-[6px] px-[6px]">
            <span
              className={cx(
                "text-[14px] font-light leading-[18px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              Forgot password?
            </span>
            <button
              type="button"
              onClick={() => {
                alert("Please contact Kandy Depot Dispatch support at +94 11 234 5678.");
              }}
              className={cx(
                "text-[14px] font-medium leading-[18px] underline underline-offset-2 transition-colors",
                isNight ? "text-[#00BF6A] hover:text-[#00d878]" : "text-[#0E766D] hover:text-[#0a5750]"
              )}
            >
              Call support
            </button>
          </div>

          {/* Sign in Button */}
          <button
            type="submit"
            id="login-sign-in-btn"
            disabled={isLoading}
            className={cx(
              "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] disabled:opacity-75 shadow-sm mt-[2px]",
              isNight
                ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                : "bg-[#031B08] text-white hover:bg-[#062613]"
            )}
          >
            {isLoading ? (
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
              "Sign in"
            )}
          </button>
        </div>
      </form>

      {/* ================================================================= */}
      {/* 5. Footer Offline Sync Disclaimer (Figma: bottom 51px)            */}
      {/* ================================================================= */}
      <div className="relative px-[38px] mt-[24px] mb-[20px] shrink-0 z-10">
        <p
          className={cx(
            "text-[13px] font-light leading-[16px] text-center transition-colors",
            isNight ? "text-[#9CA3AF]" : "text-[#6B7280]"
          )}
        >
          After your first sign-in, GO keeps working offline. Your work syncs when the connection returns.
        </p>
      </div>

      {/* ================================================================= */}
      {/* 6. Home Indicator Pill (Figma: w:140px, h:5px, bottom: 7.67px)    */}
      {/* ================================================================= */}
      <div className="w-full flex justify-center pb-[8px] shrink-0">
        <div
          className={cx(
            "w-[140px] h-[5px] rounded-[100px] transition-colors",
            isNight ? "bg-white/30" : "bg-black/30"
          )}
        />
      </div>
    </div>
  );
}
