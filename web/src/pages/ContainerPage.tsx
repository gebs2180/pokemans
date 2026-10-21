import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiPath, STAGE_LABEL, useApi, type CardStatus, type CardView, type ContainerDetail, type Me, type ReportView } from '../api'
import { useLoad } from '../useLoad'
import { ReportsList } from './ReportsPanel'

/** Lender and insurer reports are paused unless the build turns them on. */
const COLLATERAL = import.meta.env.VITE_COLLATERAL_ENABLED === 'true'

/** A batch or collection: its cards and their capture status. */
export function ContainerPage() {
  const { orgId = '', kind = '', containerId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data, error } = useLoad(
    () => api<ContainerDetail>('GET', apiPath`/api/orgs/${orgId}/${kind}/${containerId}`),
    [api, orgId, kind, containerId],
  )
  // Where each card is on the way to a sale; collections only (batches are for partners, who don't sell).
  const { data: statuses } = useLoad(
    () =>
      kind === 'collections'
        ? api<CardStatus[]>('GET', apiPath`/api/orgs/${orgId}/collections/${containerId}/status`).catch(() => [] as CardStatus[])
        : Promise.resolve([] as CardStatus[]),
    [api, orgId, kind, containerId],
  )
  const [label, setLabel] = useState('')
  const [lot, setLot] = useState(false)
  const [quantity, setQuantity] = useState(1)
  const [addError, setAddError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  if (kind !== 'batches' && kind !== 'collections') return <p>Page not found.</p>

  async function addCard(e: FormEvent) {
    e.preventDefault()
    setAddError(null)
    setAdding(true)
    try {
      const card = await api<CardView>('POST', apiPath`/api/orgs/${orgId}/${kind}/${containerId}/cards`, {
        label,
        lot,
        quantity: lot ? quantity : 1,
      })
      // Straight to its photos: that's the next step for a new card.
      navigate(`/orgs/${encodeURIComponent(orgId)}/cards/${encodeURIComponent(card.id)}`)
    } catch (err) {
      setAddError(err instanceof Error ? err.message : String(err))
    } finally {
      setAdding(false)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p>Loading…</p>

  const complete = data.cards.filter((c) => c.captureComplete).length
  return (
    <>
      <p>
        <Link to={`/orgs/${orgId}`}>← Back</Link>
      </p>
      <h1>{data.name}</h1>
      <p className="muted">
        {complete} of {data.cards.length} card{data.cards.length === 1 ? '' : 's'} fully photographed
      </p>

      <form onSubmit={addCard} className="stack">
        <input
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          placeholder="Card, e.g. Charizard 4/102"
          aria-label="Card name and number"
          required
          maxLength={300}
        />
        <div className="row">
          <label>
            <input type="checkbox" checked={lot} onChange={(e) => setLot(e.target.checked)} /> Lot of cheaper cards
          </label>
          {lot && (
            <input
              type="number"
              min={1}
              value={quantity}
              onChange={(e) => setQuantity(Number(e.target.value))}
              className="narrow"
              aria-label="Number of cards in the lot"
            />
          )}
        </div>
        {lot && <p className="muted small">Lots are kept with their photos, but don't get offers: add cards worth $25 or more one by one.</p>}
        <button type="submit" disabled={adding}>
          Add card
        </button>
      </form>
      {addError && <p className="error" role="alert">{addError}</p>}

      <ul className="list">
        {data.cards.map((card) => {
          const status = statuses?.find((s) => s.cardId === card.id)
          return (
            <li key={card.id}>
              <Link to={`/orgs/${orgId}/cards/${card.id}`}>{card.label}</Link>{' '}
              {card.lot && <span className="muted">lot of {card.quantity}</span>}{' '}
              {!card.captureComplete ? (
                <span className="warn">Needs {card.missingSides.map((s) => s.toLowerCase()).join(' and ')}</span>
              ) : status ? (
                <span className={status.stage === 'NO_OFFER' ? 'warn' : status.stage === 'SOLD' || status.stage === 'READY' || status.stage === 'OFFERED' ? 'ok' : 'muted'}>
                  {STAGE_LABEL[status.stage]}
                </span>
              ) : (
                <span className="ok">Photos OK</span>
              )}
              {status?.identity && <div className="muted small">{status.identity}</div>}
            </li>
          )
        })}
      </ul>

      {COLLATERAL && kind === 'collections' && <CollectionReports orgId={orgId} collectionId={containerId} />}
    </>
  )
}

/** Reports issued for the collection, and for the admin the way to value it. */
export function CollectionReports({ orgId, collectionId }: { orgId: string; collectionId: string }) {
  const api = useApi()
  const { data: me } = useLoad(() => api<Me>('GET', '/api/me'), [api])
  const { data: reports, error } = useLoad(
    () => api<ReportView[]>('GET', apiPath`/api/orgs/${orgId}/collections/${collectionId}/reports`),
    [api, orgId, collectionId],
  )
  return (
    <section>
      <h2>Reports</h2>
      {me?.roles.includes('ADMIN') && (
        <p>
          <Link to={`/admin/valuation/${orgId}/${collectionId}`}>Admin: valuation runs and report issuing</Link>
        </p>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      {reports && <ReportsList reports={reports} downloadPath={(id) => apiPath`/api/orgs/${orgId}/reports/${id}/download`} />}
    </section>
  )
}
