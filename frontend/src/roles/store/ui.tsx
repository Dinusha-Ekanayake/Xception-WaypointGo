import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { Icon, PRESS, ShellActions, Spinner, cx, useOverlay, useSheetDrag, type IconName } from "@shared/ui";
import type { OutletView } from "@shared/domain/types";
import AccountMenu from "./AccountMenu.tsx";
import { dockLabel, type StatusTone } from "./data/format.ts";

// Store pieces from Figma "15 Store Manager · Mobile". Touch targets are at
// least 48px; the manager works at the counter, often one handed.

const CHIP: Record<StatusTone, string> = {
  mint: "bg-go-mint text-black",
  ink: "bg-[#031a0c] text-white",
  warn: "bg-go-warning-tint text-go-warning-text",
  danger: "bg-go-danger-tint text-go-danger-strong",
  muted: "bg-go-canvas text-go-muted",
  ok: "bg-[#e3f8ee] text-go-success",
};

export function Chip({ children, tone = "mint", outline }: { children: ReactNode; tone?: StatusTone; outline?: boolean }): React.JSX.Element {
  return (
    <span
      className={cx(
        "inline-flex shrink-0 items-center rounded-full px-2.5 py-[5px] text-[13px] font-medium whitespace-nowrap",
        outline ? "border border-[#031b08] bg-white text-black" : CHIP[tone],
      )}
    >
      {children}
    </span>
  );
}

type ButtonTone = "mint" | "ink" | "plain" | "danger";
const BUTTON: Record<ButtonTone, string> = {
  mint: "bg-go-mint text-black",
  ink: "bg-[#031a0c] text-white",
  plain: "border border-go-mint bg-white text-black",
  danger: "bg-[#ea2525] text-white",
};

export function Button({
  children,
  tone = "ink",
  onClick,
  disabled,
  large,
  busy = false,
}: {
  children: ReactNode;
  tone?: ButtonTone;
  onClick?: () => void;
  disabled?: boolean;
  large?: boolean;
  /** The command is on its way: disabled, with a spinner before the label. */
  busy?: boolean;
}): React.JSX.Element {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cx(
        "flex w-full min-w-0 items-center justify-center gap-2 rounded-[22px] px-4 font-medium disabled:cursor-not-allowed disabled:opacity-50",
        busy && "disabled:cursor-wait disabled:opacity-70",
        large ? "min-h-16 text-[20px]" : "min-h-12 text-[15px]",
        BUTTON[tone],
        PRESS,
      )}
    >
      {busy && <Spinner />}
      {children}
    </button>
  );
}

export function Card({ children, label, className, style }: { children: ReactNode; label?: string; className?: string; style?: CSSProperties }): React.JSX.Element {
  return (
    <section aria-label={label} className={cx("flex w-full flex-col gap-3.5 rounded-[26px] bg-white p-[18px]", className)} style={style}>
      {children}
    </section>
  );
}

export function Muted({ children }: { children: ReactNode }): React.JSX.Element {
  return <p className="text-[13px] font-light text-go-muted">{children}</p>;
}

/** Minus, count, plus. The count turns teal when it differs from the usual. */
export function Stepper({ value, onChange, label, highlight }: { value: number; onChange: (n: number) => void; label: string; highlight?: boolean }): React.JSX.Element {
  const round = "flex size-12 shrink-0 items-center justify-center rounded-full bg-go-canvas text-[22px] leading-none disabled:opacity-40";
  return (
    <div className="flex items-center gap-1" role="group" aria-label={label}>
      <button type="button" className={round} aria-label={`One less ${label}`} disabled={value <= 0} onClick={() => onChange(Math.max(0, value - 1))}>
        −
      </button>
      <span aria-live="polite" className={cx("w-9 text-center text-[17px] font-semibold", highlight ? "text-[#0f766e]" : "text-black")}>
        {value}
      </span>
      <button type="button" className={round} aria-label={`One more ${label}`} onClick={() => onChange(value + 1)}>
        +
      </button>
    </div>
  );
}

/** A bottom sheet over a blurred page, as in "04 Order sent". Escape, focus and motion come from useOverlay; a phone can swipe it away. */
export function Sheet({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  useSheetDrag(panel, onClose);
  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-40 flex items-end justify-center md:items-center md:p-6" role="presentation">
      <button type="button" aria-label="Close" tabIndex={-1} onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/20 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="go-panel go-panel-sheet relative flex max-h-[92dvh] w-full max-w-[520px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-white px-6 pt-6 pb-8 outline-none md:max-h-[85dvh] md:rounded-[32px]"
      >
        {children}
      </div>
    </div>
  );
}

/**
 * A centred dialog over a blurred page: "04 Order sent", "06b Enter PIN" and
 * "07 Delivery confirmed". Phones still get the bottom {@link Sheet}; this is
 * for the desktop layout, and falls back to a full-width card on a phone.
 * Without onClose it cannot be dismissed (a step that must be finished).
 */
export function Modal({ label, onClose, children }: { label: string; onClose?: () => void; children: ReactNode }): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-40 flex items-center justify-center p-4" role="presentation">
      <button type="button" aria-label="Close" tabIndex={-1} onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/20 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="go-panel go-panel-dialog relative flex max-h-[92dvh] w-full max-w-[520px] flex-col gap-4 overflow-y-auto rounded-[32px] bg-white px-7 pt-8 pb-7 outline-none"
      >
        {children}
      </div>
    </div>
  );
}

/** A panel from the right over a blurred page, as in "05b make-up delivery"; a bottom sheet on a phone. */
export function Drawer({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  useSheetDrag(panel, onClose);
  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-40 flex items-end justify-center lg:items-stretch lg:justify-end" role="presentation">
      <button type="button" aria-label="Close" tabIndex={-1} onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/20 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="go-panel go-panel-drawer relative flex max-h-[92dvh] w-full flex-col gap-5 overflow-y-auto rounded-t-[32px] bg-white px-6 pt-6 pb-8 outline-none lg:max-h-none lg:w-[440px] lg:rounded-t-none lg:rounded-l-[32px] lg:pt-7"
      >
        {children}
      </div>
    </div>
  );
}

/** The round mint badge that heads a Figma dialog. */
export function Badge({ children }: { children: ReactNode }): React.JSX.Element {
  return <span className="mx-auto flex size-[60px] items-center justify-center rounded-full bg-go-mint/60">{children}</span>;
}

/** Label left, value right, on a pale card: the rows of "04 Order sent" and "07 Delivery confirmed". */
export function Facts({ rows }: { rows: { label: string; value: ReactNode; strong?: boolean }[] }): React.JSX.Element {
  return (
    <dl className="flex flex-col gap-2.5 rounded-[16px] bg-go-canvas px-4 py-4 text-[14px]">
      {rows.map((r) => (
        <div key={r.label} className="flex justify-between gap-3">
          <dt className="text-go-muted">{r.label}</dt>
          <dd className={cx("text-right", r.strong === false ? "text-black" : "font-semibold text-black")}>{r.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * A dark note that says what just happened, as in "03d Item added" and "03e
 * Draft saved". The live region stays mounted so a screen reader hears each
 * new message; the note itself shows only while there is one.
 */
export function Toast({ note }: { note: { title: string; detail?: string; tone?: "ok" | "danger" } | null }): React.JSX.Element {
  return (
    <div role="status" aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-[260px] z-40 flex justify-center px-4 lg:bottom-8 lg:pl-[260px]">
      {note && (
        <div className="flex max-w-[420px] animate-rise-in items-center gap-3 rounded-[20px] bg-[#031a0c] px-5 py-3 text-white shadow-[0_8px_20px_rgba(0,0,0,0.18)]">
          <span aria-hidden className={cx("size-2 shrink-0 rounded-full", note.tone === "danger" ? "bg-go-danger" : "bg-[#22c55e]")} />
          <span className="flex min-w-0 flex-col">
            <span className="text-[15px] font-medium">{note.title}</span>
            {note.detail && <span className="text-[13px] text-white/75">{note.detail}</span>}
          </span>
        </div>
      )}
    </div>
  );
}

export function BackButton({ onClick, label = "Back" }: { onClick: () => void; label?: string }): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className="flex min-h-12 items-center gap-2 self-start pr-3 text-[17px] text-black">
      <Icon name="arrow-left" />
      {label}
    </button>
  );
}

export type Tab = "home" | "orders" | "deliveries" | "issues";
const TABS: { id: Tab; icon: IconName; label: string }[] = [
  { id: "home", icon: "home", label: "Home" },
  { id: "orders", icon: "cart", label: "Orders" },
  { id: "deliveries", icon: "truck", label: "Deliveries" },
  { id: "issues", icon: "alert", label: "Issues" },
];

/** True once the page has moved, so the floating bar can fold smaller. */
function usePageScrolled(threshold = 24): boolean {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const read = () => setScrolled((window.scrollY || document.documentElement.scrollTop) > threshold);
    read();
    window.addEventListener("scroll", read, { passive: true });
    return () => window.removeEventListener("scroll", read);
  }, [threshold]);
  return scrolled;
}

/** "SM / Tab bar": floating, dark, the active tab on a white pill. Scrolling folds it smaller. */
export function TabBar({ tab, onTab, badges }: { tab: Tab; onTab: (t: Tab) => void; badges: Partial<Record<Tab, number>> }): React.JSX.Element {
  const compact = usePageScrolled();
  // On a phone held sideways (`short:`) the fade and the padding took nearly
  // half the screen; there the bar sits low with no fade (issue #201).
  return (
    <nav aria-label="Store" className={cx("fixed inset-x-0 bottom-0 z-30 flex justify-center lg:hidden bg-gradient-to-b from-go-canvas/0 via-go-canvas via-45% to-go-canvas px-4 transition-all duration-300 short:bg-none short:pt-0 short:pb-[max(0.5rem,env(safe-area-inset-bottom))]", compact ? "pt-4 pb-[max(0.75rem,env(safe-area-inset-bottom))]" : "pt-10 pb-[max(1.5rem,env(safe-area-inset-bottom))]")}>
      <div className={cx("flex w-full items-center justify-around rounded-[32px] bg-[#031b08] px-2 drop-shadow-[0_8px_12px_rgba(0,0,0,0.18)] transition-all duration-300 short:h-14", compact ? "h-12 max-w-[248px]" : "h-16 max-w-[324px]")}>
        {TABS.map((t) => {
          const active = t.id === tab;
          const badge = badges[t.id] ?? 0;
          return (
            <button
              key={t.id}
              type="button"
              aria-label={t.label}
              aria-current={active ? "page" : undefined}
              onClick={() => onTab(t.id)}
              className={cx("relative flex items-center justify-center rounded-[24px] transition-all duration-300", compact ? "h-9 w-12" : "h-12 w-[64px]", active && "bg-white")}
            >
              <span className={cx(!active && "opacity-75 invert")}>
                <Icon name={t.icon} />
              </span>
              {badge > 0 && (
                <span className="absolute top-0.5 right-0.5 rounded-[9px] bg-go-mint px-[5px] py-px text-[11px] font-semibold text-black">{badge}</span>
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

/**
 * "Shell / Sidebar" from "14 Store Manager · Desktop": brand, the destinations
 * with their counts, and at the foot the outlet and the person signed in, who
 * opens the account menu. From lg only; phones keep the floating tab bar.
 */
export function SideNav({
  tab,
  onTab,
  badges,
  outlet,
  displayName,
  onEditProfile,
  onEditStore,
}: {
  tab: Tab;
  onTab: (t: Tab) => void;
  badges: Partial<Record<Tab, number>>;
  outlet: OutletView | null;
  displayName: string;
  onEditProfile: () => void;
  onEditStore: () => void;
}): React.JSX.Element {
  const [account, setAccount] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <aside className="fixed inset-y-0 left-0 z-30 hidden w-[260px] flex-col gap-6 bg-white px-5 pt-8 pb-6 lg:flex">
      <div className="flex items-center gap-2.5 px-2">
        <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
        <span className="rounded-full bg-go-mint px-3 py-[5px] text-[13px] font-medium text-black">WayPoint Store</span>
      </div>
      <nav aria-label="Store" className="flex flex-col gap-1">
        {TABS.map((t) => {
          const active = t.id === tab;
          const badge = badges[t.id] ?? 0;
          return (
            <button
              key={t.id}
              type="button"
              aria-current={active ? "page" : undefined}
              onClick={() => onTab(t.id)}
              className={cx(
                "flex min-h-12 items-center gap-3 rounded-[16px] px-4 text-left text-[16px]",
                active ? "bg-[#e7f3f2] font-medium text-black" : "text-go-muted",
              )}
            >
              <Icon name={t.icon} />
              <span className="flex-1">{t.label}</span>
              {badge > 0 && (
                <span className={cx("flex size-7 items-center justify-center rounded-full text-[12px] font-semibold", t.id === "issues" ? "bg-go-danger-tint text-go-danger-strong" : "bg-go-mint text-black")}>
                  {badge}
                </span>
              )}
            </button>
          );
        })}
      </nav>
      <span className="flex-1" />
      {outlet && (
        <div className="flex flex-col gap-0.5 rounded-[16px] bg-[#e7f3f2] px-4 py-3">
          <span className="text-[13px] font-medium text-go-teal">{outlet.brandCode}</span>
          <span className="text-[16px] font-medium text-black">
            {outlet.districtName} · {outlet.outletId}
          </span>
          <span className="text-[13px] text-go-muted">
            {dockLabel(outlet.dockType)} · {outlet.windowOpen.slice(0, 5)}-{outlet.windowClose.slice(0, 5)}
          </span>
        </div>
      )}
      <div className="flex items-center gap-3">
        <button
          ref={trigger}
          type="button"
          aria-haspopup="dialog"
          aria-expanded={account}
          aria-label={`Account: ${displayName}`}
          onClick={() => setAccount((open) => !open)}
          className="flex min-h-12 min-w-0 flex-1 items-center gap-3 rounded-[16px] text-left"
        >
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-mint text-[14px] font-semibold text-black">{initials(displayName)}</span>
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-[15px] font-medium text-black">{displayName}</span>
            <span className="text-[13px] text-go-muted">Store manager</span>
          </span>
        </button>
        <ShellActions compact />
      </div>
      {account && (
        <AccountMenu
          displayName={displayName}
          initials={initials(displayName)}
          outlet={outlet}
          onEditProfile={onEditProfile}
          onEditStore={onEditStore}
          onClose={() => {
            setAccount(false);
            trigger.current?.focus();
          }}
        />
      )}
    </aside>
  );
}
