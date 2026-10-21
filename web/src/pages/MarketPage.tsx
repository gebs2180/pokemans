import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath, money, parseMoney, RETURN_REASON, useApi, type AcceptResult, type Deal, type DeclinedReason, type MarketItem, type OfferCard } from '../api'
import { MoneyInput } from '../inputs'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'
import { HowPriced } from './OfferPanel'

function CardTitle({ card }: { card: OfferCard }) {
  return (
    <>
      <h3>{card.name}</h3>
      <p className="muted small">
        {card.setName} #{card.number}, {card.language.toUpperCase()}
        {card.edition !== 'UNLIMITED' && `, ${card.edition.replace(/_/g, ' ').toLowerCase()}`}
        {card.variant !== 'NORMAL' && `, ${card.variant.replace(/_/g, ' ').toLowerCase()}`}
        {card.gradedAs && ` · graded ${card.gradedAs}`}
      </p>
    </>
  )
}

function Photos({ photos }: { photos: { side: string; url: string }[] }) {
  return (
    <div className="photos">
      {photos.map((p) => {
        const href = safeHref(p.url)
        return href ? (
          <a key={href} href={href} target="_blank" rel="noreferrer">
            <img src={href} alt={p.side.toLowerCase()} className="thumb" />
          </a>
        ) : null
      })}
    </div>
  )
}

/** Buyers: raw cards posted at one firm price. The first buyer to accept buys the card at it. */
export function MarketPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<MarketItem[]>('GET', apiPath`/api/orgs/${orgId}/market`), [api, orgId])
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function accept(listingId: string) {
    setMessage(null)
    setBusy(true)
    try {
      const result = await api<AcceptResult>('POST', apiPath`/api/orgs/${orgId}/market/${listingId}/accept`)
      setMessage(
        result.labelPending
          ? `Bought. The seller's label didn't go through yet (${result.labelPending}); try it again from your deals.`
          : 'Bought: the seller has a prepaid label to you. It is in your deals.',
      )
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
      setConfirming(null)
      reload()
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <p>
        <Link to={`/orgs/${orgId}`}>← Back</Link> · <Link to={`/orgs/${orgId}/deals`}>Your deals</Link>
      </p>
      <h1>Cards for sale</h1>
      <p className="muted">
        Each raw card has one firm price, based on the grade it's likely to be. Accepting buys it at that price, whatever it grades;
        we send the seller a prepaid label to you, billed to you monthly.
      </p>
      {message && <p role="status">{message}</p>}
      {!data && <p>Loading…</p>}
      {data?.length === 0 && <p className="muted">Nothing posted right now.</p>}
      {data?.map((item) => (
        <section key={item.listingId} className="panel">
          <CardTitle card={item.card} />
          <Photos photos={item.photos} />
          <p className="offer-price">{money(item.offer.price)}</p>
          {item.offer.subjectToAuthentication && <p className="muted small">Not yet authenticated: check it in hand.</p>}
          <HowPriced offer={item.offer} />
          <p className="muted small">
            Posted {new Date(item.listedAt).toLocaleDateString()}, open until {new Date(item.expiresAt).toLocaleDateString()}.
          </p>
          {confirming === item.listingId ? (
            <p className="row">
              <button onClick={() => accept(item.listingId)} disabled={busy}>
                Confirm: buy for {money(item.offer.price)}
              </button>
              <button className="link" onClick={() => setConfirming(null)}>
                Cancel
              </button>
            </p>
          ) : (
            <button onClick={() => setConfirming(item.listingId)}>Buy</button>
          )}
        </section>
      ))}
    </>
  )
}

/** Buyers: cards you bought, their labels, and what to record when each one arrives. */
export function DealsPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<Deal[]>('GET', apiPath`/api/orgs/${orgId}/deals`), [api, orgId])
  const [labelError, setLabelError] = useState<string | null>(null)

  async function retryLabel(listingId: string) {
    setLabelError(null)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/deals/${listingId}/label`)
      reload()
    } catch (e) {
      setLabelError(e instanceof Error ? e.message : String(e))
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <p>
        <Link to={`/orgs/${orgId}/market`}>← Cards for sale</Link>
      </p>
      <h1>Your deals</h1>
      {labelError && <p className="error" role="alert">{labelError}</p>}
      {!data && <p>Loading…</p>}
      {data?.length === 0 && <p className="muted">You haven't bought any cards yet.</p>}
      {data?.map((deal) => (
        <section key={deal.listingId} className="panel">
          <CardTitle card={deal.card} />
          <p>
            Bought for {money(deal.offer.price)} on {new Date(deal.acceptedAt).toLocaleDateString()} from {deal.seller.orgName ?? 'a seller'}
            {deal.seller.email && (
              <>
                {' '}
                (<a href={`mailto:${deal.seller.email}`}>{deal.seller.email}</a>)
              </>
            )}
            .
          </p>
          {deal.label ? (
            <p className="muted small">
              {deal.label.carrier} {deal.label.service}
              {deal.label.trackingCode && `, tracking ${deal.label.trackingCode}`}, insured for {money(deal.label.insuredValue)}.
            </p>
          ) : (
            <p className="warn">
              No label yet.{' '}
              <button className="link" onClick={() => retryLabel(deal.listingId)}>
                Try the label again
              </button>
            </p>
          )}
          {deal.outcome ? (
            <p className="ok">
              {deal.outcome.type === 'RECEIVED' && `Arrived; you recorded paying ${money(deal.outcome.pricePaid)}.`}
              {deal.outcome.type === 'RETURNED' && deal.outcome.returnedReason && `Returned: ${RETURN_REASON[deal.outcome.returnedReason]}.`}
              {deal.outcome.type === 'INSPECTED' && `Inspected at PSA ${deal.outcome.inspectedGrade}.`}
            </p>
          ) : (
            <ArrivalForm orgId={orgId} deal={deal} onSaved={reload} />
          )}
        </section>
      ))}
    </>
  )
}

/** Once the card is in hand: it came as shown and you paid, or it goes back for one of the allowed reasons. */
export function ArrivalForm({ orgId, deal, onSaved }: { orgId: string; deal: Deal; onSaved: () => void }) {
  const api = useApi()
  const [returning, setReturning] = useState(false)
  const [paid, setPaid] = useState(String(deal.offer.price))
  const [reason, setReason] = useState<DeclinedReason>('DOES_NOT_MATCH_PHOTOS')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (returning) {
        await api('POST', apiPath`/api/orgs/${orgId}/deals/${deal.listingId}/returned`, { reason, note: note || null })
      } else {
        await api('POST', apiPath`/api/orgs/${orgId}/deals/${deal.listingId}/received`, { pricePaid: parseMoney(paid), note: note || null })
      }
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="stack">
      <div className="row">
        <label>
          <input type="radio" checked={!returning} onChange={() => setReturning(false)} /> Arrived as shown, paid
        </label>
        <label>
          <input type="radio" checked={returning} onChange={() => setReturning(true)} /> Sending it back
        </label>
      </div>
      {returning ? (
        <label>
          Because it's{' '}
          <select value={reason} onChange={(e) => setReason(e.target.value as DeclinedReason)} aria-label="Return reason">
            {(Object.keys(RETURN_REASON) as DeclinedReason[]).map((r) => (
              <option key={r} value={r}>
                {RETURN_REASON[r]}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <label>
          Paid ($){' '}
          <MoneyInput value={paid} onChange={setPaid} min={0} className="narrow" aria-label="Price paid" required />
        </label>
      )}
      <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" aria-label="Note" maxLength={2000} />
      <button type="submit" disabled={busy}>
        Record
      </button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}
