"use client";

import { useEffect, useState, type ReactNode } from "react";
import { cx } from "./primitives.tsx";

export type StructuredErrorProps = {
  /** Error code or status label displayed prominently above the truck, e.g. 503, 404, 500, "OFFLINE" */
  code?: string | number | null;
  /** Primary heading, e.g. "Cannot reach dispatch", "Page not found". Auto-derived from code if omitted. */
  title?: string;
  /** Explanatory body text below the card. Auto-derived from code if omitted. */
  message?: ReactNode;
  /** Action button label, defaults based on code (e.g. "Try again", "Return to home") */
  actionLabel?: string;
  /** Callback when the action button is clicked */
  onAction?: () => void;
  /** Optional secondary action, e.g. a link or dismiss button */
  secondaryAction?: ReactNode;
  /**
   * Appearance mode: defaults to "light" to match standard Waypoint canvas & mockups.
   * Can be set to "dark" or "auto" (following OS/workspace preference).
   */
  theme?: "light" | "dark" | "auto";
  /** Path to light mode illustration, defaults to "/assets/error_truck_light.webp" */
  imageLight?: string;
  /** Path to dark mode illustration, defaults to "/assets/error_truck_dark.webp" */
  imageDark?: string;
  /** Whether to fill the full viewport (min-h-dvh) or render inside a card container */
  fullScreen?: boolean;
  /** Whether to show a subtle Day / Night toggle in the corner */
  showThemeToggle?: boolean;
  className?: string;
};

/** Provides intelligent, human-friendly titles, messages, and action defaults for standard error codes. */
export function getErrorDefaults(code?: string | number | null): {
  title: string;
  message: string;
  actionLabel: string;
  defaultAction?: () => void;
} {
  const norm = typeof code === "number" ? code : typeof code === "string" ? code.trim().toUpperCase() : null;

  if (norm === 404 || norm === "404" || norm === "NOT_FOUND") {
    return {
      title: "Page not found",
      message: "The page or destination you requested could not be found.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Return to home",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.href = "/";
      },
    };
  }

  if (norm === 401 || norm === "401" || norm === "UNAUTHORIZED") {
    return {
      title: "Session expired",
      message: "Your session has ended. Please sign in again to continue.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Sign in",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === 403 || norm === "403" || norm === "FORBIDDEN") {
    return {
      title: "Access denied",
      message: "You do not have permission to access this area or role address.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Return to home",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.href = "/";
      },
    };
  }

  if (norm === 500 || norm === "500" || norm === "INTERNAL_SERVER_ERROR") {
    return {
      title: "Something went wrong",
      message: "An unexpected server error occurred while loading this view.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === 502 || norm === "502" || norm === 504 || norm === "504" || norm === "GATEWAY_TIMEOUT") {
    return {
      title: "Dispatch is unreachable",
      message: "The server took too long to answer or could not be reached.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === "OFFLINE") {
    return {
      title: "This device is offline",
      message: "You are currently offline.\nDon't worry—your work is saved safely on this phone.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  return {
    title: typeof norm === "number" ? `Error ${norm}` : "Cannot reach dispatch",
    message: "Having trouble connecting right now.\nDon't worry—your work is saved safely on this phone.",
    actionLabel: "Try again",
    defaultAction: () => {
      if (typeof window !== "undefined") window.location.reload();
    },
  };
}

/**
 * Standard structured error template for Waypoint Dispatch.
 * Designed for mobile phone viewports and driver cab readability:
 * - Clear, non-technical heading
 * - Large visual error code with truck breakdown illustration
 * - Card background perfectly matches page background
 * - Reassuring explanation emphasizing that saved work is safe
 * - Prominent, accessible touch target (min 52px)
 * - Seamless support for daylight (default) and night appearance modes
 */
export function StructuredError({
  code = 503,
  title,
  message,
  actionLabel,
  onAction,
  secondaryAction,
  theme = "light",
  imageLight = "/assets/error_truck_light.webp",
  imageDark = "/assets/error_truck_dark.webp",
  fullScreen = true,
  showThemeToggle = true,
  className,
}: StructuredErrorProps): React.JSX.Element {
  const [explicitTheme, setExplicitTheme] = useState<"light" | "dark" | null>(
    theme === "auto" ? null : theme
  );

  useEffect(() => {
    if (theme !== "auto") {
      setExplicitTheme(theme);
    }
  }, [theme]);

  // Determine whether night/dark mode is active
  const isNight =
    explicitTheme === "dark"
      ? true
      : explicitTheme === "light"
      ? false
      : false; // default to clean light mode matching mockup

  const defaults = getErrorDefaults(code);
  const resolvedTitle = title ?? defaults.title;
  const resolvedMessage = message ?? defaults.message;
  const resolvedActionLabel = actionLabel ?? defaults.actionLabel;
  const handleAction = onAction ?? defaults.defaultAction;

  const content = (
    <div className="flex w-full max-w-[390px] flex-col items-center text-center">
      {/* Title */}
      <h1
        className={cx(
          "text-[28px] sm:text-[32px] font-normal tracking-tight mb-6 transition-colors",
          isNight ? "text-white" : "text-[#031b08]"
        )}
      >
        {resolvedTitle}
      </h1>

      {/* Structured Card: Large Code + Top Fade + Truck Breakdown Illustration */}
      <div
        className={cx(
          "w-full rounded-[28px] sm:rounded-[32px] overflow-hidden transition-colors",
          isNight ? "bg-[#071512]" : "bg-go-canvas"
        )}
      >
        {/* Error Code */}
        {code !== null && code !== undefined && (
          <div className="pt-6 sm:pt-7 pb-1 text-center select-none">
            <span
              className={cx(
                "text-[72px] sm:text-[80px] font-normal leading-none tracking-tight transition-colors",
                isNight ? "text-white" : "text-[#031b08]"
              )}
            >
              {code}
            </span>
          </div>
        )}

        {/* Truck Breakdown Illustration */}
        <div className="relative w-full overflow-hidden">
          <img
            src={isNight ? imageDark : imageLight}
            alt={typeof code === "number" || typeof code === "string" ? `Error ${code}` : resolvedTitle}
            className="w-full h-auto block select-none pointer-events-none"
            width={786}
            height={443}
            loading="eager"
          />
        </div>
      </div>

      {/* Explanatory Message */}
      <div className="mt-6 sm:mt-7 mb-7 sm:mb-8 px-2">
        <p
          className={cx(
            "text-[16px] sm:text-[17px] font-normal leading-relaxed whitespace-pre-line transition-colors",
            isNight ? "text-[#edf5f0]" : "text-[#14231e]"
          )}
        >
          {resolvedMessage}
        </p>
      </div>

      {/* Main Action Button */}
      {handleAction && (
        <button
          type="button"
          onClick={handleAction}
          className={cx(
            "w-full min-h-[52px] sm:min-h-[56px] rounded-full text-[16px] font-medium tracking-wide flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
            isNight
              ? "bg-white text-[#031a0c] hover:bg-slate-100"
              : "bg-[#031a0c] text-white hover:bg-[#062613]"
          )}
        >
          {resolvedActionLabel}
        </button>
      )}

      {/* Secondary Action */}
      {secondaryAction && (
        <div className="mt-3 w-full flex justify-center">
          {secondaryAction}
        </div>
      )}
    </div>
  );

  if (!fullScreen) {
    return (
      <section
        role="alert"
        aria-live="assertive"
        className={cx("w-full flex justify-center p-4 font-go", className)}
      >
        {content}
      </section>
    );
  }

  return (
    <main
      role="alert"
      aria-live="assertive"
      className={cx(
        "relative flex min-h-dvh w-full items-center justify-center px-4 py-8 font-go transition-colors",
        isNight ? "bg-[#071512]" : "bg-go-canvas",
        className
      )}
    >
      {/* Day / Night appearance toggle in corner */}
      {showThemeToggle && (
        <div className="absolute top-4 right-4 z-20">
          <button
            type="button"
            onClick={() => setExplicitTheme(isNight ? "light" : "dark")}
            className={cx(
              "flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium border shadow-xs transition-all",
              isNight
                ? "bg-[#14271e] border-[#304b3b] text-[#edf5f0] hover:bg-[#1a3428]"
                : "bg-white border-[#dfe7e6] text-[#031b08] hover:bg-slate-50"
            )}
            title="Toggle Day/Night mode"
            aria-label="Toggle Day/Night mode"
          >
            <span>{isNight ? "🌙 Night" : "☀️ Day"}</span>
          </button>
        </div>
      )}

      {content}
    </main>
  );
}
