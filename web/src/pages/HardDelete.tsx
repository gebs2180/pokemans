import { useState, type FormEvent } from 'react'
import { useApi, type DeletionDone, type DeletionPlan } from '../api'
import { message } from '../format'

/** Table names in words, for the list of what goes. Anything not here shows as its table name. */
const ROW_LABEL: Record<string, string> = {
  card_item: 'cards',
  photo: 'photo records',
  card_offer: 'offers',
  card_offer_void: 'offer voids',
  sale_listing: 'listings',
  listing_event: 'deal steps',
  shipping_label: 'shipping labels',
  label_refund: 'label refunds',
  deal_outcome: 'deal outcomes',
  condition_assessment: 'condition assessments',
  card_identification: 'identifications',
  membership: 'memberships',
  invite: 'invites',
  org: 'org',
}

/**
 * Admin: the hard delete of a test or junk card or org. Shows what will go (or what holds it) first, and deletes only
 * once the name is typed exactly. Nothing is deleted on a hold: the server refuses too.
 */
export function HardDelete({
  previewPath,
  deletePath,
  what,
  onDeleted,
}: {
  previewPath: string
  deletePath: string
  what: 'card' | 'org'
  onDeleted: (done: DeletionDone) => void
}) {
  const api = useApi()
  const [plan, setPlan] = useState<DeletionPlan | null>(null)
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function open() {
    setError(null)
    setBusy(true)
    try {
      setPlan(await api<DeletionPlan>('GET', previewPath))
    } catch (err) {
      setError(message(err))
    } finally {
      setBusy(false)
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    if (!plan) return
    setError(null)
    setBusy(true)
    try {
      onDeleted(await api<DeletionDone>('DELETE', `${deletePath}?confirm=${encodeURIComponent(typed)}`))
    } catch (err) {
      setError(message(err))
    } finally {
      setBusy(false)
    }
  }

  if (!plan) {
    return (
      <>
        <button className="link" onClick={open} disabled={busy}>
          {busy ? 'Checking…' : `Delete ${what}…`}
        </button>
        {error && <p className="error" role="alert">{error}</p>}
      </>
    )
  }

  const rows = Object.entries(plan.rows)
  return (
    <div className="panel" role="region" aria-label={`Delete ${what}`}>
      <h3>
        Delete {what} “{plan.name}” for good
      </h3>
      {plan.holds.length > 0 ? (
        <>
          <p className="error" role="alert">
            It can't be deleted: a trade is still in play.
          </p>
          <ul>
            {[...new Set(plan.holds.map((h) => h.message))].map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
        </>
      ) : (
        <form onSubmit={confirm}>
          <p>
            This can't be undone. {what === 'org' && `${plan.cards} card${plan.cards === 1 ? '' : 's'} and ${plan.photos} photo${plan.photos === 1 ? '' : 's'} go with it. Its people's accounts stay; only their memberships go. `}
            {what === 'card' && `${plan.photos} photo${plan.photos === 1 ? '' : 's'} go with it, from storage too. `}
            It removes:
          </p>
          <ul className="small">
            {rows.map(([table, n]) => (
              <li key={table}>
                {n} {ROW_LABEL[table] ?? table.replaceAll('_', ' ')}
              </li>
            ))}
          </ul>
          <label>
            Type <strong>{plan.name}</strong> to confirm
            <input value={typed} onChange={(e) => setTyped(e.target.value)} aria-label={`Type the ${what}'s name to confirm`} autoComplete="off" />
          </label>{' '}
          <button type="submit" className="danger" disabled={busy || typed.trim() !== plan.name.trim()}>
            {busy ? 'Deleting…' : `Delete ${what} for good`}
          </button>
        </form>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <button className="link" onClick={() => { setPlan(null); setTyped(''); setError(null) }} disabled={busy}>
        Cancel
      </button>
    </div>
  )
}
