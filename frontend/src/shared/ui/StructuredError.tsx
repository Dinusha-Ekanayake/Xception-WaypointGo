"use client";

import { useEffect, useState, type ReactNode } from "react";
import { cx } from "./primitives.tsx";
import { ThemeToggle, keepShellTheme, storedShellDark } from "./ThemeToggle.tsx";

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
   * Appearance mode. The default, "auto", follows the choice kept by the sign-in's
   * theme toggle, which is light until someone picks dark.
   */
  theme?: "light" | "dark" | "auto";
  /** Path to light mode illustration, defaults to "/assets/error_truck_light.webp" */
  imageLight?: string;
  /** Path to dark mode illustration, defaults to "/assets/error_truck_dark.webp" */
  imageDark?: string;
  /** Whether to fill the full viewport (min-h-dvh) or render inside a card container */
  fullScreen?: boolean;
  /** Whether to show the sun and moon theme toggle in the corner */
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
      message: "The page or destination you requested could not be found.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Return to home",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.href = "/";
      },
    };
  }

  if (norm === 401 || norm === "401" || norm === "UNAUTHORIZED") {
    return {
      title: "Session expired",
      message: "Your session has ended. Please sign in again to continue.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Sign in",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === 403 || norm === "403" || norm === "FORBIDDEN") {
    return {
      title: "Access denied",
      message: "You do not have permission to access this area or role address.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Return to home",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.href = "/";
      },
    };
  }

  if (norm === 500 || norm === "500" || norm === "INTERNAL_SERVER_ERROR") {
    return {
      title: "Something went wrong",
      message: "Something went wrong at Waypoint while loading this screen.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === 502 || norm === "502" || norm === 504 || norm === "504" || norm === "GATEWAY_TIMEOUT") {
    return {
      title: "We can't reach Waypoint",
      message: "Waypoint took too long to answer, or there is no connection.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === "OFFLINE") {
    return {
      title: "You're offline",
      message: "You are currently offline.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (norm === 503 || norm === "503") {
    return {
      title: "We can't reach Waypoint",
      message: "Waypoint took too long to answer, or there is no connection.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  if (typeof norm === "number" && norm >= 500) {
    return {
      title: "Waypoint had a problem",
      message: "Something went wrong at Waypoint while loading this screen.\nDon't worry: your work is saved safely on this device.",
      actionLabel: "Try again",
      defaultAction: () => {
        if (typeof window !== "undefined") window.location.reload();
      },
    };
  }

  return {
    title: typeof norm === "number" ? `Error ${norm}` : "Cannot reach dispatch",
    message: "Having trouble connecting right now.\nDon't worry: your work is saved safely on this device.",
    actionLabel: "Try again",
    defaultAction: () => {
      if (typeof window !== "undefined") window.location.reload();
    },
  };
}

/**
 * Standard structured error template for Waypoint Dispatch.
 * - Clear, non-technical heading
 * - Large visual error code with truck breakdown illustration
 * - Reassuring explanation emphasizing that saved work is safe
 * - Prominent, accessible touch target (min 52px)
 * - One column on a phone; on a wide screen the words sit beside the picture
 * - Light by default, with the same sun and moon toggle as the sign-in (ThemeToggle)
 */
export function StructuredError({
  code = 503,
  title,
  message,
  actionLabel,
  onAction,
  secondaryAction,
  theme = "auto",
  imageLight = "/assets/error_truck_light.webp",
  imageDark = "/assets/error_truck_dark.webp",
  fullScreen = true,
  showThemeToggle = true,
  className,
}: StructuredErrorProps): React.JSX.Element {
  const [isNight, setIsNight] = useState(theme === "dark");

  // "auto" follows the choice kept by the sign-in's toggle; an explicit theme is kept as given.
  useEffect(() => {
    setIsNight(theme === "auto" ? storedShellDark() : theme === "dark");
  }, [theme]);

  const toggle = () => {
    const next = !isNight;
    setIsNight(next);
    keepShellTheme(next);
  };

  const defaults = getErrorDefaults(code);
  const resolvedTitle = title ?? defaults.title;
  const resolvedMessage = message ?? defaults.message;
  const resolvedActionLabel = actionLabel ?? defaults.actionLabel;
  const handleAction = onAction ?? defaults.defaultAction;
  const wide = fullScreen;

  // Three parts: a phone stacks them (title, picture, words); from lg the title and
  // words share the left column and the picture fills the right, so a desktop is not
  // shown a phone-sized column.
  const heading = (
    <h1
      className={cx(
        "text-center text-[28px] font-normal tracking-tight transition-colors sm:text-[32px]",
        wide && "lg:col-start-1 lg:row-start-1 lg:self-end lg:text-left lg:text-[44px] lg:leading-tight",
        isNight ? "text-white" : "text-[#031b08]",
      )}
    >
      {resolvedTitle}
    </h1>
  );

  const picture = (
    <div
      className={cx(
        "w-full overflow-hidden rounded-[28px] transition-colors sm:rounded-[32px]",
        wide && "lg:col-start-2 lg:row-span-2 lg:row-start-1",
        isNight ? "bg-[#071512]" : "bg-go-canvas",
      )}
    >
      {code !== null && code !== undefined && (
        <div className="pt-6 pb-1 text-center select-none sm:pt-7">
          <span
            className={cx(
              "text-[72px] leading-none font-normal tracking-tight transition-colors sm:text-[80px]",
              wide && "lg:text-[112px]",
              isNight ? "text-white" : "text-[#031b08]",
            )}
          >
            {code}
          </span>
        </div>
      )}
      <img
        src={isNight ? imageDark : imageLight}
        alt={typeof code === "number" || typeof code === "string" ? `Error ${code}` : resolvedTitle}
        className="pointer-events-none block h-auto w-full select-none"
        width={786}
        height={443}
        loading="eager"
        decoding="async"
      />
    </div>
  );

  const words = (
    <div className={cx("flex w-full flex-col items-center gap-7 text-center sm:gap-8", wide && "lg:col-start-1 lg:row-start-2 lg:items-start lg:self-start lg:text-left")}>
      <p
        className={cx(
          "px-2 text-[16px] leading-relaxed font-normal whitespace-pre-line transition-colors sm:text-[17px]",
          wide && "lg:px-0 lg:text-[18px]",
          isNight ? "text-[#edf5f0]" : "text-[#14231e]",
        )}
      >
        {resolvedMessage}
      </p>
      {(handleAction || secondaryAction) && (
        <div className="flex w-full flex-col items-center gap-3 lg:items-start">
          {handleAction && (
            <button
              type="button"
              onClick={handleAction}
              className={cx(
                "flex min-h-[52px] w-full items-center justify-center rounded-full text-[16px] font-medium tracking-wide shadow-sm transition-all active:scale-[0.99] sm:min-h-[56px]",
                wide && "lg:w-auto lg:min-w-[240px] lg:px-10",
                isNight ? "bg-white text-[#031a0c] hover:bg-slate-100" : "bg-[#031a0c] text-white hover:bg-[#062613]",
              )}
            >
              {resolvedActionLabel}
            </button>
          )}
          {secondaryAction}
        </div>
      )}
    </div>
  );

  if (!fullScreen) {
    return (
      <section role="alert" aria-live="assertive" className={cx("flex w-full justify-center p-4 font-go", isNight && "go-dark", className)}>
        <div className="flex w-full max-w-[390px] flex-col items-center gap-6">
          {heading}
          {picture}
          {words}
        </div>
      </section>
    );
  }

  return (
    <main
      role="alert"
      aria-live="assertive"
      className={cx(
        "relative flex min-h-dvh w-full items-center justify-center px-4 py-16 font-go transition-colors sm:px-8",
        isNight ? "go-dark bg-[#071512]" : "bg-go-canvas",
        className,
      )}
    >
      {showThemeToggle && (
        <div className="absolute top-4 right-4 z-20 sm:top-6 sm:right-6">
          <ThemeToggle dark={isNight} onToggle={toggle} />
        </div>
      )}
      <div className="flex w-full max-w-[390px] flex-col items-center gap-6 sm:max-w-[520px] lg:grid lg:max-w-[1080px] lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)] lg:items-center lg:gap-x-16 lg:gap-y-6">
        {heading}
        {picture}
        {words}
      </div>
    </main>
  );
}
