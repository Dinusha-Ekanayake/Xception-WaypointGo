/**
 * The small turning ring a button shows while its command is on the way. It
 * takes the button's text colour, so it works on every tone and in the dark
 * theme. Under reduced motion it stops turning and stays as a ring.
 */
export function Spinner({ className = "size-4" }: { className?: string }): React.JSX.Element {
  return <span aria-hidden className={`inline-block shrink-0 animate-spin rounded-full border-2 border-current border-r-transparent ${className}`} />;
}

/**
 * How every button answers a press, in every role: it gives a little under the
 * finger at once (150 ms), and on a mouse it dims slightly on hover. A
 * disabled button does neither.
 */
export const PRESS =
  "transition-[transform,opacity] duration-150 ease-go-out enabled:active:scale-[0.97] [@media(hover:hover)]:enabled:hover:opacity-90";
