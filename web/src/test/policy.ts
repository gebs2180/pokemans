import type { Policy } from '../policy'

/** The beta's numbers, as parameter set 1.4.0 and the default offers properties give them. */
export const testPolicy: Policy = {
  minFmv: 25,
  maxPrice: 5000,
  offerValidDays: 7,
  listingOpenDays: 14,
  shipWithinDays: 3,
  payWithinDays: 7,
  margins: { A: 0.1, B: 0.13, C: 0.17 },
}
