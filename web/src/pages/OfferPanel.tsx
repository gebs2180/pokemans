import { useState } from 'react'
import {
  apiPath,
  confidenceInWords,
  likelyRungs,
  money,
  PAYMENT_METHOD,
  useApi,
  type CardStatus,
  type Offer,
  type Sale,
  type ShipAddress,
  type SoldPrice,
} from '../api'
import { useLoad } from '../useLoad'
import { AddressForm } from './AddressForm'
import { SoldPanel } from './SoldPanel'
import { message } from '../format'
import { days, usePolicy } from '../policy'

/** "$300 (at least 3 sales, 90 days)": the sold price a grade's worth starts from, with what it rests on. */
function soldFor(sold: SoldPrice | undefined) {
  if (!sold) return 'no sales of its own'
  return `${money(sold.price)} (at least ${sold.sales} ${sold.sales === 1 ? 'sale' : 'sales'}, ${sold.windowDays} days)`
}

/**
 * How the firm price was reached: what a buyer could pay at each grade the card might be, how likely each is, and what
 * that grade recently sold for (UX review finding 11). The sold prices are the comps stored with the offer, not today's.
 */
export function HowPriced({ offer }: { offer: Offer }) {
  const { likely, lower } = likelyRungs(offer.ladder)
  const sold = offer.sold
  return (
    <>
      <p className="small">{confidenceInWords(offer)}</p>
      <details className="disclosure">
        <summary>How we priced it</summary>
        <div className="scroll">
          <table className="table ladder">
            <thead>
              <tr>
                <th>If it's a</th>
                <th>Chance</th>
                {sold && <th>Recently sold for</th>}
                <th>Worth to a buyer</th>
              </tr>
            </thead>
            <tbody>
              {likely.map((r) => (
                <tr key={r.grade}>
                  <td>PSA {r.grade}</td>
                  <td>{Math.round(r.probability * 100)}%</td>
                  {sold && <td>{soldFor(sold.find((s) => s.grade === r.grade))}</td>}
                  <td>{money(r.price)}</td>
                </tr>
              ))}
              {lower && (
                <tr>
                  <td>PSA {lower.grade} or lower</td>
                  <td className="muted">unlikely</td>
                  {sold && <td />}
                  <td>up to {money(lower.price)}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {sold && (
          <p className="muted small">
            Recently sold for: the typical PSA-graded sale at that grade, recent sales counting most, from the sales we priced this
            offer on.
          </p>
        )}
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
    </>
  )
}

/**
 * A member's card: get our instant offer, sell at it, and follow the sale through to the buyer.
 *
 * @param onChange called after the seller gets an offer, posts or withdraws, so the rest of the card page catches up
 */
export function OfferPanel({ orgId, cardId, onChange }: { orgId: string; cardId: string; onChange?: () => void }) {
  const policy = usePolicy()
  const api = useApi()
  const salePath = apiPath`/api/orgs/${orgId}/cards/${cardId}/sale`
  const { data: sale, error, reload } = useLoad(() => api<Sale>('GET', salePath), [api, salePath])
  // Wrapped, so "no address on file" (an empty response) is told apart from "still loading".
  const { data: shipFrom, error: addressError, reload: reloadAddress } = useLoad(
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
      setActionError(message(e))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!sale) return null
  const { offer, listing } = sale
  const live = listing && (listing.status === 'OPEN' || listing.status === 'ACCEPTED')
  const getOffer = () => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/offers`)
  const address = shipFrom?.address
  const paidBy = address?.paymentMethod ? `, by ${PAYMENT_METHOD[address.paymentMethod]}` : ''
  // The card sold at this offer and came back: it goes out again on a new one, or not at all.
  const soldAtThisOffer = listing?.status === 'BACK' && listing.offerId === offer?.id
  // Only offer the button when asking can work; otherwise say what happens first.
  const askButton = (text: string) =>
    status?.canOffer ? (
      <div className="action-bar">
        <div className="actions">
          <button onClick={getOffer} disabled={busy} aria-busy={busy}>
            {text}
          </button>
        </div>
      </div>
    ) : (
      status && <p className="muted">{status.nextStep}</p>
    )

  return (
    <section className="panel offer-panel">
      <h2>Your offer</h2>
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
          {offer.rawSales?.below && offer.rawSales.lowest != null && (
            <p className="alert alert-warning">
              This offer is below recent raw sales of {money(offer.rawSales.lowest)}: that's the lowest near-mint ungraded sale we
              have for this card in the 90 days before the offer. You may do better selling it raw yourself.
            </p>
          )}
          <HowPriced offer={offer} />
          {!live &&
            (soldAtThisOffer ? (
              askButton('Get a new offer')
            ) : offer.voided ? (
              <>
                <p className="alert alert-warning">
                  We withdrew this offer on {new Date(offer.voided.at).toLocaleDateString()}: {offer.voided.reason}.
                </p>
                {askButton('Get a new offer')}
              </>
            ) : offer.expired ? (
              <>
                <p className="alert alert-warning">This offer has expired; prices move.</p>
                {askButton('Get a new offer')}
              </>
            ) : (
              <>
                <p className="muted small">
                  Good until {new Date(offer.expiresAt).toLocaleDateString()}. Selling posts this price to our buyers: the first to
                  accept buys the card at it, and we send you a prepaid, insured shipping label. It isn't sold until a buyer accepts.
                </p>
                <p className="small">
                  <strong>The buyer pays you directly, not us{paidBy},</strong> within {days(policy.payWithinDays)} of the card arriving as shown. Rawmark
                  doesn't handle the money.
                </p>
                {shipFrom && (address == null || !address.paymentHandle || editingAddress) ? (
                  <>
                    <h3>Where will you ship from?</h3>
                    <AddressForm
                      orgId={orgId}
                      payment
                      initial={address}
                      onSaved={() => {
                        setEditingAddress(false)
                        reloadAddress()
                      }}
                    />
                  </>
                ) : (
                  <>
                    {address && (
                      <p className="meta">
                        Ships from {address.name}, {address.street1}
                        {address.street2 && ` ${address.street2}`}, {address.city}, {address.state} {address.zip}.
                        {address.paymentMethod && ` Paid by ${PAYMENT_METHOD[address.paymentMethod]}: ${address.paymentHandle}.`}{' '}
                        <button className="link" onClick={() => setEditingAddress(true)}>
                          Change
                        </button>
                      </p>
                    )}
                    <div className="action-bar">
                      {confirming === 'sell' ? (
                        <div className="confirm">
                          <p>
                            If a buyer accepts, you're committing to ship them the card, as shown in your photos, for{' '}
                            {money(offer.price)}. They pay you directly{paidBy}, within {days(policy.payWithinDays)} of it arriving.
                          </p>
                          <div className="actions">
                            <button
                              onClick={() => act('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/listings`, { offerId: offer.id })}
                              disabled={busy}
                              aria-busy={busy}
                            >
                              Confirm: post for {money(offer.price)}
                            </button>
                            <button className="secondary" onClick={() => setConfirming(null)}>
                              Cancel
                            </button>
                          </div>
                        </div>
                      ) : (
                        <div className="actions">
                          <button onClick={() => setConfirming('sell')} disabled={busy || !shipFrom} aria-busy={busy}>
                            Sell for {money(offer.price)}
                          </button>
                        </div>
                      )}
                    </div>
                  </>
                )}
              </>
            ))}
        </>
      )}

      {listing?.status === 'OPEN' && (
        <div className="alert alert-info">
          <p>
            Waiting for a buyer, until {new Date(listing.expiresAt).toLocaleDateString()}. If one takes it, you have {days(policy.shipWithinDays)} to ship. We
            don't send notifications yet, so check back here.
          </p>
          <div className="actions">
            {confirming === 'withdraw' ? (
              <>
                <button
                  className="danger"
                  onClick={() => act('POST', apiPath`/api/orgs/${orgId}/listings/${listing.id}/withdraw`)}
                  disabled={busy}
                  aria-busy={busy}
                >
                  Confirm: take it off the market
                </button>
                <button className="secondary" onClick={() => setConfirming(null)}>
                  Keep it posted
                </button>
              </>
            ) : (
              <button className="link" onClick={() => setConfirming('withdraw')} disabled={busy}>
                Withdraw
              </button>
            )}
          </div>
        </div>
      )}
      {listing && (listing.status === 'ACCEPTED' || soldAtThisOffer) && (
        <SoldPanel
          orgId={orgId}
          listing={listing}
          price={offer?.price}
          address={address}
          busy={busy}
          act={act}
          onAddressSaved={reloadAddress}
        />
      )}
      {listing?.status === 'CANCELLED' && listing.offerId === offer?.id && (
        <p className="alert alert-danger">
          We cancelled the sale{listing.buyer?.orgName && ` to ${listing.buyer.orgName}`}
          {listing.undone && ` on ${new Date(listing.undone.at).toLocaleDateString()}: ${listing.undone.reason}`}. Don't ship the card, and
          don't use its label. If you already shipped it, tell us and we'll help get it back.
        </p>
      )}
      {listing && (listing.status === 'WITHDRAWN' || listing.status === 'EXPIRED') && (
        <p className="muted small">Your last posting was {listing.status.toLowerCase()}.</p>
      )}
      {addressError && <p className="error" role="alert">Couldn't load your shipping address: {addressError}</p>}
      {actionError && <p className="error" role="alert">{actionError}</p>}
    </section>
  )
}
