// The JavaScript half of the fixture. R-006 lives here. It shares src/ with a TypeScript file, and the
// two adapters divide them by extension rather than one of them stepping aside for the other.

/** R-006: formats an amount in minor units as a decimal string. */
export const format = (minorUnits) => (minorUnits / 100).toFixed(2);
