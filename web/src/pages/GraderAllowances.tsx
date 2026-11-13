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
  const unsure = standards.filter((s) => s.allowances.some((a) => a.unsure))
  return (
    <>
      <h3 className="grader-allowances-heading">What each grader allows</h3>
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
                {s.allowances.some((a) => a.unsure) && <span className="muted small"> (to confirm)</span>}
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
      {unsure.length > 0 && (
        <div className="muted small grader-notes">
          <p>
            To confirm: {listOf(unsure.map((s) => s.grader))} {unsure.length === 1 ? 'is' : 'are'} read from{' '}
            {unsure.length === 1 ? 'its' : 'their'} published standards, with some limits not stated or not yet checked.
          </p>
          <details>
            <summary>What isn't confirmed</summary>
            <ul>
              {unsure.flatMap((s) =>
                s.allowances.flatMap((a) =>
                  a.unsure ? [<li key={`${s.grader} ${a.grade}`}>{`${s.grader} ${a.grade}: ${a.unsure}`}</li>] : [],
                ),
              )}
            </ul>
          </details>
        </div>
      )}
    </>
  )
}

/** "A", "A and B", "A, B and C". */
function listOf(names: string[]) {
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}
