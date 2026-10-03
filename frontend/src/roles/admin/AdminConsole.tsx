"use client";

import { useEffect, useState } from "react";
import AccessDemo from "./access/AccessDemo";
import AssistantsConsole from "./assistants/AssistantsConsole";

// The admin role's two surfaces: the access console (sample data until #22)
// and the live AI assistants console (issue #177), chosen by #assistants in the
// address so a link opens the right one.

type View = "access" | "assistants";
const viewOf = (): View => (typeof window !== "undefined" && window.location.hash.startsWith("#assistants") ? "assistants" : "access");

export default function AdminConsole(): React.JSX.Element {
  const [view, setView] = useState<View>("access");
  useEffect(() => {
    const sync = () => setView(viewOf());
    sync();
    window.addEventListener("hashchange", sync);
    return () => window.removeEventListener("hashchange", sync);
  }, []);
  const tab = (target: View, label: string) => (
    <a href={target === "assistants" ? "#assistants" : "#people"} aria-current={view === target ? "page" : undefined}
      className={`min-h-11 rounded-full px-4 py-2.5 text-sm font-semibold ${view === target ? "bg-[#006b57] text-white" : "text-[#1a3a2e] hover:bg-[#edf8f5]"}`}>
      {label}
    </a>
  );
  return (
    <>
      <nav aria-label="Admin" className="mx-auto flex max-w-5xl gap-2 px-4 pt-4 sm:px-6">
        {tab("access", "Access")}
        {tab("assistants", "AI assistants")}
      </nav>
      {view === "assistants" ? <AssistantsConsole /> : <AccessDemo />}
    </>
  );
}
