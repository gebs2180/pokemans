import { useState } from 'react'
import { apiPath, likelyRungs, money, RETURN_REASON, useApi, type CardStatus, type Offer, type Sale, type ShipAddress } from '../api'
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
        Each grade's worth is what the card sells for at that grade, less what it costs a buyer to get it graded (fee, shipping
        and insurance) and to sell it. That's why a PSA 10's worth is well under what PSA 10s sell for.
      </p>
      <p className="muted small">
        Each grade's worth leaves the buyer a {Math.round(offer.buyerMargin * 100)}% margin, set by how sure we are of the card's
        condition (confidence {offer.confidence}): the less certain, the more room the buyer gets for the risk.
      </p>
    </details>
  )
}

/** A member's card: get our instant offer, sell at it, and follow the sale through to the buyer. */
/** @param onChange called after the seller gets an offer, posts or withdraws, so the rest of the card page catches up */
export function OfferPanel({ orgId, cardId, onChange }: { orgId: string; cardId: string; onChange?: () => void }) {
  const api = useApi()
  const salePath = apiPath`/api/orgs/${orgId}/cards/${cardId}/sale`
  const { data: sale, error, reload } = useLoad(() => api<Sale>('GET', salePath), [api, salePath])
  // Wrapped, so "no address on file" (an empty response) is told apart from "still loading".
  const { data: shipFrom, reload: reloadAddress } = useLoad(
    () => api<ShipAddress | null>('GET', apiPath`/api/orgs/${orgId}/ship-address`).then((address) => ({ address })),
    [api, orgId],
  )
  const { data: status, reload: reloadStatus } = useLoad(
    () => api<CardStatus>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}/status`),
    [api, orgId, cardId],
  )
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  // Selling commits the seller to ship, and withdrawing takes the card off the market: each asks once more.
  const [confirming, setConfirming] = useState<'sell' | 'withdraw' | null>(null)
  const [editingAddress, setEditingAddress] = useState(false)

  async function act(method: string, path: string, body?: unknown) {
    setActionError(null)
    setBusy(true)
    setConfirming(null)
    try {
      await api(method, path, body)
      reload()
      reloadStatus()
      onChange?.()
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
  const address = shipFrom?.address
  // Only offer the button when asking can work; otherwise say what happens first.
  const askButton = (text: string) =>
    status?.canOffer ? (
      <button onClick={getOffer} disabled={busy}>
        {text}
      </button>
    ) : (
      status && <p className="muted">{status.nextStep}</p>
    )

  return (
    <section className="panel">
      <h2>Instant offer</h2>
      {!offer && (
        <>
          <p className="muted">One firm price for this card, based on the grade it's likely to be. No listing, no haggling.</p>
          {askButton('Get an offer')}
        </>
      )}
      {offer && (
        <>
          <p className="offer-price">{money(offer.price)}</p>
          {offer.subjectToAuthentication && <p className="muted small">Subject to the buyer authenticating the card in hand.</p>}
          <HowPriced offer={offer} />
          {!live &&
            (offer.expired ? (
              <>
                <p>This offer has expired; prices move.</p>
                {askButton('Get a new offer')}
              </>
            ) : (
              <>
                <p className="muted small">
                  Good until {new Date(offer.expiresAt).toLocaleDateString()}. Selling posts this price to our buyers: the first to
                  accept buys the card at it, and we send you a prepaid, insured shipping label. It isn't sold until a buyer accepts.
                </p>
                {shipFrom && (address == null || editingAddress) ? (
                  <>
                    <p>Where will you ship from?</p>
                    <AddressForm
                      orgId={orgId}
                      onSaved={() => {
                        setEditingAddress(false)
                        reloadAddress()
                      }}
                    />
                  </>
                ) : (
                  <>
                    {address && (
                      <p className="muted small">
                        Ships from {address.name}, {address.street1}
                        {address.street2 && ` ${address.street2}`}, {address.city}, {address.state} {address.zip}.{' '}
                        <button className="link" onClick={() => setEditingAddress(true)}>
                          Change
                        </button>
                      </p>
                    )}
                    {confirming === 'sell' ? (
                      <div className="stack">
                        <p>
                          If a buyer accepts, you're committing to ship them the card, as shown in your photos, for{' '}
                          {money(offer.price)}.
                        </p>
                        <p className="row">
                          <button
                            onClick={() => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/listings`, { offerId: offer.id })}
                            disabled={busy}
                          >
                            Confirm: post for {money(offer.price)}
                          </button>
                          <button className="link" onClick={() => setConfirming(null)}>
                            Cancel
                          </button>
                        </p>
                      </div>
                    ) : (
                      <button onClick={() => setConfirming('sell')} disabled={busy || !shipFrom}>
                        Sell for {money(offer.price)}
                      </button>
                    )}
                  </>
                )}
              </>
            ))}
        </>
      )}

      {listing?.status === 'OPEN' && (
        <p>
          Waiting for a buyer, until {new Date(listing.expiresAt).toLocaleDateString()}.{' '}
          {confirming === 'withdraw' ? (
            <>
              <button onClick={() => act('POST', apiPath`/api/orgs/${orgId}/listings/${listing.id}/withdraw`)} disabled={busy}>
                Confirm: take it off the market
              </button>{' '}
              <button className="link" onClick={() => setConfirming(null)}>
                Keep it posted
              </button>
            </>
          ) : (
            <button className="link" onClick={() => setConfirming('withdraw')} disabled={busy}>
              Withdraw
            </button>
          )}
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
            !listing.outcome && (
              <p className="warn">
                Your prepaid label isn't ready yet: buying it didn't go through. Don't ship the card until the label shows here; we'll
                be in touch.
              </p>
            )
          )}
          {listing.outcome?.type === 'RECEIVED' && (
            <p>
              Arrived. They recorded paying {money(listing.outcome.pricePaid)}
              {listing.outcome.note ? `: "${listing.outcome.note}"` : '.'}
            </p>
          )}
          {listing.outcome?.type === 'RETURNED' && listing.outcome.returnedReason && (
            <p>They're sending it back: {RETURN_REASON[listing.outcome.returnedReason]}.</p>
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
