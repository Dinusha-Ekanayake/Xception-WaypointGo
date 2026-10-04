import type { AllocationView, PlacementView } from "@shared/domain/types";
import { checkLabel, ruleLabel } from "../../../shared/wording/index.ts";

// Why an order was not placed, as one explanation a dispatcher can read out to a
// store (issue #267). Nothing is worked out here: the rule, its reason, every
// check and every place the order could take come from the planning module, so
// there is still one definition of every rule. This only arranges those facts,
// in the same order every time, with no model and nothing stored.

export type Explanation = {
  headline: string;
  /** The deciding rule in words, and the server's reason for it. */
  rule: string;
  reason: string;
  /** The checks that failed, each with what the server said. */
  stopped: Array<{ label: string; detail: string }>;
  /** The checks that passed, in words. */
  met: string[];
  /** Where it could still go; null while the places are still being checked. */
  fits: string[] | null;
  /** A few places it was tried and refused, with the rule that refused each. */
  refused: Array<{ where: string; why: string }>;
  next: string;
};

const REFUSED_SHOWN = 3;

const where = (place: PlacementView) => `${place.vehicleId}, trip ${place.tripNumber}`;

export function explainDeferral(input: {
  orderRef: string | null;
  outletId: string | null;
  day: string;
  allocation: Pick<AllocationView, "bindingRule" | "reason" | "checks">;
  places: PlacementView[] | null;
}): Explanation {
  const { allocation, places } = input;
  const order = input.orderRef ?? "This order";
  const fits = places === null ? null : places.filter((place) => place.feasible);
  const refused = (places ?? []).filter((place) => !place.feasible).slice(0, REFUSED_SHOWN);

  return {
    headline: `${order}${input.outletId ? ` for ${input.outletId}` : ""} was not placed on the plan for ${input.day}.`,
    rule: ruleLabel(allocation.bindingRule),
    reason: allocation.reason,
    stopped: allocation.checks.filter((check) => !check.passed).map((check) => ({ label: checkLabel(check.ruleId, false), detail: check.reason })),
    met: allocation.checks.filter((check) => check.passed).map((check) => checkLabel(check.ruleId, true)),
    fits: fits === null ? null : fits.map((place) => `${where(place)}: ${place.joins ? "joins the trip already planned" : "opens a new trip"}`),
    refused: refused.map((place) => ({ where: where(place), why: `${ruleLabel(place.bindingRule)}: ${place.reason}` })),
    next:
      fits === null
        ? "Every vehicle is still being checked."
        : fits.length > 0
          ? `It can still go on ${fits.length === 1 ? "one trip" : `${fits.length} trips`}. Choose one and give a reason, and it is placed by hand.`
          : "It fits on no trip of any available vehicle. Swap it with an order on a trip, or keep it deferred: it is offered first on the next plan.",
  };
}
