import { createContext, useContext } from 'react'

/**
 * The numbers the offer terms are written around (GET /api/policy), so the copy never drifts from what the server does
 * (review finding 47). Public: the how-it-works page shows them before anyone signs in.
 */
export type Policy = {
  /** The smallest FMV that gets an offer, in dollars. */
  minFmv: number
  /** The highest firm price we offer, in dollars. */
  maxPrice: number
  offerValidDays: number
  listingOpenDays: number
  shipWithinDays: number
  payWithinDays: number
  /** The buyer's margin by confidence, A to C, as a share. */
  margins: Record<string, number>
}

export const PolicyContext = createContext<Policy | null>(null)

export function usePolicy(): Policy {
  const policy = useContext(PolicyContext)
  if (!policy) throw new Error('usePolicy needs a PolicyProvider')
  return policy
}

/** A whole-dollar amount for copy, e.g. "$5,000". */
export function dollars(amount: number) {
  return amount.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })
}

/** "7 days", or "1 day". */
export function days(n: number) {
  return n === 1 ? '1 day' : `${n} days`
}
