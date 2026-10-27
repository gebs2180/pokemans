import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type CardStatus, type CardView, type ContainerDetail, type Me, type ReportView } from '../api'
import { Chip, EmptyState, Field, Loading, StageChip } from '../ui'
import { useFormError } from '../useFormError'
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
  const formError = useFormError(addError)

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
  if (!data) return <Loading />

  const complete = data.cards.filter((c) => c.captureComplete).length
  return (
    <>
      <p className="back">
        <Link to={`/orgs/${orgId}`}>← Back</Link>
      </p>
      <h1>{data.name}</h1>
      <p className="muted">
        {complete} of {data.cards.length} card{data.cards.length === 1 ? '' : 's'} fully photographed
      </p>

      <form onSubmit={addCard} className="panel form" aria-describedby={formError.describedBy}>
        <Field label="Card name and number" hint="Its name and number, as printed. One card worth $25 or more at a time.">
          {(control) => (
            <input
              {...control}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Card, e.g. Charizard 4/102"
              required
              maxLength={300}
            />
          )}
        </Field>
        <div className="row">
          <label className="check">
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
        <div className="actions">
          <button type="submit" disabled={adding} aria-busy={adding}>
            Add card
          </button>
        </div>
        {formError.message}
      </form>

      {data.cards.length === 0 && <EmptyState title="No cards yet.">Add the first one above, then photograph it.</EmptyState>}
      <ul className="tile-list">
        {data.cards.map((card) => {
          const status = statuses?.find((s) => s.cardId === card.id)
          return (
            <li key={card.id} className="tile">
              <div className="tile-head">
                <Link to={`/orgs/${orgId}/cards/${card.id}`}>{card.label}</Link>
                {!card.captureComplete ? (
                  <Chip tone="warning">Needs {card.missingSides.map((s) => s.toLowerCase()).join(' and ')}</Chip>
                ) : status ? (
                  <StageChip stage={status.stage} />
                ) : (
                  <Chip tone="success">Photos OK</Chip>
                )}
              </div>
              {card.lot && <span className="muted">lot of {card.quantity}</span>}
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
