"use client";

import { createContext, useCallback, useContext, type ReactNode } from "react";
import type { AppLang } from "@shared/ui";
import { translate, type Translate, type Vars } from "./data/strings.ts";

// Roadmap FF-00: the driver's critical screens in Sinhala and Tamil. The choice
// is the one the driver already makes in Settings (useDeviceLang), which also
// picks the voice that reads messages aloud; this only makes the screens follow
// it. Strings are in data/strings.ts.

const LangContext = createContext<AppLang>("en");

export function DriverLang({ lang, children }: { lang: AppLang; children: ReactNode }): React.JSX.Element {
  return <LangContext.Provider value={lang}>{children}</LangContext.Provider>;
}

export function useDriverLang(): AppLang {
  return useContext(LangContext);
}

export function useT(): Translate {
  const lang = useContext(LangContext);
  return useCallback((english: string, vars?: Vars) => translate(lang, english, vars), [lang]);
}
