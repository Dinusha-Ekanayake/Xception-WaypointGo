"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { LANGS, translate, type Lang, type Translate, type Vars } from "./data/strings.ts";

export type { Lang, Translate };

const STORAGE_KEY = "waypoint.loader.lang";

// The loader's language choice, kept per device. Strings are in data/strings.ts.

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
    document.documentElement.lang = LANGS.find((l) => l.value === lang)!.html;
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

export function useT(): Translate {
  const { lang } = useContext(LangContext);
  return useCallback((english: string, vars?: Vars) => translate(lang, english, vars), [lang]);
}
