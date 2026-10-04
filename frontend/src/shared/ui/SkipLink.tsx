/**
 * "Skip to content": invisible until it has keyboard focus, then it sits at
 * the top-left corner so Tab from the browser chrome does not have to walk
 * every header control before reaching the page's own main landmark.
 */
export function SkipLink({ targetId = "main-content" }: { targetId?: string }): React.JSX.Element {
  return (
    <a
      href={`#${targetId}`}
      className="absolute -top-96 left-2 z-50 rounded-full bg-go-ink px-4 py-2 text-sm font-medium text-go-card focus:top-2"
    >
      Skip to content
    </a>
  );
}
