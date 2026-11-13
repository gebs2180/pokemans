import type { DealProgress, ShippingLabel } from './api'
import { shortDate as date } from './format'

export type StepState = 'done' | 'current' | 'upcoming'
export type Step = { key: string; title: string; detail?: string; state: StepState }

/**
 * The stages of a sale in order, each done, current (the first not done) or upcoming, from what the API already says.
 * Returned and cancelled sales have their own notices instead of a timeline.
 *
 * @param who whose view it is: the seller ships and is paid, the buyer pays
 * @param accepted when the buyer accepted
 * @param payWithin how long the buyer has to pay once it arrives, in words ("7 days")
 */
export function dealSteps({
  who,
  accepted,
  label,
  progress,
  payWithin,
}: {
  who: 'seller' | 'buyer'
  accepted: string
  label: ShippingLabel | null
  progress: DealProgress | null
  payWithin: string
}): Step[] {
  const shipped = !!progress?.shippedAt || (!!progress && progress.stage !== 'SOLD')
  const arrived = !!progress?.arrivedAt
  const paid = !!progress?.paidAt || !!progress?.confirmedAt
  const seller = who === 'seller'

  const raw: (Omit<Step, 'state'> & { done: boolean })[] = []
  const add = (done: boolean, key: string, title: string, detail?: string) => raw.push({ done, key, title, detail })

  add(true, 'accepted', seller ? 'Buyer accepted' : 'You accepted', date(accepted))
  add(!!label || shipped, 'label', 'Label ready', label ? date(label.boughtAt) : undefined)
  add(
    shipped,
    'ship',
    // The seller ships; the buyer waits for it. Each role gets its own words.
    shipped
      ? 'Shipped'
      : seller
        ? label
          ? `Ship it by ${date(label.shipBy)}`
          : 'Ship it'
        : label
          ? `Seller ships by ${date(label.shipBy)}`
          : 'Seller ships',
    progress?.shippedAt ? date(progress.shippedAt) : undefined,
  )
  add(arrived, 'arrive', arrived ? 'Arrived' : 'Arrives', progress?.arrivedAt ? date(progress.arrivedAt) : undefined)
  add(
    paid,
    'pay',
    paid
      ? seller
        ? 'Buyer paid you'
        : 'You paid the seller'
      : seller
        ? `Buyer pays you within ${payWithin}`
        : `You pay the seller within ${payWithin}`,
    progress?.confirmedAt
      ? `${date(progress.confirmedAt)}, confirmed`
      : progress?.paidAt
        ? date(progress.paidAt)
        : progress?.payBy
          ? `By ${date(progress.payBy)}`
          : undefined,
  )

  let currentTaken = false
  return raw.map(({ done, ...step }) => {
    if (done) return { ...step, state: 'done' as const }
    if (!currentTaken) {
      currentTaken = true
      return { ...step, state: 'current' as const }
    }
    return { ...step, state: 'upcoming' as const }
  })
}
