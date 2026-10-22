import { Link } from 'react-router'
import { money, percent, RETURN_REASON, useApi, type Acceptance, type DeclinedReason, type TrackRecord } from '../api'
import { useLoad } from '../useLoad'

/** Below this many, a rate is shown but called out as too few to read anything into. */
const FEW = 30

/** A rate with the counts behind it: "50% (2 of 4)", or "–" with nothing to count. */
function Rate({ share, count, of }: { share: number | null; count: number; of: number }) {
  if (share == null) return <span className="muted">– (none yet)</span>
  return (
    <>
      {percent(share)}{' '}
      <span className="muted">
        ({count} of {of}
        {of < FEW ? ', too few to read' : ''})
      </span>
    </>
  )
}

function AcceptanceRow({ a }: { a: Acceptance }) {
  return (
    <tr>
      <td>{a.band}</td>
      <td>{a.listings}</td>
      <td>
        <Rate share={a.acceptanceRate} count={a.accepted} of={a.closed} />
      </td>
      <td>{a.medianHoursToAccept == null ? '–' : `${a.medianHoursToAccept} h`}</td>
      <td className="muted">
        {a.withdrawn} withdrawn · {a.expired} expired · {a.open} open
      </td>
    </tr>
  )
}

/** Admin: what happened to the firm offers we posted, with the counts and caveats behind every rate. */
export function OfferTrackRecordPage() {
  const api = useApi()
  const { data, error } = useLoad(() => api<TrackRecord>('GET', '/api/admin/offers/track-record'), [api])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p>Loading…</p>
  const { overall, deliveries, prices, shipping } = data

  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link> · <Link to="/admin/shipping">Postage to bill →</Link>
      </p>
      <h1>Offer track record</h1>
      <p className="muted">
        What happened to the instant offers we posted, as of {new Date(data.asOf).toLocaleString()}. The same numbers come from the
        analysis view <code>v_offer_outcomes</code>.
      </p>

      <section aria-label="Read these with care">
        <h2>Read these with care</h2>
        <ul>
          {data.caveats.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
      </section>

      <h2>Acceptance</h2>
      <p className="muted">Bought out of listings that closed (bought, withdrawn or expired), by offer price.</p>
      <div className="scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Offer</th>
              <th>Listings</th>
              <th>Bought</th>
              <th>Median time to buy</th>
              <th>Not bought</th>
            </tr>
          </thead>
          <tbody>
            <AcceptanceRow a={overall} />
            {data.byPriceBand.map((b) => (
              <AcceptanceRow key={b.band} a={b} />
            ))}
          </tbody>
        </table>
      </div>

      <h2>Deliveries</h2>
      <table className="table">
        <tbody>
          <tr>
            <th>Bought</th>
            <td>
              {deliveries.taken} · {deliveries.labels} labelled · {deliveries.inTransit} on the way · {deliveries.awaitingLabel} waiting
              for a label
            </td>
          </tr>
          <tr>
            <th>Returned</th>
            <td>
              <Rate share={deliveries.returnRate} count={deliveries.returned} of={deliveries.received + deliveries.returned} />
              {Object.entries(deliveries.returnedBy).map(([reason, n]) => (
                <span key={reason} className="muted">
                  {' '}
                  · {RETURN_REASON[reason as DeclinedReason] ?? reason}: {n}
                </span>
              ))}
            </td>
          </tr>
          <tr>
            <th>Median label to arrival</th>
            <td>{deliveries.medianHoursToArrival == null ? '–' : `${Math.round(deliveries.medianHoursToArrival / 24)} days`}</td>
          </tr>
          {deliveries.earlierModel > 0 && (
            <tr>
              <th>Under the earlier per-grade offers</th>
              <td className="muted">{deliveries.earlierModel}, left out of the numbers above</td>
            </tr>
          )}
        </tbody>
      </table>

      <h2>Price paid against the offer</h2>
      <table className="table">
        <tbody>
          <tr>
            <th>Below / at / above the offer</th>
            <td>
              {prices.belowOffer} / {prices.atOffer} / {prices.aboveOffer} <span className="muted">of {prices.compared} that arrived as shown</span>
            </td>
          </tr>
          <tr>
            <th>Median paid ÷ offer</th>
            <td>
              {prices.medianRatio == null ? '–' : percent(prices.medianRatio)}
              {prices.compared > 0 && prices.compared < FEW && <span className="muted"> (too few to read)</span>}
            </td>
          </tr>
          <tr>
            <th>Total paid against total offered</th>
            <td>
              {money(prices.totalPaid)} against {money(prices.totalOffered)}
            </td>
          </tr>
        </tbody>
      </table>

      <h2>Shipping</h2>
      <p>
        {shipping.labels} labels: {money(shipping.postage)} postage and {money(shipping.insurance)} insurance
        {shipping.meanPerLabel != null && `, ${money(shipping.meanPerLabel)} a card on average`}. Billed to buyers.
      </p>
    </>
  )
}
