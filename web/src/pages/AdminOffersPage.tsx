import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, money, RETURN_REASON, useApi, type AdminListing, type AdminOffer, type AttentionKind, type DealAttention, type Undo } from '../api'
import { useLoad } from '../useLoad'
import { Loading } from '../ui'

const STATUS: Record<AdminListing['status'], string> = {
  OPEN: 'On the market',
  ACCEPTED: 'Taken',
  WITHDRAWN: 'Withdrawn by the seller',
  EXPIRED: 'Expired',
  VOIDED: 'Taken down (voided)',
  CANCELLED: 'Deal cancelled',
  BACK: 'Returned, back with the seller',
}

const ATTENTION: Record<AttentionKind, string> = {
  NOT_PAID: "Seller says they haven't been paid",
  RETURNED: 'Return to review',
  RETURN_DISPUTED: 'Seller disagrees with the return',
}

function undone(what: string, u: Undo) {
  return `${what} ${new Date(u.at).toLocaleString()}${u.by ? ` by ${u.by}` : ''}: ${u.reason}`
}

/** An undo that needs a reason: the button opens a one-line form, and the reason is kept with the new row. */
export function UndoAction({
  label,
  path,
  onDone,
  placeholder = 'Why? (kept with the record)',
}: {
  label: string
  path: string
  onDone: () => void
  placeholder?: string
}) {
  const api = useApi()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api('POST', path, { reason })
      setOpen(false)
      setReason('')
      onDone()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button className="link" onClick={() => setOpen(true)}>
        {label}
      </button>
    )
  }
  return (
    <form onSubmit={submit} className="row">
      <input
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder={placeholder}
        aria-label={`${label}: reason`}
        required
        maxLength={2000}
      />
      <button type="submit" disabled={busy || !reason.trim()}>
        {label}
      </button>
      <button type="button" className="link" onClick={() => setOpen(false)}>
        Never mind
      </button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

/**
 * What's waiting on the admin after a sale: sellers who say they weren't paid, and returns, each with who
 * to contact. Rawmark doesn't handle the money, so acting on one means talking to them, then closing it
 * here with a note both sides see.
 */
function NeedsAttention({ items, onDone }: { items: DealAttention[]; onDone: () => void }) {
  if (items.length === 0) return <p className="muted">Nothing is waiting on you.</p>
  return (
    <>
      {items.map((a) => {
        const held = a.progress?.held
        return (
          <section key={a.listingId} className="panel" aria-label={`Needs attention: ${a.card.name}`}>
            <h3>
              {ATTENTION[a.kind]}: <Link to={`/admin/review/${a.sellerOrgId}/${a.cardItemId}`}>{a.card.name}</Link>
            </h3>
            <p>
              {money(a.price)}, raised {new Date(a.since).toLocaleDateString()}.
              {a.progress?.payBy && a.kind === 'NOT_PAID' && ` Pay-by ${new Date(a.progress.payBy).toLocaleDateString()}.`}
              {a.progress?.paidAt && ` The buyer says they paid ${money(a.progress.pricePaid)} on ${new Date(a.progress.paidAt).toLocaleDateString()}.`}
              {a.trackingCode && ` Tracking ${a.trackingCode}.`}
            </p>
            {a.outcome?.returnedReason && (
              <p>
                Returned: {RETURN_REASON[a.outcome.returnedReason]}
                {a.outcome.note && `: "${a.outcome.note}"`}
              </p>
            )}
            {a.note && a.kind !== 'RETURNED' && <p>The seller says: "{a.note}"</p>}
            <p className="small">
              Buyer {a.buyer.orgName ?? 'unknown'}
              {a.buyer.email && ` (${a.buyer.email})`} · seller {a.seller.orgName ?? 'unknown'}
              {a.seller.email && ` (${a.seller.email})`}
            </p>
            {held && (
              <p className="warn">
                This card can't get a new offer until you close this. If it looks fake, record that on its review page first.
              </p>
            )}
            <UndoAction
              label="Close"
              path={apiPath`/api/admin/deals/${a.listingId}/resolve`}
              onDone={onDone}
              placeholder="What happened? Both sides see this."
            />
          </section>
        )
      })}
    </>
  )
}

/**
 * Admin: the newest offers and what became of them, with the undo (2026-10-21). Nothing is edited:
 * voiding an offer, cancelling a deal and refunding a label each add a row with who, when and why.
 */
export function AdminOffersPage() {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<AdminOffer[]>('GET', '/api/admin/offers'), [api])
  const { data: attention, reload: reloadAttention } = useLoad(() => api<DealAttention[]>('GET', '/api/admin/deals/attention'), [api])

  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link> · <Link to="/admin/offers">Offer track record →</Link> ·{' '}
        <Link to="/admin/shipping">Postage to bill →</Link>
      </p>
      <h1>Offers and deals</h1>
      <h2>Needs you{attention && attention.length > 0 && ` (${attention.length})`}</h2>
      {attention && (
        <NeedsAttention
          items={attention}
          onDone={() => {
            reloadAttention()
            reload()
          }}
        />
      )}
      <h2>Newest offers</h2>
      <p className="muted">
        The newest 200 offers. Void an offer to take it off the market; cancel a deal a buyer took, before it arrives, to free the card for a
        new offer; then mark its label to be refunded.
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      {!data && !error && <Loading />}
      {data?.length === 0 && <p className="muted">No offers yet.</p>}
      {data?.map((o) => {
        const l = o.listing
        const canVoid = !o.voided && l?.status !== 'ACCEPTED' && l?.status !== 'CANCELLED'
        const canCancel = l?.status === 'ACCEPTED' && !l.outcome
        const canRefund = l?.status === 'CANCELLED' && l.label && !l.labelRefund?.providerStatus
        return (
          <section key={o.offerId} className="panel" aria-label={`Offer on ${o.card.name}`}>
            <h2>
              {o.card.name} <span className="muted small">{o.card.setName} #{o.card.number}</span>
            </h2>
            <p>
              {money(o.price)} to {o.sellerName ?? 'a seller'}, offered {new Date(o.createdAt).toLocaleDateString()}{' '}
              <span className="muted small">
                (FMV {money(o.fmv)}, confidence {o.confidence}
                {o.expired && ', expired'})
              </span>
            </p>
            {o.voided && <p className="warn">{undone('Voided', o.voided)}</p>}
            {l && (
              <p>
                {STATUS[l.status]}
                {l.buyer && ` by ${l.buyer.orgName ?? 'a buyer'}`}
                {l.outcome && `, ${l.outcome.toLowerCase()}`}
                {l.label && (
                  <span className="muted small">
                    {' '}
                    · {l.label.carrier} label{l.label.trackingCode && ` ${l.label.trackingCode}`}
                  </span>
                )}
              </p>
            )}
            {l?.status === 'CANCELLED' && l.undone && <p className="warn">{undone('Cancelled', l.undone)}</p>}
            {l?.labelRefund && (
              <p className={l.labelRefund.providerStatus ? 'muted small' : 'warn'}>
                {undone('Label refund asked for', l.labelRefund)}.{' '}
                {l.labelRefund.providerStatus ? `Provider: ${l.labelRefund.providerStatus}.` : l.labelRefund.providerError}
              </p>
            )}
            <p>
              {canVoid && <UndoAction label="Void offer" path={apiPath`/api/admin/offers/${o.offerId}/void`} onDone={reload} />}
              {canCancel && <UndoAction label="Cancel deal" path={apiPath`/api/admin/deals/${l.id}/cancel`} onDone={reload} />}
              {canRefund && <UndoAction label="Refund label" path={apiPath`/api/admin/deals/${l.id}/label/refund`} onDone={reload} />}
            </p>
          </section>
        )
      })}
    </>
  )
}
