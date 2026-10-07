// The TypeScript half of the ledger. R-005 lives here.

/** R-005: the running total of a list of amounts. */
export const total = (amounts: readonly number[]): number =>
  amounts.reduce((running, amount) => running + amount, 0);
