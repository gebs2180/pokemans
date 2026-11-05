import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiPath, deadlineText, useApi, type CardStatus, type CardView, type ContainerDetail, type Me, type ReportView } from '../api'
import { Chip, EmptyState, Field, Loading, NotFound, PageError, StageChip } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { ReportsList } from './ReportsPanel'
import { COLLATERAL } from '../flags'
import { message } from '../format'
import { dollars, usePolicy } from '../policy'

/** Lender and insurer reports are paused unless the build turns them on. */

/** A batch or binder on its own page. */
export function ContainerPage() {
  const { orgId = '', kind = '', containerId = '' } = useParams()
  if (kind !== 'batches' && kind !== 'collections') return <NotFound />
  return <ContainerView orgId={orgId} kind={kind} containerId={containerId} />
}

/**
 * A batch or binder: its cards and their capture status.
 * @param title    for a binder shown inside a page of its own: the page's heading, in place of the binder's name and back link
 * @param lead     shown under the heading
 * @param children shown under the cards
 */
export function ContainerView({
  orgId,
  kind,
  containerId,
  title,
  lead,
  children,
}: {
  orgId: string
  kind: string
  containerId: string
  title?: string
  lead?: ReactNode
  children?: ReactNode
}) {
  const policy = usePolicy()
  const api = useApi()
  const navigate = useNavigate()
  const { data, error } = useLoad(
    () => api<ContainerDetail>('GET', apiPath`/api/orgs/${orgId}/${kind}/${containerId}`),
    [api, orgId, kind, containerId],
  )
  // Where each card is on the way to a sale; collections only (batches are for partners, who don't sell).
  const { data: statuses, error: statusError } = useLoad(
    () =>
      kind === 'collections'
        ? api<CardStatus[]>('GET', apiPath`/api/orgs/${orgId}/collections/${containerId}/status`)
        : Promise.resolve([] as CardStatus[]),
    [api, orgId, kind, containerId],
  )
  const [label, setLabel] = useState('')
  const [lot, setLot] = useState(false)
  const [quantity, setQuantity] = useState(1)
  const [addError, setAddError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const formError = useFormError(addError)
  // Lots never get offers, so sellers aren't offered them; partners' batches still take them.
  const lots = kind === 'batches'

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
      setAddError(message(err))
    } finally {
      setAdding(false)
    }
  }

  if (error) return <PageError message={error} />
  if (!data) return <Loading />

  const complete = data.cards.filter((c) => c.captureComplete).length
  return (
    <>
      {title == null && (
        <p className="back">
          <Link to={`/orgs/${orgId}`}>← Back</Link>
        </p>
      )}
      <h1>{title ?? data.name}</h1>
      {lead}
      {data.cards.length > 0 && (
        <p className="muted">
          {complete} of {data.cards.length} card{data.cards.length === 1 ? '' : 's'} fully photographed
        </p>
      )}

      <form onSubmit={addCard} className="panel form" aria-describedby={formError.describedBy}>
        <Field label="Card name and number" hint={lots ? 'Its name and number, as printed.' : `Its name and number, as printed. One card at a time: we'll tell you if it's under our ${dollars(policy.minFmv)} minimum.`}>
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
        {lots && (
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
        )}
        {lot && <p className="muted small">Lots are kept with their photos, but don't get offers: add cards worth {dollars(policy.minFmv)} or more one by one.</p>}
        <div className="actions">
          <button type="submit" disabled={adding} aria-busy={adding}>
            Add card
          </button>
        </div>
        {formError.message}
      </form>

      {statusError && (
        <p className="alert alert-warning" role="status">
          Couldn't load where each card is: {statusError}
        </p>
      )}
      {data.cards.length === 0 && <EmptyState title="No cards yet.">Add the first one above, then photograph it.</EmptyState>}
      <ul className="tile-list">
        {data.cards.map((card) => {
          const status = statuses?.find((s) => s.cardId === card.id)
          const deadline = status && deadlineText(status)
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
              {deadline && <div className="small">{deadline}</div>}
            </li>
          )
        })}
      </ul>

      {children}
      {COLLATERAL && kind === 'collections' && <CollectionReports orgId={orgId} collectionId={containerId} />}
    </>
  )
}

/** Reports issued for the collection, and for the admin the way to value it. */
export function CollectionReports({ orgId, collectionId }: { orgId: string; collectionId: string }) {
  const api = useApi()
  const { data: me, error: meError } = useLoad(() => api<Me>('GET', '/api/me'), [api])
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
      {meError && <p className="error" role="alert">Couldn't check your access: {meError}</p>}
      {reports && <ReportsList reports={reports} downloadPath={(id) => apiPath`/api/orgs/${orgId}/reports/${id}/download`} />}
    </section>
  )
}
