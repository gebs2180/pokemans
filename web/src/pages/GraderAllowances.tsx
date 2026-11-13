import type { GraderStandard } from '../api'
import { bestGrade } from '../centering'
import './reviewTools.css'

/**
 * The best grade each grader's centering standard allows for one or more readings. Display only: the
 * valuation and the centering cap use PSA's table alone, and no other grader's centering is ever used.
 *
 * @param readings each column: a heading and the front and back worse-pair shares (null where not measured)
 */
export function GraderAllowances({
  standards,
  readings,
}: {
  standards: GraderStandard[]
  readings: { heading: string; front: number | null; back: number | null }[]
}) {
  return (
    <>
      <table className="table grader-allowances">
        <thead>
          <tr>
            <th>Grader</th>
            {readings.map((r) => (
              <th key={r.heading}>{r.heading}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {standards.map((s) => (
            <tr key={s.grader}>
              <td>
                {s.grader}
                {s.allowances.some((a) => a.unsure) && (
                  <span className="muted small" title={s.allowances.flatMap((a) => (a.unsure ? [`${a.grade}: ${a.unsure}`] : [])).join('\n')}>
                    {' '}
                    (to confirm)
                  </span>
                )}
              </td>
              {readings.map((r) => (
                <td key={r.heading}>{bestGrade(s, r.front, r.back) ?? '–'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">
        The best grade each grader's published centering standard allows, centering alone. For reference only: offers and the centering
        cap use PSA's table and nothing else.
      </p>
    </>
  )
}
