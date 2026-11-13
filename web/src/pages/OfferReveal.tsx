import { useEffect, useState } from 'react'
import { likelyRungs, money, type Offer } from '../api'
import { hasSeenOffer, markOfferSeen, prefersReducedMotion } from '../offerSeen'

/** The ledger greens, then a neutral, repeated if there are more grades than that. */
const SEGMENT_CLASSES = ['seg-1', 'seg-2', 'seg-3', 'seg-4']

/** The chance of each grade as one bar, with a legend below since the lighter greens can't carry text. */
export function ChanceBar({ offer }: { offer: Offer }) {
  const { likely } = likelyRungs(offer.ladder)
  const pct = (p: number) => Math.round(p * 100)
  return (
    <div className="chance">
      <div
        className="stackbar"
        role="img"
        aria-label={`Chance of each grade: ${likely.map((r) => `PSA ${r.grade} ${pct(r.probability)}%`).join(', ')}`}
      >
        {likely.map((r, i) => (
          <span key={r.grade} className={SEGMENT_CLASSES[i % SEGMENT_CLASSES.length]} style={{ flexGrow: r.probability }} />
        ))}
      </div>
      <ul className="stackbar-legend" aria-hidden="true">
        {likely.map((r, i) => (
          <li key={r.grade}>
            <span className={`swatch ${SEGMENT_CLASSES[i % SEGMENT_CLASSES.length]}`} />
            PSA {r.grade} <span className="muted">{pct(r.probability)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function RevealBack({ offer }: { offer: Offer }) {
  return (
    <>
      <p className="reveal-label">Firm offer</p>
      <p className="offer-price">{money(offer.price)}</p>
      <ChanceBar offer={offer} />
      <p className="reveal-final">Final. No re-grade on arrival.</p>
    </>
  )
}

/**
 * The price a collector is offered. The first time they see an offer the card turns over and the price is on its back;
 * after that, and with reduced motion on, it's just the price. Seen is remembered per offer in this browser.
 *
 * @param frontUrl the card's front photo, shown until it turns over; a plain card face when there isn't one
 */
export function OfferReveal({ offer, frontUrl }: { offer: Offer; frontUrl?: string | null }) {
  // Decided once, when the offer first renders: marking it seen must not undo the reveal that's playing.
  const [first] = useState(() => !hasSeenOffer(offer.id))
  const [still] = useState(prefersReducedMotion)
  useEffect(() => markOfferSeen(offer.id), [offer.id])

  if (!first) return <p className="offer-price">{money(offer.price)}</p>
  if (still) {
    return (
      <div className="reveal-static" data-testid="offer-reveal">
        <RevealBack offer={offer} />
      </div>
    )
  }
  return (
    <div className="flip" data-testid="offer-reveal">
      <div className="flip-inner">
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
