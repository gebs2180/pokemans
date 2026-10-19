import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath, money, useApi, type Deal, type DeclinedReason, type MarketItem, type OfferCard } from '../api'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'
import { OfferLadder } from './OfferPanel'

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

/** Buyers: raw cards posted at our offer. The first buyer to accept takes the card. */
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
      await api('POST', apiPath`/api/orgs/${orgId}/market/${listingId}/accept`)
      setMessage('Taken: it is in your deals, with the seller to contact.')
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
        Each card is offered at a price for every grade it could inspect at, judged on the PSA scale. Accepting commits you to pay
        the price for the grade you find in hand.
      </p>
      {message && <p role="status">{message}</p>}
      {!data && <p>Loading…</p>}
      {data?.length === 0 && <p className="muted">Nothing posted right now.</p>}
      {data?.map((item) => (
        <section key={item.listingId} className="panel">
          <CardTitle card={item.card} />
          <Photos photos={item.photos} />
          <OfferLadder offer={item.offer} />
          <p className="muted small">Posted {new Date(item.listedAt).toLocaleDateString()}, open until {new Date(item.expiresAt).toLocaleDateString()}.</p>
          {confirming === item.listingId ? (
            <p className="row">
              <button onClick={() => accept(item.listingId)} disabled={busy}>
                Confirm: I commit to buy at these prices
              </button>
              <button className="link" onClick={() => setConfirming(null)}>
                Cancel
              </button>
            </p>
          ) : (
            <button onClick={() => setConfirming(item.listingId)}>Accept</button>
          )}
        </section>
      ))}
    </>
  )
}

/** Buyers: cards you took, the seller to contact, and the inspection to record once the card arrives. */
export function DealsPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<Deal[]>('GET', apiPath`/api/orgs/${orgId}/deals`), [api, orgId])

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <p>
        <Link to={`/orgs/${orgId}/market`}>← Cards for sale</Link>
      </p>
      <h1>Your deals</h1>
      {!data && <p>Loading…</p>}
      {data?.length === 0 && <p className="muted">You haven't taken any cards yet.</p>}
      {data?.map((deal) => (
        <section key={deal.listingId} className="panel">
          <CardTitle card={deal.card} />
          <p>
            Seller: {deal.seller.orgName ?? 'unknown'}
            {deal.seller.email && (
              <>
                {' '}
                (<a href={`mailto:${deal.seller.email}`}>{deal.seller.email}</a>)
              </>
            )}
            , taken {new Date(deal.acceptedAt).toLocaleDateString()}.
          </p>
          <OfferLadder offer={deal.offer} />
          {deal.inspection ? (
            <p className="ok">
              Recorded: PSA {deal.inspection.inspectedGrade}
              {deal.inspection.declinedReason
                ? `, declined (${deal.inspection.declinedReason.replace(/_/g, ' ').toLowerCase()})`
                : `, paid ${money(deal.inspection.pricePaid)} (offer at that grade ${money(deal.inspection.ladderPrice)})`}
              .
            </p>
          ) : (
            <InspectionForm orgId={orgId} listingId={deal.listingId} onSaved={reload} />
          )}
        </section>
      ))}
    </>
  )
}

export function InspectionForm({ orgId, listingId, onSaved }: { orgId: string; listingId: string; onSaved: () => void }) {
  const api = useApi()
  const [grade, setGrade] = useState('')
  const [authentic, setAuthentic] = useState(true)
  const [pricePaid, setPricePaid] = useState('')
  const [declined, setDeclined] = useState<DeclinedReason | ''>('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/deals/${listingId}/inspection`, {
        inspectedGrade: Number(grade),
        authentic,
        pricePaid: declined ? null : Number(pricePaid),
        declinedReason: declined || null,
        note: note || null,
      })
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="stack">
      <p className="muted small">Once the card is in hand: the grade you found (PSA scale, 0 if ungradable) and what you paid.</p>
      <div className="row">
        <label>
          Grade{' '}
          <input type="number" min={0} max={10} step={0.5} value={grade} onChange={(e) => setGrade(e.target.value)} className="narrow" aria-label="Inspected grade" required />
        </label>
        <label>
          <input type="checkbox" checked={authentic} onChange={(e) => setAuthentic(e.target.checked)} /> Genuine
        </label>
        <label>
          Declined because{' '}
          <select value={declined} onChange={(e) => setDeclined(e.target.value as DeclinedReason | '')} aria-label="Declined because">
            <option value="">not declined</option>
            <option value="NOT_GENUINE">not genuine</option>
            <option value="NOT_THE_LISTED_CARD">not the listed card</option>
            <option value="DOES_NOT_MATCH_PHOTOS">doesn't match its photos</option>
          </select>
        </label>
        {!declined && (
          <label>
            Paid ($){' '}
            <input type="number" min={0} step={0.01} value={pricePaid} onChange={(e) => setPricePaid(e.target.value)} className="narrow" aria-label="Price paid" required />
          </label>
        )}
      </div>
      <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" aria-label="Note" maxLength={2000} />
      <button type="submit" disabled={busy}>
        Record inspection
      </button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}
