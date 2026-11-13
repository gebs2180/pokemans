import type { KeyboardEvent } from 'react'
import type { CenteringFace, GraderStandard } from './api'

export type FrameSide = 'left' | 'right' | 'top' | 'bottom'
export const SIDES: FrameSide[] = ['left', 'right', 'top', 'bottom']
/** Where a line starts: a fixed place, never the measurer's answer, which would pull the label towards it. */
export const START_PX = 50

/** Border widths of one face, in any one unit. A null side is "can't see it". */
export type Borders = { left: number | null; right: number | null; top: number | null; bottom: number | null }

/** A pair as graders write it: the first border's share, e.g. "55.0/45.0"; null if either side is missing. */
export function ratio(first: number | null, second: number | null): string | null {
  if (first == null || second == null || first + second <= 0) return null
  const share = (100 * first) / (first + second)
  return `${share.toFixed(1)}/${(100 - share).toFixed(1)}`
}

/** The larger border's share of one pair, 50 to 100, or null if either side is missing. */
function pairShare(first: number | null, second: number | null): number | null {
  if (first == null || second == null || first + second <= 0) return null
  const share = (100 * first) / (first + second)
  return Math.max(share, 100 - share)
}

/**
 * The worse pair's larger share, what graders' limits are written against. A pair with a side missing
 * doesn't count; with neither pair whole there is no share (null).
 */
export function worstShare(b: Borders): number | null {
  const shares = [pairShare(b.left, b.right), pairShare(b.top, b.bottom)].filter((s): s is number => s != null)
  return shares.length === 0 ? null : Math.max(...shares)
}

/**
 * The best grade this grader allows, the same rule as the backend's GraderCentering.best and PSA's cap with
 * no margin: a face not measured doesn't limit it, and with neither there is no answer.
 */
export function bestGrade(standard: GraderStandard, front: number | null, back: number | null): string | null {
  if (front == null && back == null) return null
  const within = (share: number | null, max: number) => share == null || share <= max + 1e-9
  const fit = standard.allowances.find((a) => within(front, a.maxFrontShare) && within(back, a.maxBackShare))
  return fit?.grade ?? standard.lowest
}

/** Shares by face, for a reading of one face only. */
export function faceShares(face: CenteringFace, share: number | null): { front: number | null; back: number | null } {
  return face === 'FRONT' ? { front: share, back: null } : { front: null, back: share }
}

/**
 * Arrow keys move the chosen line 1 px (0.05 mm), inward on right or down for left and top, on left or up
 * for right and bottom. Every arrow is kept from scrolling the page while the editor has focus, the other
 * axis's too; a form field keeps its own arrows.
 */
export function onFrameKey(e: KeyboardEvent, selected: FrameSide, nudge: (side: FrameSide, by: number) => void) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return
  const target = e.target as HTMLElement
  if (target.closest('input, select, textarea')) return
  e.preventDefault()
  const vertical = selected === 'left' || selected === 'right'
  const towardsEnd = vertical ? e.key === 'ArrowRight' : e.key === 'ArrowDown'
  const towardsStart = vertical ? e.key === 'ArrowLeft' : e.key === 'ArrowUp'
  if (!towardsEnd && !towardsStart) return
  const inward = selected === 'left' || selected === 'top' ? towardsEnd : towardsStart
  nudge(selected, inward ? 1 : -1)
}
