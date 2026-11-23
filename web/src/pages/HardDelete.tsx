import { useState, type FormEvent } from 'react'
import { useApi, type DeletionDone, type DeletionPlan } from '../api'
import { message } from '../format'

/** Table names in words, one and many (review finding 20). Anything not here shows as its table name. */
const ROW_LABEL: Record<string, [one: string, many: string]> = {
  card_item: ['card', 'cards'],
  photo: ['photo record', 'photo records'],
  card_offer: ['offer', 'offers'],
  card_offer_void: ['offer void', 'offer voids'],
  sale_listing: ['listing', 'listings'],
  listing_event: ['deal step', 'deal steps'],
  shipping_label: ['shipping label', 'shipping labels'],
  label_refund: ['label refund', 'label refunds'],
  deal_outcome: ['deal outcome', 'deal outcomes'],
  condition_assessment: ['condition assessment', 'condition assessments'],
  card_identification: ['identification', 'identifications'],
  membership: ['membership', 'memberships'],
  invite: ['invite', 'invites'],
  org: ['org', 'orgs'],
}

function rowLabel(table: string, n: number) {
  const words = ROW_LABEL[table]
  return words ? words[n === 1 ? 0 : 1] : table.replaceAll('_', ' ')
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
                {n} {rowLabel(table, n)}
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
