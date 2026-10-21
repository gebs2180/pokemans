import { useState } from 'react'
import { apiPath, likelyRungs, money, RETURN_REASON, useApi, type Offer, type Sale, type ShipAddress } from '../api'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'
import { AddressForm } from './AddressForm'

/** How the firm price was reached: what a buyer could pay at each grade the card might be, and how likely each is. */
export function HowPriced({ offer }: { offer: Offer }) {
  const { likely, lower } = likelyRungs(offer.ladder)
  return (
    <details>
      <summary className="muted small">How we priced it</summary>
      <table className="table">
        <thead>
          <tr>
            <th>If it's a</th>
            <th>Chance</th>
            <th>Worth to a buyer</th>
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
      <p className="muted small">The offer is the chance-weighted total, and it doesn't change with the grade the card turns out to be.</p>
      <p className="muted small">
        Each grade's worth leaves the buyer a {Math.round(offer.buyerMargin * 100)}% margin, set by how sure we are of the card's
        condition (confidence {offer.confidence}): the less certain, the more room the buyer gets for the risk.
      </p>
    </details>
  )
}

/** A member's card: get our instant offer, sell at it, and follow the sale through to the buyer. */
export function OfferPanel({ orgId, cardId }: { orgId: string; cardId: string }) {
  const api = useApi()
  const salePath = apiPath`/api/orgs/${orgId}/cards/${cardId}/sale`
  const { data: sale, error, reload } = useLoad(() => api<Sale>('GET', salePath), [api, salePath])
  // Wrapped, so "no address on file" (an empty response) is told apart from "still loading".
  const { data: shipFrom, reload: reloadAddress } = useLoad(
    () => api<ShipAddress | null>('GET', apiPath`/api/orgs/${orgId}/ship-address`).then((address) => ({ address })),
    [api, orgId],
  )
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
  const labelHref = safeHref(listing?.label?.labelUrl)

  return (
    <section className="panel">
      <h2>Instant offer</h2>
      {!offer && (
        <>
          <p className="muted">One firm price for this card, based on the grade it's likely to be. No listing, no haggling.</p>
          <button onClick={getOffer} disabled={busy}>
            Get an offer
          </button>
        </>
      )}
      {offer && (
        <>
          <p className="offer-price">{money(offer.price)}</p>
          {offer.subjectToAuthentication && <p className="muted small">Subject to the buyer authenticating the card in hand.</p>}
          <HowPriced offer={offer} />
          {!live &&
            (offer.voided ? (
              <p>
                We withdrew this offer on {new Date(offer.voided.at).toLocaleDateString()}: {offer.voided.reason}.{' '}
                <button onClick={getOffer} disabled={busy}>
                  Get a new offer
                </button>
              </p>
            ) : offer.expired ? (
              <p>
                This offer has expired; prices move.{' '}
                <button onClick={getOffer} disabled={busy}>
                  Get a new offer
                </button>
              </p>
            ) : (
              <>
                <p className="muted small">
                  Good until {new Date(offer.expiresAt).toLocaleDateString()}. Selling posts this price to our buyers: the first to
                  accept buys the card at it, and we send you a prepaid, insured shipping label. It isn't sold until a buyer accepts.
                </p>
                {shipFrom && shipFrom.address == null ? (
                  <>
                    <p>Where will you ship from?</p>
                    <AddressForm orgId={orgId} onSaved={reloadAddress} />
                  </>
                ) : (
                  <button
                    onClick={() => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/listings`, { offerId: offer.id })}
                    disabled={busy || !shipFrom}
                  >
                    Sell for {money(offer.price)}
                  </button>
                )}
              </>
            ))}
        </>
      )}

      {listing?.status === 'OPEN' && (
        <p>
          Waiting for a buyer, until {new Date(listing.expiresAt).toLocaleDateString()}.{' '}
          <button className="link" onClick={() => act('POST', apiPath`/api/orgs/${orgId}/listings/${listing.id}/withdraw`)} disabled={busy}>
            Withdraw
          </button>
        </p>
      )}
      {listing?.status === 'ACCEPTED' && (
        <div className="ok">
          <p>
            Sold to {listing.buyer?.orgName ?? 'a buyer'}
            {listing.buyer?.email && ` (${listing.buyer.email})`} on {new Date(listing.closedAt!).toLocaleDateString()}. They pay
            you {offer && money(offer.price)} once it arrives as shown.
          </p>
          {listing.label && labelHref ? (
            <p>
              <a href={labelHref} target="_blank" rel="noreferrer">
                Print your prepaid label
              </a>{' '}
              <span className="muted small">
                {listing.label.carrier} {listing.label.service}
                {listing.label.trackingCode && `, tracking ${listing.label.trackingCode}`}, insured for {money(listing.label.insuredValue)}
              </span>
            </p>
          ) : (
            <p className="muted">Your prepaid label is on its way.</p>
          )}
          {listing.outcome?.type === 'RECEIVED' && <p>Arrived. They recorded paying {money(listing.outcome.pricePaid)}.</p>}
          {listing.outcome?.type === 'RETURNED' && listing.outcome.returnedReason && (
            <p>They're sending it back: {RETURN_REASON[listing.outcome.returnedReason]}.</p>
          )}
        </div>
      )}
      {listing?.status === 'CANCELLED' && listing.offerId === offer?.id && (
        <p className="warn">
          We cancelled the sale{listing.buyer?.orgName && ` to ${listing.buyer.orgName}`}
          {listing.undone && ` on ${new Date(listing.undone.at).toLocaleDateString()}: ${listing.undone.reason}`}. Don't ship the card, and
          don't use its label.
        </p>
      )}
      {listing && (listing.status === 'WITHDRAWN' || listing.status === 'EXPIRED') && (
        <p className="muted small">Your last posting was {listing.status.toLowerCase()}.</p>
      )}
      {actionError && <p className="error" role="alert">{actionError}</p>}
    </section>
  )
}
