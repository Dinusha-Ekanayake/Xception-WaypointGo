// The planning rules in words people read. A rule id such as R-PLN-06 is for the
// documents, the audit log and the API: the screen says what stopped the order
// (docs/architecture/GLOSSARY.md, "Codes shown to people"). The ids are the ones
// the engine and the override path name in `bindingRule` and in each check.

export const RULE_LABEL: Record<string, string> = {
  "R-FLT-03": "Vehicle in the workshop",
  "R-PLN-01": "One brand and district per trip",
  "R-PLN-02": "Needs a refrigerated vehicle",
  "R-PLN-03": "Van only outlet",
  "R-PLN-04": "Another depot's order",
  "R-PLN-05": "An order is not split",
  "R-PLN-06": "No room on the vehicle",
  "R-PLN-07": "Too many trips for the day",
  "R-PLN-09": "Fresh time budget",
  "R-PLN-10": "Style and Tech time budget",
  "R-PLN-12": "No travel time for the district",
  "R-PLN-13": "Delivery window missed",
  "R-PLN-16": "Weekly fuel used up",
  "R-PLN-19": "Decided by the dispatcher",
  "R-PLN-22": "Larger than any vehicle",
  "R-PLN-29": "Outlet and mall window missed",
  "R-PLN-30": "Window shorter than the service time",
  "R-PLN-31": "One temperature per trip",
  "R-LOD-09": "No vehicle could take the trip",
  "PLN-07": "Arrived after the plan was made",
  "R-PLN-ENGINE-TIMEOUT": "The planner ran out of time",
};

/** The rule in words; an unknown id reads as "Another rule" rather than as a code. */
export function ruleLabel(ruleId: string | null | undefined): string {
  if (!ruleId) return "";
  return RULE_LABEL[ruleId] ?? "Another rule";
}

/** The same rules when they pass, for a check shown as met ("✓ Fits on the vehicle"). */
export const RULE_PASSED_LABEL: Record<string, string> = {
  "R-FLT-03": "Vehicle available",
  "R-PLN-01": "One brand and district",
  "R-PLN-02": "Refrigerated where needed",
  "R-PLN-03": "Van-only outlets on a van",
  "R-PLN-04": "All from this depot",
  "R-PLN-05": "No order split",
  "R-PLN-06": "Fits on the vehicle",
  "R-PLN-07": "Trips fit the day",
  "R-PLN-09": "Within the Fresh time budget",
  "R-PLN-10": "Within the Style and Tech time budget",
  "R-PLN-12": "Travel times known",
  "R-PLN-13": "Delivery windows met",
  "R-PLN-16": "Weekly fuel within quota",
  "R-PLN-22": "Fits a vehicle",
  "R-PLN-29": "Mall windows met",
  "R-PLN-30": "Windows long enough to serve",
  "R-PLN-31": "One temperature per trip",
};

/** A check in words: what it met when it passed, what stopped it when it failed. */
export function checkLabel(ruleId: string, passed: boolean): string {
  return passed ? (RULE_PASSED_LABEL[ruleId] ?? "Rule met") : ruleLabel(ruleId);
}
