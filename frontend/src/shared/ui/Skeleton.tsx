/**
 * Placeholder rows while a list or card loads: the same rounded bars the rows
 * will fill, pulsing gently (still under reduced motion). A screen reader hears
 * the label, the same words the screen used to print.
 */
export function SkeletonRows({ rows = 3, label }: { rows?: number; label: string }): React.JSX.Element {
  return (
    <div role="status" className="flex w-full flex-col gap-2">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} aria-hidden className="h-12 w-full animate-pulse rounded-go-tile bg-go-surface" />
      ))}
    </div>
  );
}
