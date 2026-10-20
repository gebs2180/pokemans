import { useState } from 'react'
import { apiPath, likelyRungs, money, useApi, type Offer, type Sale } from '../api'
import { useLoad } from '../useLoad'

/** The offer as a price per grade: what a buyer pays depends on the grade the card inspects at. */
export function OfferLadder({ offer }: { offer: Offer }) {
  const { likely, lower } = likelyRungs(offer.ladder)
  return (
    <>
      <table className="table">
        <thead>
          <tr>
            <th>If it inspects at</th>
            <th>Chance</th>
            <th>Offer</th>
          </tr>
        </thead>
        <tbody>
          {likely.map((r) => (
            <tr key={r.grade}>
              <td>PSA {r.grade}</td>
              <td>{Math.round(r.probability * 100)}%</td>
              <td>{money(r.price)}</td>
            </tr>
          ))}
          {lower && (
            <tr>
              <td>PSA {lower.grade} or lower</td>
              <td className="muted">unlikely</td>
              <td>up to {money(lower.price)}</td>
            </tr>
          )}
        </tbody>
      </table>
      <p className="muted small">
        Likely about {money(offer.expectedOffer)}.
        {offer.subjectToAuthentication && ' Subject to the buyer authenticating the card in hand.'}
      </p>
    </>
  )
}

/** A member's card: get our instant offer, post it to buyers, and see what happened. */
export function OfferPanel({ orgId, cardId }: { orgId: string; cardId: string }) {
  const api = useApi()
  const salePath = apiPath`/api/orgs/${orgId}/cards/${cardId}/sale`
  const { data: sale, error, reload } = useLoad(() => api<Sale>('GET', salePath), [api, salePath])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  async function act(method: string, path: string, body?: unknown) {
    setActionError(null)
    setBusy(true)
    try {
      await api(method, path, body)
      reload()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!sale) return null
  const { offer, listing } = sale
  const live = listing && (listing.status === 'OPEN' || listing.status === 'ACCEPTED')
  const getOffer = () => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/offers`)

  return (
    <section className="panel">
      <h2>Instant offer</h2>
      {!offer && (
        <>
          <p className="muted">See what a buyer would pay for this card, at each grade it could come back as.</p>
          <button onClick={getOffer} disabled={busy}>
            Get an offer
          </button>
        </>
      )}
      {offer && (
        <>
          <OfferLadder offer={offer} />
          {!live &&
            (offer.expired ? (
              <p>
                This offer has expired; prices move.{' '}
                <button onClick={getOffer} disabled={busy}>
                  Get a new offer
                </button>
              </p>
            ) : (
              <>
                <p className="muted small">
                  Good until {new Date(offer.expiresAt).toLocaleDateString()}. Selling posts it to our buyers: the first to accept
                  takes the card, and it isn't sold until one does.
                </p>
                <button onClick={() => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/listings`, { offerId: offer.id })} disabled={busy}>
                  Sell at this offer
                </button>
              </>
            ))}
        </>
      )}

      {listing?.status === 'OPEN' && (
        <p>
          Posted to buyers until {new Date(listing.expiresAt).toLocaleDateString()}.{' '}
          <button className="link" onClick={() => act('POST', apiPath`/api/orgs/${orgId}/listings/${listing.id}/withdraw`)} disabled={busy}>
            Withdraw
          </button>
        </p>
      )}
      {listing?.status === 'ACCEPTED' && (
        <div className="ok">
          <p>
            Taken by {listing.buyer?.orgName ?? 'a buyer'}
            {listing.buyer?.email && ` (${listing.buyer.email})`} on {new Date(listing.closedAt!).toLocaleDateString()}. Contact them
            to arrange shipping; they pay the offer for the grade they find.
          </p>
          {listing.inspection && (
            <p>
              {listing.inspection.declinedReason
                ? `They declined: ${listing.inspection.declinedReason.replace(/_/g, ' ').toLowerCase()}.`
                : `Inspected at PSA ${listing.inspection.inspectedGrade}; paid ${money(listing.inspection.pricePaid)} (offer at that grade ${money(listing.inspection.ladderPrice)}).`}
            </p>
          )}
        </div>
      )}
      {listing && (listing.status === 'WITHDRAWN' || listing.status === 'EXPIRED') && (
        <p className="muted small">Your last posting was {listing.status.toLowerCase()}.</p>
      )}
      {actionError && <p className="error" role="alert">{actionError}</p>}
    </section>
  )
}
