"use client";

import { useState, type ReactNode } from "react";
import {
  Card,
  CardHead,
  ConnectionStatus,
  FilterTabs,
  ICON_NAMES,
  Icon,
  KpiCard,
  LinkAction,
  Notice,
  Pending,
  Pill,
  PrimaryButton,
  SecondaryButton,
  Segmented,
  Sheet,
  StatTile,
  type Tone,
} from "@shared/ui";

// Every export of @shared/ui on one page, light and dark side by side, so a
// screen is built from what exists instead of a near copy (issue #28). The
// route answers 404 in a production build (app/gallery/page.tsx).

const TONES: Tone[] = ["neutral", "muted", "success", "warning", "danger", "info", "mint"];

function Section({ title, children }: { title: string; children: ReactNode }): React.JSX.Element {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-[13px] font-medium tracking-wide text-go-muted uppercase">{title}</h2>
      <div className="flex flex-wrap items-start gap-3">{children}</div>
    </section>
  );
}

function Components(): React.JSX.Element {
  const [tab, setTab] = useState<"all" | "late" | "done">("all");
  const [view, setView] = useState<"day" | "week">("day");
  const [sheet, setSheet] = useState(false);

  return (
    <div className="flex flex-col gap-8">
      <Section title="Icon">
        {ICON_NAMES.map((name) => (
          <span key={name} className="flex w-20 flex-col items-center gap-1 text-[11px] text-go-muted">
            <span className="text-go-ink"><Icon name={name} /></span>
            {name}
          </span>
        ))}
      </Section>
      <Section title="Pill">
        {TONES.map((tone) => (
          <Pill key={tone} tone={tone}>{tone}</Pill>
        ))}
      </Section>
      <Section title="Buttons">
        <PrimaryButton>Primary</PrimaryButton>
        <SecondaryButton>Secondary</SecondaryButton>
        <PrimaryButton disabled>Disabled</PrimaryButton>
        <LinkAction onClick={() => undefined}>Link action</LinkAction>
      </Section>
      <Section title="Filter tabs and segmented">
        <FilterTabs
          label="Example filter"
          value={tab}
          onChange={setTab}
          options={[{ value: "all", label: "All" }, { value: "late", label: "Late" }, { value: "done", label: "Done" }]}
        />
        <Segmented
          label="Example period"
          value={view}
          onChange={setView}
          options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }]}
        />
      </Section>
      <Section title="Card, KPI and stat">
        <div className="w-[320px]">
          <Card label="Example card">
            <CardHead title="Card head" meta="Meta line" action={<LinkAction onClick={() => undefined}>Action</LinkAction>} />
            <p className="text-[14px] text-go-muted">Card body.</p>
          </Card>
        </div>
        <div className="w-[220px]"><KpiCard label="On-time" value="94%" note="Last 7 days" /></div>
        <div className="w-[220px]"><StatTile label="Orders" value="128" note="due today" /></div>
      </Section>
      <Section title="Notice and pending">
        {(["danger", "warning", "info", "neutral"] as const).map((tone) => (
          <div key={tone} className="w-[320px]">
            <Notice tone={tone} title={`${tone} notice`}>Second line.</Notice>
          </div>
        ))}
        <div className="w-[320px]"><Pending what="Forecast" waitingOn="#16" /></div>
      </Section>
      <Section title="Connection status">
        <ConnectionStatus online lastSyncedAt={new Date()} offlineNote="Saved on this device" />
        <ConnectionStatus online={false} lastSyncedAt={null} offlineNote="Saved on this device" />
      </Section>
      <Section title="Sheet">
        <SecondaryButton onClick={() => setSheet(true)}>Open sheet</SecondaryButton>
        {sheet && (
          <Sheet label="Example sheet" onClose={() => setSheet(false)}>
            <p className="text-[15px]">Sheet content.</p>
            <PrimaryButton onClick={() => setSheet(false)}>Close</PrimaryButton>
          </Sheet>
        )}
      </Section>
    </div>
  );
}

export default function GalleryRoute(): React.JSX.Element {
  return (
    <main className="grid min-h-dvh grid-cols-1 font-go xl:grid-cols-2">
      <div className="bg-go-canvas p-6 text-go-ink">
        <h1 className="mb-6 text-[28px] font-semibold">Components · light</h1>
        <Components />
      </div>
      <div className="go-dark bg-go-canvas p-6 text-go-ink">
        <h1 className="mb-6 text-[28px] font-semibold">Components · dark</h1>
        <Components />
      </div>
    </main>
  );
}
