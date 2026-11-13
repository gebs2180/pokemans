import type { Rung } from './api'

/** True when the rounded "counts for" rows don't add to the price, so the slip says so rather than nudging a row. */
export function slipDiffers(rungs: Rung[], price: number) {
  const rows = rungs.reduce((sum, r) => sum + Math.round(r.probability * r.price * 100), 0)
  return rows !== Math.round(price * 100)
}
