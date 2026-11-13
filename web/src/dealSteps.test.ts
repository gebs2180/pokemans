import { describe, expect, it } from 'vitest'
import type { DealProgress, ShippingLabel } from './api'
import { dealSteps } from './dealSteps'

const label: ShippingLabel = {
  carrier: 'USPS',
  service: 'GroundAdvantage',
  trackingCode: '9400111',
  labelUrl: 'https://example.com/label.pdf',
  insuredValue: 636.12,
  boughtAt: '2026-11-02T12:00:00Z',
  shipBy: '2026-11-05T12:00:00Z',
}
const progress = (over: Partial<DealProgress>): DealProgress =>
  ({ stage: 'SOLD', shippedAt: null, arrivedAt: null, payBy: null, paidAt: null, confirmedAt: null, ...over }) as DealProgress

const run = (over: Partial<Parameters<typeof dealSteps>[0]> = {}) =>
  dealSteps({ who: 'seller', accepted: '2026-11-02T11:00:00Z', label, progress: progress({}), payWithin: '7 days', ...over })
const states = (steps: ReturnType<typeof dealSteps>) => steps.map((s) => `${s.key}:${s.state}`)

describe('dealSteps', () => {
  it('has the five steps in order, with the ship-by date and the pay window', () => {
    const steps = run()
    expect(steps.map((s) => s.title)).toEqual([
      'Buyer accepted',
      'Label ready',
      expect.stringMatching(/^Ship it by /),
      'Arrives',
      'Buyer pays you within 7 days',
    ])
  })

  it('makes the first step not done the current one', () => {
    expect(states(run())).toEqual(['accepted:done', 'label:done', 'ship:current', 'arrive:upcoming', 'pay:upcoming'])
  })

  it('waits on the label when buying it failed', () => {
    expect(states(run({ label: null }))).toEqual(['accepted:done', 'label:current', 'ship:upcoming', 'arrive:upcoming', 'pay:upcoming'])
  })

  it('moves on as it ships and arrives, and shows the pay-by date', () => {
    const shipped = run({ progress: progress({ stage: 'SHIPPED', shippedAt: '2026-11-04T12:00:00Z' }) })
    expect(states(shipped)).toEqual(['accepted:done', 'label:done', 'ship:done', 'arrive:current', 'pay:upcoming'])
    const arrived = run({ progress: progress({ stage: 'ARRIVED', shippedAt: '2026-11-04T12:00:00Z', arrivedAt: '2026-11-07T12:00:00Z', payBy: '2026-11-14T12:00:00Z' }) })
    expect(states(arrived).slice(2)).toEqual(['ship:done', 'arrive:done', 'pay:current'])
    expect(arrived[4].detail).toMatch(/^By /)
  })

  it('is all done once paid, and still counts the label when the shipped label is gone', () => {
    const paid = run({
      label: null,
      progress: progress({ stage: 'PAID', shippedAt: 'x', arrivedAt: '2026-11-07T12:00:00Z', paidAt: '2026-11-08T12:00:00Z' } as Partial<DealProgress>),
    })
    expect(paid.every((s) => s.state === 'done')).toBe(true)
    expect(paid[4].title).toBe('Buyer paid you')
  })

  it('speaks to the buyer in the buyer view', () => {
    const steps = run({ who: 'buyer' })
    expect(steps[0].title).toBe('You accepted')
    expect(steps[4].title).toBe('You pay the seller within 7 days')
  })
})
