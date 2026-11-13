import { useEffect, useRef, useState } from 'react'
import { likelyRungs, money, type Offer } from '../api'
import { hasSeenOffer, markOfferSeen, prefersReducedMotion } from '../offerSeen'
import { slipCents } from '../slip'

/** Accent for the most likely grade, then lighter or darker steps; repeated if there are more grades than that. */
const SEGMENT_CLASSES = ['seg-1', 'seg-2', 'seg-3', 'seg-4']

/** Which colour each grade gets: by how likely it is, so the strongest colour is always the biggest share. */
function segmentClasses(probabilities: number[]) {
  const order = probabilities.map((p, i) => ({ p, i })).sort((a, b) => b.p - a.p || a.i - b.i)
  const classes: string[] = []
  order.forEach(({ i }, rank) => {
    classes[i] = SEGMENT_CLASSES[Math.min(rank, SEGMENT_CLASSES.length - 1)]
  })
  return classes
}

const pct = (p: number) => `${Math.round(p * 100)}%`

/**
 * How the price adds up: the chance of each grade as one bar, then per grade its chance times what a buyer pays at it,
 * ending in the firm offer. The rows add to the offer to the cent (the last takes the rounding). These are the inputs
 * to a price, not a prediction of the grade it will come back as.
 */
export function PriceBreakdown({ offer }: { offer: Offer }) {
  const { likely, lower } = likelyRungs(offer.ladder)
  const cents = slipCents(likely, offer.price)
  const classes = segmentClasses(likely.map((r) => r.probability))
  return (
    <div className="chance">
      <p className="chance-intro">How the price adds up: the chance of each grade, times what a buyer would pay at that grade.</p>
      <div
        className="stackbar"
        role="img"
        aria-label={`Chance of each grade: ${likely.map((r) => `PSA ${r.grade} ${pct(r.probability)}`).join(', ')}`}
      >
        {likely.map((r, i) => (
          <span key={r.grade} className={classes[i]} style={{ flexGrow: r.probability }} />
        ))}
      </div>
      <ul className="adds-up">
        {likely.map((r, i) => (
          <li key={r.grade}>
            <span className={`swatch ${classes[i]}`} aria-hidden="true" />
            <span className="adds-grade">PSA {r.grade}</span>
            <span className="adds-sum muted">
              {pct(r.probability)} × {money(r.price)}
            </span>
            <span className="adds-total">{money(cents[i] / 100)}</span>
          </li>
        ))}
        {lower && (
          <li>
            <span className="swatch" aria-hidden="true" />
            <span className="adds-grade">PSA {lower.grade} or lower</span>
            <span className="adds-sum muted">0%, not counted</span>
            <span className="adds-total">{money(0)}</span>
          </li>
        )}
      </ul>
      <p className="muted small">These are what the price is made of, not a grade we're predicting.</p>
    </div>
  )
}

function RevealBack({ offer }: { offer: Offer }) {
  return (
    <>
      <p className="reveal-label">Firm offer</p>
      <p className="offer-price">{money(offer.price)}</p>
      <PriceBreakdown offer={offer} />
      <p className="reveal-final">Final. No re-grade on arrival.</p>
    </>
  )
}

/** The card turns over once it is mostly on screen; where there's no way to tell, straight away. */
const VISIBLE = 0.6

/**
 * The price a collector is offered. The first time they see an offer the card turns over, once it's mostly in view, and
 * the price is on its back; after that, and with reduced motion on, the same back is simply there. It counts as seen
 * only once the turn has finished, so a phone that opens it below the fold still gets the moment.
 *
 * @param frontUrl the card's front, cropped, shown until it turns over; a plain card face when there isn't one
 */
export function OfferReveal({ offer, frontUrl }: { offer: Offer; frontUrl?: string | null }) {
  // Decided once, when the offer first renders.
  const [flipping, setFlipping] = useState(() => !hasSeenOffer(offer.id) && !prefersReducedMotion())
  const [play, setPlay] = useState(false)
  const root = useRef<HTMLDivElement>(null)

  // Reduced motion, or already seen: nothing plays, so only a first sight needs recording here.
  useEffect(() => {
    if (!flipping) markOfferSeen(offer.id)
  }, [flipping, offer.id])

  useEffect(() => {
    if (!flipping) return
    const el = root.current
    if (!el || typeof IntersectionObserver === 'undefined') {
      setPlay(true)
      return
    }
    const watcher = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting && e.intersectionRatio >= VISIBLE)) {
          setPlay(true)
          watcher.disconnect()
        }
      },
      { threshold: [VISIBLE] },
    )
    watcher.observe(el)
    return () => watcher.disconnect()
  }, [flipping])

  if (!flipping) {
    return (
      <div className="reveal-static" data-testid="offer-reveal">
        <RevealBack offer={offer} />
      </div>
    )
  }
  return (
    <div className="flip" data-testid="offer-reveal" ref={root}>
      <div
        className={`flip-inner${play ? ' play' : ''}`}
        onAnimationEnd={() => {
          markOfferSeen(offer.id)
          setFlipping(false)
        }}
      >
        <div className="flip-face flip-front" aria-hidden="true">
          {frontUrl ? <img src={frontUrl} alt="" /> : <div className="flip-blank" />}
        </div>
        <div className="flip-face flip-back">
          <RevealBack offer={offer} />
        </div>
      </div>
    </div>
  )
}
