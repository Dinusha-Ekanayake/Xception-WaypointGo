import { flushSync } from "react-dom";
import { reducedMotion } from "./useOverlay.ts";

/**
 * Screen changes through the browser's View Transitions: a 200 ms crossfade
 * between tabs, plus a 12 px slide when drilling into a detail ("forward") or
 * back out of it ("back"). The update itself runs at once inside the
 * transition, so nothing waits on the animation. Without support, or under
 * reduced motion, the update runs directly, exactly as before. The CSS is at
 * the end of theme.css.
 */

export type TransitionDirection = "tab" | "forward" | "back";

type ViewTransitionLike = { finished: Promise<unknown> };
type TransitionDocument = Document & { startViewTransition?: (update: () => void) => ViewTransitionLike };

let latest = 0;

export function withTransition(update: () => void, direction: TransitionDirection = "tab"): void {
  if (typeof document === "undefined" || reducedMotion()) {
    update();
    return;
  }
  const doc = document as TransitionDocument;
  if (!("startViewTransition" in doc) || typeof doc.startViewTransition !== "function") {
    update();
    return;
  }
  const root = doc.documentElement;
  const mine = ++latest;
  root.dataset.vt = direction;
  const transition = doc.startViewTransition(() => flushSync(update));
  // A newer transition skips this one; only the newest clears the direction.
  const clear = () => {
    if (mine === latest) delete root.dataset.vt;
  };
  transition.finished.then(clear, clear);
}
