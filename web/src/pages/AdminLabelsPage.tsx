import { useState } from 'react'
import { Link } from 'react-router'
import { apiPath, money, useApi, type AwaitingLabel } from '../api'
import { useLoad } from '../useLoad'
import { ArrivalForm, CardTitle, Photos } from './MarketPage'

/**
 * Admin: deals a buyer took whose prepaid label didn't go through. Buy the label again (once shipping
 * works), or, if the card shipped another way, record its arrival for the buyer, saying how.
 */
export function AdminLabelsPage() {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<AwaitingLabel[]>('GET', '/api/admin/deals/awaiting-label'), [api])
  const [busy, setBusy] = useState<string | null>(null)
  const [failed, setFailed] = useState<{ listingId: string; message: string } | null>(null)
  const [byHand, setByHand] = useState<string | null>(null)

  async function buyLabel(deal: AwaitingLabel) {
    setFailed(null)
    setBusy(deal.listingId)
    try {
      await api('POST', apiPath`/api/admin/deals/${deal.listingId}/label?buyerOrgId=${deal.buyerOrgId}`)
      reload()
    } catch (e) {
      setFailed({ listingId: deal.listingId, message: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(null)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Labels to buy</h1>
      <p className="muted">
        Cards a buyer took whose prepaid label didn't go through. The seller is told not to ship until the label shows on their card's
        page.
      </p>
      {!data && <p>Loading…</p>}
      {data?.length === 0 && <p className="muted">No deals are waiting for a label.</p>}
      {data?.map((deal) => (
        <section key={deal.listingId} className="panel">
          <CardTitle card={deal.card} />
          <Photos photos={deal.photos} />
          <p>
            {money(deal.offer.price)} · bought {new Date(deal.acceptedAt).toLocaleDateString()} by {deal.buyer.orgName ?? 'a buyer'}
            {deal.buyer.email && ` (${deal.buyer.email})`} from {deal.seller.orgName ?? 'a seller'}
            {deal.seller.email && ` (${deal.seller.email})`}.
          </p>
          <p className="row">
            <button onClick={() => buyLabel(deal)} disabled={busy !== null}>
              {busy === deal.listingId ? 'Buying…' : 'Buy the label again'}
            </button>
            <button className="link" onClick={() => setByHand(byHand === deal.listingId ? null : deal.listingId)}>
              It shipped another way: record the arrival
            </button>
          </p>
          {failed?.listingId === deal.listingId && (
            <p className="error" role="alert">
              {failed.message}
            </p>
          )}
          {byHand === deal.listingId && (
            <ArrivalForm
              path={apiPath`/api/admin/deals/${deal.listingId}?buyerOrgId=${deal.buyerOrgId}`}
              price={deal.offer.price}
              noteRequired
              onSaved={() => {
                setByHand(null)
                reload()
              }}
            />
          )}
        </section>
      ))}
    </>
  )
}
