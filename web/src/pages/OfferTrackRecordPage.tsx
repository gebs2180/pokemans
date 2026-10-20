import { Link } from 'react-router'
import { money, percent, useApi, type Acceptance, type TrackRecord } from '../api'
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

export function OfferTrackRecordPage() {
  const api = useApi()
  const { data, error } = useLoad(() => api<TrackRecord>('GET', '/api/admin/offers/track-record'), [api])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p>Loading…</p>
  const { overall, grades, prices } = data

  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Offer track record</h1>
      <p className="muted">
        What happened to the instant offers we posted, as of {new Date(data.asOf).toLocaleString()}. The same numbers come from the
        analysis views <code>v_offer_outcomes</code> and <code>v_offer_grade_calibration</code>.
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
      <p className="muted">Accepted out of listings that closed (accepted, withdrawn or expired), by expected offer.</p>
      <div className="scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Expected offer</th>
              <th>Listings</th>
              <th>Accepted</th>
              <th>Median time to accept</th>
              <th>Not accepted</th>
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

      <h2>Inspected grade against our prediction</h2>
      <p className="muted">
        Source: {grades.source} (the buyer's own grading). {grades.inspected} inspections of raw cards compared
        {grades.excluded > 0 ? `, ${grades.excluded} left out (graded cards, or not genuine or not the listed card)` : ''}.
      </p>
      <table className="table">
        <tbody>
          <tr>
            <th>Within ±1 of the predicted median</th>
            <td>
              <Rate share={grades.withinOneShare} count={grades.withinOne} of={grades.inspected} />
            </td>
            <td className="muted">target ≥ {percent(grades.withinOneTarget)}</td>
          </tr>
          <tr>
            <th>Below the stressed median</th>
            <td>
              <Rate share={grades.belowStressedShare} count={grades.belowStressed} of={grades.inspected} />
            </td>
            <td className="muted">target &lt; {percent(grades.belowStressedTarget)}</td>
          </tr>
          <tr>
            <th>Average difference (inspected − predicted)</th>
            <td>{grades.meanDifference == null ? '–' : `${grades.meanDifference > 0 ? '+' : ''}${grades.meanDifference} grades`}</td>
            <td className="muted">negative: the buyer found it worse than we predicted</td>
          </tr>
        </tbody>
      </table>

      <h2>Price paid against our ladder</h2>
      <p className="muted">
        Source: {prices.source}. The ladder price is what the offer said for the grade the buyer found (half grades fold down).
      </p>
      <table className="table">
        <tbody>
          <tr>
            <th>Compared</th>
            <td>
              {prices.compared} paid · {prices.declined} declined, nothing paid
            </td>
          </tr>
          <tr>
            <th>Median paid ÷ ladder</th>
            <td>
              {prices.medianRatio == null ? '–' : percent(prices.medianRatio)}
              {prices.compared > 0 && prices.compared < FEW && <span className="muted"> (too few to read)</span>}
            </td>
          </tr>
          <tr>
            <th>Below / at / above the ladder</th>
            <td>
              {prices.belowLadder} / {prices.atLadder} / {prices.aboveLadder}
            </td>
          </tr>
          <tr>
            <th>Total paid against total ladder</th>
            <td>
              {money(prices.totalPaid)} against {money(prices.totalLadder)}
            </td>
          </tr>
        </tbody>
      </table>
    </>
  )
}
