import type { Rung } from './api'

/**
 * What each grade adds to the firm price, in cents: chance times that grade's offer, rounded, with the last row taking
 * whatever the rounding left over so the rows add to the offer exactly (price = Σ p · offer, rounded once).
 */
export function slipCents(rungs: Rung[], price: number): number[] {
  const cents = rungs.map((r) => Math.round(r.probability * r.price * 100))
  if (cents.length) {
    const others = cents.slice(0, -1).reduce((a, b) => a + b, 0)
    cents[cents.length - 1] = Math.round(price * 100) - others
  }
  return cents
}
