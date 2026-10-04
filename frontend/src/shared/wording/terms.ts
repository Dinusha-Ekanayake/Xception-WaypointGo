// Counts in the glossary's words (docs/architecture/GLOSSARY.md, "Quantities").
// The order quantity is units: the data has no case or package.

/** "1 unit", "12 units". */
export const units = (n: number): string => `${n} ${n === 1 ? "unit" : "units"}`;

/** "1 product line", "3 product lines". */
export const productLines = (n: number): string => `${n} ${n === 1 ? "product line" : "product lines"}`;

/** "1 order", "4 orders". */
export const orders = (n: number): string => `${n} ${n === 1 ? "order" : "orders"}`;

/** "1 stop", "8 stops". */
export const stops = (n: number): string => `${n} ${n === 1 ? "stop" : "stops"}`;

/** "1 trip", "2 trips". */
export const trips = (n: number): string => `${n} ${n === 1 ? "trip" : "trips"}`;
