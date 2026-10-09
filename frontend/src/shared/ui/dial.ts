// How a row sits on a scrolling list around the item in hand (the driver's
// stop card, an inbox): further away, smaller and fainter. Pure, no React.

/**
 * How a row sits on the dial: the further from the card, the smaller and
 * fainter, so the list reads like a wheel turning around the stop in hand.
 * Depth 1 is the row next to the card.
 */
export function dialStyle(depth: number): { scale: number; opacity: number } {
  const steps = Math.max(0, depth - 1);
  return { scale: Math.max(0.84, 1 - 0.04 * steps), opacity: Math.max(0.4, 1 - 0.16 * steps) };
}
