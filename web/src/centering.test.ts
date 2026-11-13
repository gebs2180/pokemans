import { describe, expect, it } from 'vitest'
import type { GraderStandard } from './api'
import { bestGrade, faceShares, ratio, worstShare } from './centering'

const psa: GraderStandard = {
  grader: 'PSA', source: 'psa', lowest: '1',
  allowances: [
    { grade: '10', maxFrontShare: 55, maxBackShare: 75, unsure: null },
    { grade: '9', maxFrontShare: 60, maxBackShare: 90, unsure: null },
    { grade: '8', maxFrontShare: 65, maxBackShare: 90, unsure: null },
  ],
}

describe('centering helpers', () => {
  it('writes a pair as graders do, and gives nothing for a missing side', () => {
    expect(ratio(2.75, 2.25)).toBe('55.0/45.0')
    expect(ratio(null, 2)).toBeNull()
    expect(ratio(0, 0)).toBeNull()
  })

  it('takes the worse pair, and ignores a pair with a side missing', () => {
    expect(worstShare({ left: 2, right: 3, top: 2.5, bottom: 2.5 })).toBeCloseTo(60)
    expect(worstShare({ left: 3, right: 2, top: 2.2, bottom: 1.8 })).toBeCloseTo(60)
    expect(worstShare({ left: null, right: 3, top: 2, bottom: 3 })).toBeCloseTo(60)
    expect(worstShare({ left: null, right: 3, top: 2, bottom: null })).toBeNull()
  })

  it('gives the first grade both faces fit, the lowest past the table, and nothing unmeasured', () => {
    expect(bestGrade(psa, 55, 75)).toBe('10')
    expect(bestGrade(psa, 55, 76)).toBe('9')
    expect(bestGrade(psa, 60.0000000001, null)).toBe('9')
    expect(bestGrade(psa, null, 80)).toBe('9')
    expect(bestGrade(psa, 70, null)).toBe('1')
    expect(bestGrade(psa, null, null)).toBeNull()
  })

  it('puts a one-face reading on its face', () => {
    expect(faceShares('FRONT', 58)).toEqual({ front: 58, back: null })
    expect(faceShares('BACK', 58)).toEqual({ front: null, back: 58 })
  })
})
