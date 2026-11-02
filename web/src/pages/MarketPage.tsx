import { useState, type FormEvent } from 'react'
import { Link, NavLink, useParams } from 'react-router'
import {
  apiPath,
  money,
  parseMoney,
  PAYMENT_METHOD,
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
              <p>
                You buy it at this price, whatever it grades. You pay the seller directly, within 7 days of it arriving, and we bill you
                the postage monthly.
              </p>
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
            {!deal.cancelled && !deal.outcome && ' Pay them directly, within 7 days of it arriving as shown. Rawmark doesn\'t handle the money.'}
          </p>
          {deal.payTo ? (
            !deal.progress?.paidAt && (
              <p className="small">
                Pay them by <strong>{PAYMENT_METHOD[deal.payTo.method]}</strong>: <strong>{deal.payTo.handle}</strong>
              </p>
            )
          ) : (
            !deal.cancelled &&
            !deal.progress?.paidAt &&
            deal.outcome?.type !== 'RETURNED' && <p className="muted small">The seller hasn't said how to pay them yet: email them, or check back here.</p>
          )}
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
            <DealOutcome deal={deal} path={apiPath`/api/orgs/${orgId}/deals/${deal.listingId}`} onSaved={reload} />
          ) : deal.cancelled ? null : deal.label ? (
            <ArrivalForm path={apiPath`/api/orgs/${orgId}/deals/${deal.listingId}`} onSaved={reload} />
          ) : (
            <p className="muted small">Once the label is bought and the card ships, record here how it arrived.</p>
          )}
        </section>
      ))}
    </>
  )
}

/** What's been recorded on a deal, and what the buyer does next: pay by the date, or send it back. */
function DealOutcome({ deal, path, onSaved }: { deal: Deal; path: string; onSaved: () => void }) {
  const { outcome, progress } = deal
  const date = (at: string) => new Date(at).toLocaleDateString()
  return (
    <>
      {outcome?.type === 'RECEIVED' && (
        <div className={progress?.paidAt ? 'alert alert-success' : 'alert alert-info'}>
          <p>Arrived on {date(outcome.recordedAt)}.</p>
          {progress?.paidAt ? (
            <p>
              You recorded paying {money(progress.pricePaid)} on {date(progress.paidAt)}.
            </p>
          ) : (
            progress?.payBy && (
              <p>
                Pay {money(deal.offer.price)} by <strong>{date(progress.payBy)}</strong>.
              </p>
            )
          )}
          {progress?.confirmedAt && <p>The seller confirmed they got paid.</p>}
        </div>
      )}
      {outcome?.type === 'RETURNED' && outcome.returnedReason && (
        <div className="alert alert-warning">
          <p>Returned: {RETURN_REASON[outcome.returnedReason]}.</p>
          {outcome.note && <p>You said: "{outcome.note}"</p>}
          {progress?.stage === 'BACK' ? (
            <p className="small">The seller has it back.</p>
          ) : (
            <p className="small">Send it back tracked, at your cost, within 7 days of it arriving. You don't pay for it. Zach looks at every return.</p>
          )}
        </div>
      )}
      {outcome?.type === 'INSPECTED' && <p className="alert alert-success">Inspected at PSA {outcome.inspectedGrade}.</p>}
      {progress?.disputedAt && (
        <p className="alert alert-warning small">
          The seller told us about this deal on {date(progress.disputedAt)}
          {outcome?.type !== 'RETURNED' && ", saying they haven't been paid"}. Zach will be in touch.
        </p>
      )}
      {progress?.review && (
        <p className="small">
          From Zach, {date(progress.review.at)}: {progress.review.reason}
        </p>
      )}
      {outcome?.type === 'RECEIVED' && !progress?.paidAt && <PaidForm path={path} price={deal.offer.price} onSaved={onSaved} />}
    </>
  )
}

/**
 * Once you've paid the seller, off the app. Only a record of what you did: Rawmark doesn't handle the money.
 *
 * @param path the deal's API path
 * @param price the firm price: paying anything else needs a reason
 */
export function PaidForm({ path, price, onSaved }: { path: string; price: number; onSaved: () => void }) {
  const api = useApi()
  const [paid, setPaid] = useState(String(price))
  const [note, setNote] = useState('')
  const differs = parseMoney(paid) !== price
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!confirming) {
      setConfirming(true)
      return
    }
    setBusy(true)
    try {
      await api('POST', `${path}/paid`, { pricePaid: parseMoney(paid), note: note || null })
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="form" aria-describedby={formError.describedBy}>
      <h3>Once you've paid</h3>
      <Field label="Price paid ($)" hint={differs ? `That's not the ${money(price)} offer: say why in the note.` : undefined}>
        {(control) => (
          <MoneyInput
            {...control}
            value={paid}
            onChange={(v) => {
              setConfirming(false)
              setPaid(v)
            }}
            min={0}
            className="narrow"
            aria-label="Price paid"
            required
          />
        )}
      </Field>
      <Field label={differs ? 'Note' : 'Note (optional)'}>
        {(control) => (
          <input
            {...control}
            value={note}
            onChange={(e) => {
              setConfirming(false)
              setNote(e.target.value)
            }}
            placeholder={differs ? 'Why you paid a different amount' : 'Note (optional)'}
            aria-label="Note"
            maxLength={2000}
            required={differs}
          />
        )}
      </Field>
      {confirming ? (
        <div className="confirm">
          <p>Record that you paid {money(parseMoney(paid))}? It can't be changed afterwards, and the seller sees it.</p>
          <div className="actions">
            <button type="submit" disabled={busy} aria-busy={busy}>
              Confirm: record it
            </button>
            <button type="button" className="secondary" onClick={() => setConfirming(false)}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="actions">
          <button type="submit">I've paid</button>
        </div>
      )}
      {formError.message}
    </form>
  )
}

/**
 * Once the card is in hand: it came as shown, or it goes back for one of the allowed reasons. Paying is a
 * step of its own, since the buyer has 7 days.
 *
 * @param path the deal's API path: the buyer's, or the admin's (which adds ?buyerOrgId and needs a note)
 */
export function ArrivalForm({ path, onSaved, noteRequired = false }: { path: string; onSaved: () => void; noteRequired?: boolean }) {
  const api = useApi()
  // Hidden behind "It arrived", so nothing can be recorded the minute a card is bought. The admin came here to record one.
  const [open, setOpen] = useState(noteRequired)
  // No default for either: nothing can be recorded by accident, and a preselected reason would skew the data.
  const [choice, setChoice] = useState<'arrived' | 'returning' | null>(null)
  const returning = choice === 'returning'
  const [reason, setReason] = useState<DeclinedReason | ''>('')
  const [note, setNote] = useState('')
  const notGenuine = returning && reason === 'NOT_GENUINE'
  const needsNote = noteRequired || notGenuine
  const [busy, setBusy] = useState(false)
  // Recording can't be undone, and the seller sees it: it asks once more, and any change asks again.
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!confirming) {
      setConfirming(true)
      return
    }
    setBusy(true)
    try {
      const [base, query] = path.split('?')
      const suffix = query ? `?${query}` : ''
      if (returning) {
        await api('POST', `${base}/returned${suffix}`, { reason, note: note || null })
      } else {
        await api('POST', `${base}/received${suffix}`, { note: note || null })
      }
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  /** Runs a field's change, and takes back the confirm step: what's being confirmed has changed. */
  function changing<T>(set: (value: T) => void) {
    return (value: T) => {
      setConfirming(false)
      set(value)
    }
  }

  if (!open) {
    return (
      <div className="actions">
        <button className="secondary" onClick={() => setOpen(true)}>
          It arrived
        </button>
      </div>
    )
  }
  return (
    <form onSubmit={save} className="form" aria-describedby={formError.describedBy}>
      <h3>When it arrives</h3>
      <p className="muted small">Record this once the card is in your hands. It can't be changed afterwards, and the seller sees it.</p>
      <div className="choices">
        <label className="choice">
          <input type="radio" name="arrival" checked={choice === 'arrived'} onChange={() => changing(setChoice)('arrived')} /> Arrived as shown
        </label>
        <label className="choice">
          <input type="radio" name="arrival" checked={returning} onChange={() => changing(setChoice)('returning')} /> Sending it back
        </label>
      </div>
      {returning && (
        <Field label="Return reason">
          {(control) => (
            <select
              {...control}
              value={reason}
              onChange={(e) => changing(setReason)(e.target.value as DeclinedReason)}
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
      )}
      {choice && (
        <Field label={needsNote ? 'Note' : 'Note (optional)'}>
          {(control) => (
            <input
              {...control}
              value={note}
              onChange={(e) => changing(setNote)(e.target.value)}
              placeholder={noteRequired ? 'How the card shipped, or why this is recorded by hand' : notGenuine ? "Why you think it isn't genuine" : 'Note (optional)'}
              aria-label="Note"
              maxLength={2000}
              required={needsNote}
            />
          )}
        </Field>
      )}
      {choice &&
        (confirming ? (
          <div className="confirm">
            <p>
              {notGenuine
                ? "Say this card isn't genuine? That's a serious thing to tell a seller: they see it with your note, and Zach reviews it."
                : returning
                  ? `Record that you're sending it back: ${RETURN_REASON[reason as DeclinedReason]}?`
                  : 'Record that it arrived as shown? The 7 days to pay start now.'}
            </p>
            <div className="actions">
              <button type="submit" className={notGenuine ? 'danger' : undefined} disabled={busy} aria-busy={busy}>
                {notGenuine ? "Confirm: it isn't genuine" : 'Confirm: record it'}
              </button>
              <button type="button" className="secondary" onClick={() => setConfirming(false)}>
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <div className="actions">
            <button type="submit">Record</button>
          </div>
        ))}
      {formError.message}
    </form>
  )
}
