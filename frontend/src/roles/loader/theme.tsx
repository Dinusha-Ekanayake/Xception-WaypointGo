"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

// The loader's appearance, Figma "08 Loader · Phone" Settings: Light or Dark,
// kept per device like the language. Dark uses the shared go-dark tokens
// (src/shared/ui/theme.css), so screens built on go- tokens need no second set
// of classes.

export type Theme = "light" | "dark";

const STORAGE_KEY = "waypoint.loader.theme";

const ThemeContext = createContext<{ theme: Theme; setTheme: (theme: Theme) => void }>({
  theme: "light",
  setTheme: () => {},
});

function stored(): Theme {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export function ThemeProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [theme, setThemeState] = useState<Theme>("light");
  useEffect(() => setThemeState(stored()), []);
  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private mode or blocked storage: the choice lasts until reload.
    }
  }, []);
  return <ThemeContext.Provider value={{ theme, setTheme }}>{children}</ThemeContext.Provider>;
}

export function useTheme(): { theme: Theme; setTheme: (theme: Theme) => void } {
  return useContext(ThemeContext);
}
