const seenKey = (offerId: string) => `rawmark.offerSeen.${offerId}`

/**
 * Whether this browser has already shown the offer's reveal. When storage can't be read (a private window, blocked site
 * data) it says yes: a reveal that can't be remembered would play on every visit, and a plain price is always right.
 */
export function hasSeenOffer(offerId: string): boolean {
  try {
    return localStorage.getItem(seenKey(offerId)) !== null
  } catch {
    return true
  }
}

export function markOfferSeen(offerId: string) {
  try {
    localStorage.setItem(seenKey(offerId), '1')
  } catch {
    // Nothing to remember it with: the next visit shows a plain price anyway (see hasSeenOffer).
  }
}

/** jsdom and some embedded browsers have no matchMedia: treat that as "no preference", and let the CSS do the rest. */
export function prefersReducedMotion(): boolean {
  try {
    return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}
