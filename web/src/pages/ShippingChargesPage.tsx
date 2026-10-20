import { useState } from 'react'
import { Link } from 'react-router'
import { money, useApi, type BuyerCharges } from '../api'
import { useLoad } from '../useLoad'

function thisMonth() {
  const now = new Date()
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`
}

/** Admin: labels bought on our account in a month, per buyer: what to invoice each one. */
export function ShippingChargesPage() {
  const api = useApi()
  const [month, setMonth] = useState(thisMonth())
  const { data, error } = useLoad(
    () => api<BuyerCharges[]>('GET', `/api/admin/shipping/charges?month=${encodeURIComponent(month)}`),
    [api, month],
  )

  return (
    <>
      <p>
        <Link to="/admin/offers">← Offer track record</Link>
      </p>
      <h1>Postage to bill</h1>
      <p className="muted">Labels for bought cards are paid on our shipping account and invoiced to each buyer monthly (UTC months).</p>
      <label>
        Month <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      {data?.length === 0 && <p className="muted">No labels that month.</p>}
      {data?.map((b) => (
        <section key={b.buyerOrgId} className="panel">
          <h2>
            {b.buyerName ?? b.buyerOrgId}: {money(b.total)}
          </h2>
          <p className="muted small">
            {b.labels} label{b.labels === 1 ? '' : 's'}: {money(b.postage)} postage, {money(b.insurance)} insurance
          </p>
          <div className="scroll">
            <table className="table">
              <tbody>
                {b.lines.map((l) => (
                  <tr key={l.listingId}>
                    <td>{new Date(l.boughtAt).toLocaleDateString()}</td>
                    <td>
                      {l.carrier} {l.service}
                    </td>
                    <td className="muted">{l.trackingCode}</td>
                    <td>{money(l.postage)}</td>
                    <td>{money(l.insurance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </>
  )
}
