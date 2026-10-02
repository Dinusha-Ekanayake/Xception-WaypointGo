// Icons exported from the Figma file into public/icons/go. Each one keeps the
// box and inset it has in the design, so a glyph sits where the designer put it
// rather than stretched to its box.

type IconSpec = { box: number; inset?: string; inner?: string };

const ICONS = {
  grid: { box: 20 },
  box: { box: 20 },
  plan: { box: 20 },
  live: { box: 20 },
  alert: { box: 20 },
  truck: { box: 20, inset: "18.75% 8.33% 13.75% 8.33%", inner: "-5.56% -4.51% -5.57% -4.5%" },
  "chart-line": { box: 20, inset: "5%" },
  bell: { box: 20, inset: "0.5%" },
  "chevron-right": { box: 14, inset: "5% 21.88%" },
  "chevron-right-muted": { box: 13, inset: "5% 21.88%" },
  "chevron-down": { box: 14, inset: "5%" },
  search: { box: 14, inset: "5%" },
  snowflake: { box: 11, inset: "5% 10.62% 5% 10.63%" },
  close: { box: 16, inset: "5% 16.25%" },
  "gas-pump": { box: 15, inset: "5%" },
  "wrench-outline": { box: 16 },
  board: { box: 16 },
  "dot-online": { box: 8 },
  // Loader (Figma "08 Loader · Phone").
  "arrow-left": { box: 22, inset: "5% 10.62% 5% 10.63%" },
  "arrow-right": { box: 16, inset: "5% 10.62% 5% 10.63%" },
  check: { box: 18 },
  "check-white": { box: 16 },
  clock: { box: 18 },
  hand: { box: 16 },
  lock: { box: 16 },
  "switch-user": { box: 20 },
  triangle: { box: 18, inset: "5%" },
  loading: { box: 18 },
  dock: { box: 20 },
  "truck-go": { box: 18 },
  chilled: { box: 16 },
  // Store manager (Figma "15 Store Manager · Mobile").
  home: { box: 18 },
  cart: { box: 20 },
} satisfies Record<string, IconSpec>;

export type IconName = keyof typeof ICONS;

/** Every icon, for the development gallery. */
export const ICON_NAMES = Object.keys(ICONS) as IconName[];

export function Icon({ name, label }: { name: IconName; label?: string }): React.JSX.Element {
  const spec: IconSpec = ICONS[name];
  const img = (
    <img alt="" src={`/icons/go/${name}.svg`} className="absolute inset-0 block size-full max-w-none" />
  );
  return (
    <span
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      className="relative inline-block shrink-0 overflow-hidden"
      style={{ width: spec.box, height: spec.box }}
    >
      {spec.inset ? (
        <span className="absolute" style={{ inset: spec.inset }}>
          {spec.inner ? (
            <span className="absolute" style={{ inset: spec.inner }}>
              {img}
            </span>
          ) : (
            img
          )}
        </span>
      ) : (
        img
      )}
    </span>
  );
}
