import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
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
  type Offer,
  type OfferCard,
  type PhotoLink,
  type PostageEstimate,
  type ShipAddress,
} from '../api'
import { MoneyInput } from '../inputs'
import { safeHref } from '../safeHref'
import { Chip, EmptyState, Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { AddressForm } from './AddressForm'
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

/**
 * The photos the offer was priced on, one per side, labelled: what "matches its photos" is checked against. Tapping one
 * opens them full screen, as taken (UX review finding 8): buyers commit on these, so they get every pixel the seller sent.
 */
export function Photos({ photos }: { photos: PhotoLink[] }) {
  const shown = photos.flatMap((p) => {
    const thumb = safeHref(p.url)
    const full = safeHref(p.fullUrl) ?? thumb
    return thumb && full ? [{ side: p.side, thumb, full }] : []
  })
  const [open, setOpen] = useState<number | null>(null)
  return (
    <>
      <div className="photos">
        {shown.map((p, i) => (
          <button key={p.thumb} type="button" className="photo compact photo-button" onClick={() => setOpen(i)}>
            <img src={p.thumb} alt={`${SIDE_LABEL[p.side]} photo`} className="thumb" />
            <span className="muted small">{SIDE_LABEL[p.side]}</span>
          </button>
        ))}
      </div>
      {open != null && <PhotoViewer photos={shown} start={open} onClose={() => setOpen(null)} />}
    </>
  )
}

/**
 * Full screen, one photo at a time: swipe or use the arrows to move, tap the photo for its actual size and drag (or pinch)
 * to look closer. Nothing is cropped or scaled up.
 */
function PhotoViewer({ photos, start, onClose }: { photos: { side: PhotoLink['side']; full: string }[]; start: number; onClose: () => void }) {
  const [index, setIndex] = useState(start)
  const [actualSize, setActualSize] = useState(false)
  const track = useRef<HTMLDivElement>(null)
  const close = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    close.current?.focus()
    track.current?.scrollTo?.({ left: start * track.current.clientWidth })
  }, [start])

  function go(to: number) {
    const next = Math.max(0, Math.min(photos.length - 1, to))
    setActualSize(false)
    setIndex(next)
    track.current?.scrollTo?.({ left: next * track.current.clientWidth, behavior: 'smooth' })
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === 'Escape') onClose()
    else if (e.key === 'ArrowRight') go(index + 1)
    else if (e.key === 'ArrowLeft') go(index - 1)
  }

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label="Photos" onKeyDown={onKey}>
      <div className="viewer-bar">
        <span>
          {SIDE_LABEL[photos[index].side]} ({index + 1} of {photos.length})
        </span>
        <button type="button" className="secondary" onClick={() => setActualSize((a) => !a)}>
          {actualSize ? 'Fit to screen' : 'Actual size'}
        </button>
        <button ref={close} type="button" className="secondary" onClick={onClose}>
          Close
        </button>
      </div>
      <div
        ref={track}
        className={actualSize ? 'viewer-track locked' : 'viewer-track'}
        onScroll={(e) => {
          const t = e.currentTarget
          if (t.clientWidth) setIndex(Math.round(t.scrollLeft / t.clientWidth))
        }}
      >
        {photos.map((p, i) => (
          <figure key={p.full} className={actualSize && i === index ? 'viewer-slide actual' : 'viewer-slide'}>
            <img src={p.full} alt={`${SIDE_LABEL[p.side]} photo, full size`} onClick={() => i === index && setActualSize((a) => !a)} />
          </figure>
        ))}
      </div>
      {photos.length > 1 && (
        <div className="viewer-bar viewer-nav">
          <button type="button" className="secondary" onClick={() => go(index - 1)} disabled={index === 0}>
            ← Previous
          </button>
          <button type="button" className="secondary" onClick={() => go(index + 1)} disabled={index === photos.length - 1}>
            Next →
          </button>
        </div>
      )}
    </div>
  )
}

/** The grade the card is most likely to be and its chance; a tie goes to the lower grade. */
function mostLikely(offer: Offer) {
  let best = null as Offer['ladder'][number] | null
  for (const r of offer.ladder) if (r.probability > 0 && (!best || r.probability >= best.probability)) best = r
  if (!best) return null
  return `${best.grade === 'U' ? 'Ungradable' : `PSA ${best.grade}`} (${Math.round(best.probability * 100)}%)`
}

/** What the buyer is billed monthly for the label, as an estimate: nothing is bought until they accept. */
function PostageLine({ estimate, price }: { estimate: PostageEstimate; price: number }) {
  const how = estimate.quoted
    ? `${estimate.carrier} ${estimate.service} ${money(estimate.postage)}`
    : `our usual postage of ${money(estimate.postage)}; the label may cost a little more or less`
  return (
    <p>
      Postage and insurance: about <strong>{money(estimate.total)}</strong> ({how}, plus {money(estimate.insurance)} to insure it for{' '}
      {money(price)}), billed to you monthly.
    </p>
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
  // Wrapped, so "no address on file" (an empty response) is told apart from "still loading".
  const { data: shipTo, reload: reloadAddress } = useLoad(
    () => api<ShipAddress | null>('GET', apiPath`/api/orgs/${orgId}/ship-address`).then((address) => ({ address })),
    [api, orgId],
  )
  const [confirming, setConfirming] = useState<string | null>(null)
  const [postage, setPostage] = useState<{ listingId: string; estimate?: PostageEstimate; error?: string } | null>(null)
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

  async function confirm(listingId: string) {
    setConfirming(listingId)
    setPostage({ listingId })
    try {
      const estimate = await api<PostageEstimate>('GET', apiPath`/api/orgs/${orgId}/market/${listingId}/postage`)
      setPostage((p) => (p?.listingId === listingId ? { listingId, estimate } : p))
    } catch (e) {
      setPostage((p) => (p?.listingId === listingId ? { listingId, error: e instanceof Error ? e.message : String(e) } : p))
    }
  }

  if (error) return <PageError message={error} />
  // The label goes to the buyer's address, and the postage depends on it: ask for it before showing anything to buy.
  if (shipTo && shipTo.address == null) {
    return (
      <>
        <BuyerNav orgId={orgId} />
        <h1>Where should cards ship to?</h1>
        <p className="muted">
          Before you see what's for sale: the seller gets a prepaid label to this address, and we use it to estimate your postage.
        </p>
        <section className="panel">
          <AddressForm orgId={orgId} onSaved={reloadAddress} />
        </section>
      </>
    )
  }
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
      {(!data || !shipTo) && <Loading />}
      {shipTo && data?.length === 0 && (
        <EmptyState title="Nothing posted right now.">
          <p>New cards show here as collectors post them.</p>
        </EmptyState>
      )}
      {shipTo &&
        data?.map((item) => {
          const likely = item.offer.graded ? null : mostLikely(item.offer)
          const closeUp = item.photos.some((p) => p.side === 'DETAIL')
          const quote = postage?.listingId === item.listingId ? postage : null
          return (
            <section key={item.listingId} className="panel">
              <div className="listing-top">
                <div>
                  <CardTitle card={item.card} />
                </div>
                <p className="offer-price">{money(item.offer.price)}</p>
              </div>
              {(likely || closeUp) && (
                <p className="chips">
                  {likely && <Chip tone="info">Most likely {likely}</Chip>}
                  {closeUp && <Chip tone="success">Close-up included</Chip>}
                </p>
              )}
              <Photos photos={item.photos} />
              {item.offer.subjectToAuthentication && <p className="alert alert-warning small">Not yet authenticated: check it in hand.</p>}
              <HowPriced offer={item.offer} />
              <p className="meta">
                Posted {new Date(item.listedAt).toLocaleDateString()}, open until {new Date(item.expiresAt).toLocaleDateString()}.
              </p>
              {confirming === item.listingId ? (
                <div className="confirm">
                  <p>You buy it at this price, whatever it grades.</p>
                  {quote?.estimate ? (
                    <PostageLine estimate={quote.estimate} price={item.offer.price} />
                  ) : quote?.error ? (
                    <p>We couldn't estimate the postage ({quote.error.replace(/\.$/, '')}). It's billed to you monthly.</p>
                  ) : (
                    <p className="muted">Estimating postage and insurance…</p>
                  )}
                  <p>
                    You pay the seller {money(item.offer.price)} directly, <strong>within 7 days of it arriving</strong>.
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
                  <button onClick={() => confirm(item.listingId)}>Buy</button>
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
          )
        })}
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

  if (error) return <PageError message={error} />
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
