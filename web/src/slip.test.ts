import { describe, expect, it } from 'vitest'
import { slipDiffers } from './slip'

describe('slipDiffers', () => {
  it('is false when the rounded rows add to the price', () => {
    const rungs = [
      { grade: '10', probability: 0.5, price: 100 },
      { grade: '9', probability: 0.5, price: 50 },
    ]
    expect(slipDiffers(rungs, 75)).toBe(false)
  })

  it('is true for the Victini rows, where 30% of $337.25 is $101.175 and rounds up', () => {
    const rungs = [
      { grade: '10', probability: 0.6, price: 843.12 },
      { grade: '9', probability: 0.3, price: 337.25 },
      { grade: '8', probability: 0.1, price: 290.78 },
    ]
    expect(slipDiffers(rungs, 636.12)).toBe(true)
  })

  it('is true when they are off at all, even by a cent', () => {
    expect(slipDiffers([{ grade: '10', probability: 1, price: 100 }], 100.05)).toBe(true)
  })
})
