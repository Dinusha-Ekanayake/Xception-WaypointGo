"use client";

import { useState } from "react";
import { cx } from "@shared/ui";

export type RouteMapScreenProps = {
  onBack: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  hideHeader?: boolean;
};

export default function RouteMapScreen({
  onBack,
  isNight = false,
  onToggleTheme,
  hideHeader = false,
}: RouteMapScreenProps): React.JSX.Element {
  const [zoomLevel, setZoomLevel] = useState(1);

  const handleRecenter = () => {
    setZoomLevel((prev) => (prev === 1 ? 1.08 : 1));
  };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden",
        isNight ? "bg-[#18212F]" : "bg-[#EDF2F4]"
      )}
    >
      {/* ================================================================= */}
      {/* 1. Vector Map Canvas (Authentic Google Maps navigation render)    */}
      {/* ================================================================= */}
      <div
        className="absolute inset-0 w-full h-full overflow-hidden transition-transform duration-500 ease-out"
        style={{ transform: `scale(${zoomLevel})` }}
      >
        <svg
          viewBox="0 0 393 852"
          className="w-full h-full object-cover"
          preserveAspectRatio="xMidYMid slice"
          fill="none"
        >
          {/* Base Terrain Background */}
          <rect
            width="393"
            height="852"
            fill={isNight ? "#1B2433" : "#F4F6F6"}
          />

          {/* Greenery / Terrain Patches (Parks & reserves in central province) */}
          {/* Top Left Park (Mawanella foothills) */}
          <path
            d="M-30 140 C 20 80, 100 90, 120 150 C 130 190, 90 240, 40 230 C -10 220, -50 180, -30 140 Z"
            fill={isNight ? "#1C362A" : "#D4EEDB"}
          />
          {/* Lake / Water Reservoir (Left) */}
          <path
            d="M18 335 C 10 300, 60 295, 75 325 C 88 350, 72 380, 45 375 C 22 370, 15 350, 18 335 Z"
            fill={isNight ? "#172F4A" : "#BBD8FA"}
          />
          {/* Kadugannawa Pass Greenery (Right Middle) */}
          <path
            d="M290 280 C 330 270, 390 280, 400 330 C 410 380, 350 410, 300 400 C 270 385, 275 310, 290 280 Z"
            fill={isNight ? "#1C362A" : "#D4EEDB"}
          />
          {/* Lower Right Reserve */}
          <path
            d="M275 490 C 320 470, 400 480, 410 540 C 415 590, 340 620, 285 595 C 265 570, 260 520, 275 490 Z"
            fill={isNight ? "#1C362A" : "#D4EEDB"}
          />

          {/* Secondary Road Network (Thin white / dark gray paths) */}
          {/* Cross street 1 (top to mid-left) */}
          <path
            d="M165 0 C 175 140, 80 410, 35 600"
            stroke={isNight ? "#273549" : "#FFFFFF"}
            strokeWidth="8"
            strokeLinecap="round"
          />
          {/* Cross street 2 (connecting Mawanella across A1) */}
          <path
            d="M-20 340 C 100 365, 260 380, 410 440"
            stroke={isNight ? "#273549" : "#FFFFFF"}
            strokeWidth="7"
            strokeLinecap="round"
          />
          {/* Peradeniya connection road */}
          <path
            d="M220 185 C 290 240, 340 260, 410 275"
            stroke={isNight ? "#273549" : "#FFFFFF"}
            strokeWidth="7"
            strokeLinecap="round"
          />

          {/* Railway line (Gray dashed transit line) */}
          <path
            d="M-20 660 L 410 160"
            stroke={isNight ? "#4B5563" : "#9CA3AF"}
            strokeWidth="2.5"
            strokeDasharray="6 6"
          />

          {/* Highway A1 Trunk (Wide golden highway) */}
          {/* Casing / Border */}
          <path
            d="M-30 880 L 155 575 C 215 480, 255 380, 220 230 L 410 70"
            stroke={isNight ? "#5A4B2F" : "#DEB038"}
            strokeWidth="15"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          {/* Main Gold Highway Fill */}
          <path
            d="M-30 880 L 155 575 C 215 480, 255 380, 220 230 L 410 70"
            stroke={isNight ? "#8A7349" : "#F8D468"}
            strokeWidth="11"
            strokeLinecap="round"
            strokeLinejoin="round"
          />

          {/* ============================================================= */}
          {/* Navigation Active Route Line (Vibrant Blue Polyline)          */}
          {/* ============================================================= */}
          {/* Blue route line glow / shadow */}
          <path
            d="M152 575 C 215 480, 255 380, 226 230"
            stroke={isNight ? "#1D4ED8" : "#1967D2"}
            strokeWidth="11"
            strokeLinecap="round"
            strokeLinejoin="round"
            opacity="0.35"
          />
          {/* Main Active Blue Route */}
          <path
            d="M152 575 C 215 480, 255 380, 226 230"
            stroke={isNight ? "#3B82F6" : "#2472F5"}
            strokeWidth="8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />

          {/* Geographic & Highway Labels */}
          {/* Mawanella */}
          <text
            x="22"
            y="460"
            fill={isNight ? "#94A3B8" : "#64748B"}
            fontSize="12.5"
            fontWeight="400"
            fontFamily="'Google Sans Flex', sans-serif"
          >
            Mawanella
          </text>
          {/* Kadugannawa Pass */}
          <text
            x="275"
            y="318"
            fill={isNight ? "#94A3B8" : "#64748B"}
            fontSize="12.5"
            fontWeight="400"
            fontFamily="'Google Sans Flex', sans-serif"
          >
            Kadugannawa Pass
          </text>
          {/* Pilimathalawa */}
          <text
            x="306"
            y="250"
            fill={isNight ? "#94A3B8" : "#64748B"}
            fontSize="12.5"
            fontWeight="400"
            fontFamily="'Google Sans Flex', sans-serif"
          >
            Pilimathalawa
          </text>

          {/* Route A1 Badge */}
          <g transform="translate(250, 476)">
            <rect
              width="26"
              height="16"
              rx="4"
              fill={isNight ? "#243247" : "#FFFFFF"}
              stroke={isNight ? "#475569" : "#64748B"}
              strokeWidth="1"
            />
            <text
              x="13"
              y="11.5"
              fill={isNight ? "#E2E8F0" : "#1E293B"}
              fontSize="9.5"
              fontWeight="600"
              textAnchor="middle"
              fontFamily="'Google Sans Flex', sans-serif"
            >
              A1
            </text>
          </g>

          {/* Waypoint 02 Circle Badge on Highway Ahead */}
          <g transform="translate(305, 150)">
            <circle
              cx="0"
              cy="0"
              r="11"
              fill={isNight ? "#232F42" : "#FFFFFF"}
              stroke={isNight ? "#64748B" : "#475569"}
              strokeWidth="1.5"
            />
            <text
              x="0"
              y="3.5"
              fill={isNight ? "#FFFFFF" : "#000000"}
              fontSize="9.5"
              fontWeight="600"
              textAnchor="middle"
              fontFamily="'Google Sans Flex', sans-serif"
            >
              02
            </text>
          </g>

          {/* ============================================================= */}
          {/* Vehicle Position Radar Cone & Pulsating Beacon                */}
          {/* ============================================================= */}
          {/* Directional Radar Sector */}
          <g transform="translate(150, 580)">
            <path
              d="M0 0 L -30 -30 A 42 42 0 0 1 30 -30 Z"
              fill={isNight ? "rgba(59, 130, 246, 0.3)" : "rgba(36, 114, 245, 0.28)"}
              transform="rotate(24)"
            />
            {/* Outer radar halo */}
            <circle
              cx="0"
              cy="0"
              r="24"
              fill={isNight ? "rgba(59, 130, 246, 0.2)" : "rgba(36, 114, 245, 0.18)"}
            />
            {/* White ring */}
            <circle
              cx="0"
              cy="0"
              r="10"
              fill="#FFFFFF"
              filter="drop-shadow(0 1px 4px rgba(0,0,0,0.25))"
            />
            {/* Blue dot core */}
            <circle cx="0" cy="0" r="6" fill="#1A73E8" />
          </g>

          {/* ============================================================= */}
          {/* Destination Pin (Peradeniya - Stop 01)                        */}
          {/* ============================================================= */}
          <g transform="translate(225, 230)">
            {/* Red Teardrop Marker */}
            <path
              d="M0 -34 C -8.5 -34, -15 -27.5, -15 -19 C -15 -9, 0 0, 0 0 C 0 0, 15 -9, 15 -19 C 15 -27.5, 8.5 -34, 0 -34 Z"
              fill="#E53935"
              filter="drop-shadow(0 3px 6px rgba(0,0,0,0.3))"
            />
            {/* Dark inner circle */}
            <circle cx="0" cy="-19" r="5" fill="#7F1D1D" />

            {/* Destination Name Label */}
            <text
              x="22"
              y="-14"
              fill={isNight ? "#F87171" : "#D93025"}
              fontSize="14.5"
              fontWeight="600"
              fontFamily="'Google Sans Flex', sans-serif"
            >
              Peradeniya
            </text>
          </g>
        </svg>
      </div>

      {/* ================================================================= */}
      {/* 2. Overlaid Navigation Controls & Header                          */}
      {/* ================================================================= */}
      {/* Top Header Row (Back + Synced 05:31 + Theme toggle) */}
      {!hideHeader && (
        <div className="pt-[22px] px-[22px] flex items-center justify-between z-20 pointer-events-none">
          {/* Back Button Pill */}
          <button
            type="button"
            onClick={onBack}
            className={cx(
              "pointer-events-auto h-[43px] px-4 rounded-full flex items-center gap-2 transition-all active:scale-95 shadow-md",
              isNight
                ? "bg-[#292929] text-white hover:bg-[#333333]"
                : "bg-white text-black hover:bg-slate-50"
            )}
            aria-label="Back to route"
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
            <span className="text-[17px] font-medium leading-none">Back</span>
          </button>

          {/* Right: Synced pill + Theme toggle */}
          <div className="flex items-center gap-3">
            {/* Synced pill */}
            <div
              className={cx(
                "pointer-events-auto h-[43px] px-4 rounded-full flex items-center justify-center transition-colors shadow-md",
                isNight ? "bg-[#292929] text-white" : "bg-white text-black"
              )}
            >
              <span className="text-[15px] font-medium leading-none">Synced 05:31</span>
            </div>

            {/* Theme toggle */}
            <button
              type="button"
              onClick={onToggleTheme}
              className={cx(
                "pointer-events-auto size-[43px] rounded-full flex items-center justify-center transition-all active:scale-95 shadow-md",
                isNight ? "bg-[#292929] text-white hover:bg-[#333333]" : "bg-white text-black hover:bg-slate-50"
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
                  <line x1="12" y1="2" x2="12" y2="3" />
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
      )}

      {/* Floating Compass Button (Top Right under Theme toggle) */}
      <div className="absolute top-[82px] right-[22px] z-20">
        <button
          type="button"
          onClick={handleRecenter}
          className={cx(
            "size-[40px] rounded-full flex items-center justify-center transition-all active:scale-95 shadow-md",
            isNight
              ? "bg-[#292929] border border-[#3E4A5B]"
              : "bg-white border border-[#E5E7EB]"
          )}
          aria-label="Compass"
        >
          {/* Compass Needle (Red North, White South) */}
          <svg width="18" height="22" viewBox="0 0 18 22" fill="none">
            <polygon points="9 1 14 11 9 9 4 11" fill="#EA4335" />
            <polygon points="9 21 14 11 9 13 4 11" fill={isNight ? "#CBD5E1" : "#94A3B8"} />
          </svg>
        </button>
      </div>

      {/* Flexible Spacer */}
      <div className="flex-1" />

      {/* Floating Recenter / Navigation Arrow FAB (Bottom Right) */}
      <div className="absolute bottom-[36px] right-[24px] z-20">
        <button
          type="button"
          onClick={handleRecenter}
          className={cx(
            "size-[48px] rounded-full flex items-center justify-center transition-all active:scale-90 shadow-xl",
            isNight
              ? "bg-[#292929] text-[#3B82F6] hover:bg-[#333333]"
              : "bg-white text-[#1A73E8] hover:bg-slate-50"
          )}
          aria-label="Recenter location"
        >
          {/* Blue Navigation Arrow */}
          <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z" transform="rotate(45 12 12)" />
          </svg>
        </button>
      </div>

      {/* Google Watermark (Bottom Left) */}
      <div className="absolute bottom-[34px] left-[18px] z-20 pointer-events-none">
        {isNight ? (
          <span className="text-white/80 font-bold text-[15px] tracking-tight">Google</span>
        ) : (
          <div className="flex items-center text-[15px] font-bold tracking-tight">
            <span className="text-[#4285F4]">G</span>
            <span className="text-[#EA4335]">o</span>
            <span className="text-[#FBBC05]">o</span>
            <span className="text-[#4285F4]">g</span>
            <span className="text-[#34A853]">l</span>
            <span className="text-[#EA4335]">e</span>
          </div>
        )}
      </div>
    </div>
  );
}
