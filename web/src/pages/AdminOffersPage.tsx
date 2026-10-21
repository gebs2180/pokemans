import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, money, useApi, type AdminListing, type AdminOffer, type Undo } from '../api'
import { useLoad } from '../useLoad'

const STATUS: Record<AdminListing['status'], string> = {
  OPEN: 'On the market',
  ACCEPTED: 'Taken',
  WITHDRAWN: 'Withdrawn by the seller',
  EXPIRED: 'Expired',
  VOIDED: 'Taken down (voided)',
  CANCELLED: 'Deal cancelled',
}

function undone(what: string, u: Undo) {
  return `${what} ${new Date(u.at).toLocaleString()}${u.by ? ` by ${u.by}` : ''}: ${u.reason}`
}

/** An undo that needs a reason: the button opens a one-line form, and the reason is kept with the new row. */
export function UndoAction({ label, path, onDone }: { label: string; path: string; onDone: () => void }) {
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
        placeholder="Why? (kept with the record)"
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
 * Admin: the newest offers and what became of them, with the undo (2026-10-21). Nothing is edited:
 * voiding an offer, cancelling a deal and refunding a label each add a row with who, when and why.
 */
export function AdminOffersPage() {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<AdminOffer[]>('GET', '/api/admin/offers'), [api])

  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link> · <Link to="/admin/offers">Offer track record →</Link> ·{' '}
        <Link to="/admin/shipping">Postage to bill →</Link>
      </p>
      <h1>Offers and deals</h1>
      <p className="muted">
        The newest 200 offers. Void an offer to take it off the market; cancel a deal a buyer took, before it arrives, to free the card for a
        new offer; then mark its label to be refunded.
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      {!data && !error && <p>Loading…</p>}
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
