"use client";

import { createContext, useCallback, useContext, useMemo, useEffect, useState, type ReactNode } from "react";
import { translate, type Lang, type Translate, type Vars } from "./data/strings.ts";

export type { Lang, Translate };

// The key Settings already keeps the device's language under, so a choice made
// before the store had translations still holds.
const STORAGE_KEY = "waypoint.lang";

// The store manager's language choice, kept per device. Strings are in data/strings.ts.

const LangContext = createContext<{ lang: Lang; setLang: (lang: Lang) => void }>({ lang: "en", setLang: () => {} });

function stored(): Lang {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === "si" || value === "ta" ? value : "en";
  } catch {
    return "en";
  }
}

export function LangProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [lang, setLangState] = useState<Lang>("en");
  useEffect(() => setLangState(stored()), []);
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  const setLang = useCallback((next: Lang) => {
    setLangState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private mode or blocked storage: the choice lasts until reload.
    }
  }, []);
  return <LangContext.Provider value={{ lang, setLang }}>{children}</LangContext.Provider>;
}

export function useLang(): { lang: Lang; setLang: (lang: Lang) => void } {
  return useContext(LangContext);
}

/** "12 h 29 m left", or "closed for today", in the manager's language. */
export function cutoffWords(ms: number, t: Translate): string {
  if (ms <= 0) return t("closed for today");
  const m = Math.floor(ms / 60_000);
  return m >= 60 ? t("{h} h {m} m left", { h: Math.floor(m / 60), m: m % 60 }) : t("{m} m left", { m });
}

export function useT(): Translate {
  const { lang } = useContext(LangContext);
  return useCallback((english: string, vars?: Vars) => translate(lang, english, vars), [lang]);
}

/**
 * "1 unit", "5 units", "3 product lines" in the manager's language (roadmap
 * FF-00). The shared wording writes these in English; the store's screens use
 * these instead.
 */
export function useCounts(): { units: (n: number) => string; productLines: (n: number) => string } {
  const t = useT();
  return useMemo(
    () => ({
      units: (n: number) => (n === 1 ? t("1 unit") : t("{n} units", { n })),
      productLines: (n: number) => (n === 1 ? t("1 product line") : t("{n} product lines", { n })),
    }),
    [t],
  );
}
