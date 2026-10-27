import { useState, type FormEvent } from 'react'
import { Link, NavLink, useParams } from 'react-router'
import {
  apiPath,
  money,
  parseMoney,
  RETURN_REASON,
  SIDE_LABEL,
  useApi,
  type AcceptResult,
  type Deal,
  type DeclinedReason,
  type MarketItem,
  type OfferCard,
  type PhotoLink,
} from '../api'
import { MoneyInput } from '../inputs'
import { safeHref } from '../safeHref'
import { EmptyState, Field, Loading } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { HowPriced } from './OfferPanel'

export function CardTitle({ card }: { card: OfferCard }) {
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

/** The photos the offer was priced on, one per side, labelled: what "matches its photos" is checked against. */
export function Photos({ photos }: { photos: PhotoLink[] }) {
  return (
    <div className="photos">
      {photos.map((p) => {
        const href = safeHref(p.url)
        return href ? (
          <a key={href} href={href} target="_blank" rel="noreferrer" className="photo compact">
            <img src={href} alt={`${SIDE_LABEL[p.side]} photo`} className="thumb" />
            <span className="muted small">{SIDE_LABEL[p.side]}</span>
          </a>
        ) : null
      })}
    </div>
  )
}

/** A buyer's two lists, as tabs, with the way back to the org. */
function BuyerNav({ orgId }: { orgId: string }) {
  return (
    <>
      <p className="back">
        <Link to={`/orgs/${orgId}`}>← Back</Link>
      </p>
      <nav className="tabs" aria-label="Buying">
        <NavLink to={`/orgs/${orgId}/market`} end>
          Cards for sale
        </NavLink>
        <NavLink to={`/orgs/${orgId}/deals`} end>
          Your deals
        </NavLink>
      </nav>
    </>
  )
}

/** "Add the address cards should ship to first." is the one refusal the buyer fixes elsewhere: link there. */
const NO_ADDRESS = 'Add the address cards should ship to first.'

/** Buyers: raw cards posted at one firm price. The first buyer to accept buys the card at it. */
export function MarketPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<MarketItem[]>('GET', apiPath`/api/orgs/${orgId}/market`), [api, orgId])
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  // A refusal shows by the card it's about: on a phone the top of the list is off-screen.
  const [failed, setFailed] = useState<{ listingId: string; message: string } | null>(null)

  async function accept(listingId: string) {
    setMessage(null)
    setFailed(null)
    setBusy(true)
    try {
      const result = await api<AcceptResult>('POST', apiPath`/api/orgs/${orgId}/market/${listingId}/accept`)
      setMessage(
        result.labelPending
          ? `Bought. The seller's label didn't go through yet (${result.labelPending.replace(/\.$/, '')}). Try it again from Your deals, or we'll buy it for you.`
          : 'Bought: the seller has a prepaid label to you. It is in Your deals.',
      )
    } catch (e) {
      setFailed({ listingId, message: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
      setConfirming(null)
      reload()
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <BuyerNav orgId={orgId} />
      <h1>Cards for sale</h1>
      <p className="muted">
        Each raw card has one firm price, based on the grade it's likely to be. Accepting buys it at that price, whatever it grades;
        we send the seller a prepaid label to you, billed to you monthly.
      </p>
      {message && (
        <p role="status" className={message.startsWith('Bought:') ? 'alert alert-success' : 'alert alert-warning'}>
          {message}
        </p>
      )}
      {!data && <Loading />}
      {data?.length === 0 && (
        <EmptyState title="Nothing posted right now.">
          <p>New cards show here as collectors post them.</p>
        </EmptyState>
      )}
      {data?.map((item) => (
        <section key={item.listingId} className="panel">
          <div className="listing-top">
            <div>
              <CardTitle card={item.card} />
            </div>
            <p className="offer-price">{money(item.offer.price)}</p>
          </div>
          <Photos photos={item.photos} />
          {item.offer.subjectToAuthentication && <p className="alert alert-warning small">Not yet authenticated: check it in hand.</p>}
          <HowPriced offer={item.offer} />
          <p className="meta">
            Posted {new Date(item.listedAt).toLocaleDateString()}, open until {new Date(item.expiresAt).toLocaleDateString()}.
          </p>
          {confirming === item.listingId ? (
            <div className="confirm">
              <p>You buy it at this price, whatever it grades.</p>
              <div className="actions">
                <button onClick={() => accept(item.listingId)} disabled={busy} aria-busy={busy}>
                  Confirm: buy for {money(item.offer.price)}
                </button>
                <button className="secondary" onClick={() => setConfirming(null)}>
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <div className="actions">
              <button onClick={() => setConfirming(item.listingId)}>Buy</button>
            </div>
          )}
          {failed?.listingId === item.listingId && (
            <p className="error" role="alert">
              {failed.message}
              {failed.message === NO_ADDRESS && (
                <>
                  {' '}
                  <Link to={`/orgs/${orgId}`}>Add your address</Link>
                </>
              )}
            </p>
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
      <BuyerNav orgId={orgId} />
      <h1>Your deals</h1>
      {labelError && <p className="error" role="alert">{labelError}</p>}
      {!data && <Loading />}
      {data?.length === 0 && (
        <EmptyState title="You haven't bought any cards yet.">
          <p>Cards you buy show here with their labels.</p>
        </EmptyState>
      )}
      {data?.map((deal) => (
        <section key={deal.listingId} className="panel">
          <div className="listing-top">
            <div>
              <CardTitle card={deal.card} />
            </div>
            <p className="offer-price">{money(deal.offer.price)}</p>
          </div>
          <Photos photos={deal.photos} />
          <p className="small">
            Bought for {money(deal.offer.price)} on {new Date(deal.acceptedAt).toLocaleDateString()} from {deal.seller.orgName ?? 'a seller'}
            {deal.seller.email && (
              <>
                {' '}
                (<a href={`mailto:${deal.seller.email}`}>{deal.seller.email}</a>)
              </>
            )}
            .
          </p>
          {deal.cancelled ? (
            <p className="alert alert-danger">
              We cancelled this deal on {new Date(deal.cancelled.at).toLocaleDateString()}: {deal.cancelled.reason}. You don't pay for
              it, and its label isn't billed to you.
            </p>
          ) : deal.label ? (
            <p className="alert alert-info small">
              {deal.label.carrier} {deal.label.service}
              {deal.label.trackingCode && `, tracking ${deal.label.trackingCode}`}, insured for {money(deal.label.insuredValue)}.
            </p>
          ) : (
            <div className="alert alert-warning">
              <p>No label yet.</p>
              <div className="actions">
                <button className="secondary" onClick={() => retryLabel(deal.listingId)}>
                  Try the label again
                </button>
              </div>
            </div>
          )}
          {deal.outcome ? (
            <p className="alert alert-success">
              {deal.outcome.type === 'RECEIVED' && `Arrived; you recorded paying ${money(deal.outcome.pricePaid)}.`}
              {deal.outcome.type === 'RETURNED' && deal.outcome.returnedReason && `Returned: ${RETURN_REASON[deal.outcome.returnedReason]}.`}
              {deal.outcome.type === 'INSPECTED' && `Inspected at PSA ${deal.outcome.inspectedGrade}.`}
            </p>
          ) : deal.cancelled ? null : deal.label ? (
            <ArrivalForm path={apiPath`/api/orgs/${orgId}/deals/${deal.listingId}`} price={deal.offer.price} onSaved={reload} />
          ) : (
            <p className="muted small">Once the label is bought and the card ships, record here how it arrived.</p>
          )}
        </section>
      ))}
    </>
  )
}

/**
 * Once the card is in hand: it came as shown and you paid, or it goes back for one of the allowed reasons.
 *
 * @param path the deal's API path: the buyer's, or the admin's (which adds ?buyerOrgId and needs a note)
 * @param price the firm price: paying anything else needs a reason
 */
export function ArrivalForm({
  path,
  price,
  onSaved,
  noteRequired = false,
}: {
  path: string
  price: number
  onSaved: () => void
  noteRequired?: boolean
}) {
  const api = useApi()
  const [returning, setReturning] = useState(false)
  const [paid, setPaid] = useState(String(price))
  // No default: the reason is data, and a preselected one would skew it.
  const [reason, setReason] = useState<DeclinedReason | ''>('')
  const [note, setNote] = useState('')
  const differs = !returning && parseMoney(paid) !== price
  const needsNote = noteRequired || differs
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const [base, query] = path.split('?')
      const suffix = query ? `?${query}` : ''
      if (returning) {
        await api('POST', `${base}/returned${suffix}`, { reason, note: note || null })
      } else {
        await api('POST', `${base}/received${suffix}`, { pricePaid: parseMoney(paid), note: note || null })
      }
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="form" aria-describedby={formError.describedBy}>
      <h3>When it arrives</h3>
      <div className="choices">
        <label className="choice">
          <input type="radio" checked={!returning} onChange={() => setReturning(false)} /> Arrived as shown, paid
        </label>
        <label className="choice">
          <input type="radio" checked={returning} onChange={() => setReturning(true)} /> Sending it back
        </label>
      </div>
      {returning ? (
        <Field label="Return reason">
          {(control) => (
            <select
              {...control}
              value={reason}
              onChange={(e) => setReason(e.target.value as DeclinedReason)}
              aria-label="Return reason"
              required
            >
              <option value="" disabled>
                choose a reason
              </option>
              {(Object.keys(RETURN_REASON) as DeclinedReason[]).map((r) => (
                <option key={r} value={r}>
                  {RETURN_REASON[r]}
                </option>
              ))}
            </select>
          )}
        </Field>
      ) : (
        <Field label="Price paid ($)" hint={differs ? `That's not the ${money(price)} offer: say why in the note.` : undefined}>
          {(control) => <MoneyInput {...control} value={paid} onChange={setPaid} min={0} className="narrow" aria-label="Price paid" required />}
        </Field>
      )}
      <Field label={needsNote ? 'Note' : 'Note (optional)'}>
        {(control) => (
          <input
            {...control}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder={noteRequired ? 'How the card shipped, or why this is recorded by hand' : differs ? 'Why you paid a different amount' : 'Note (optional)'}
            aria-label="Note"
            maxLength={2000}
            required={needsNote}
          />
        )}
      </Field>
      <div className="actions">
        <button type="submit" disabled={busy} aria-busy={busy}>
          Record
        </button>
      </div>
      {formError.message}
    </form>
  )
}
