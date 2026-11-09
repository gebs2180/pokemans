import { useState, type FormEvent } from 'react'
import { money, parseMoney, useApi, type BuyerOutcomeRecord, type DealOutcomeKind, type Grader, type RecordedDealOutcome } from '../api'
import { DateInput, MoneyInput } from '../inputs'
import { Field } from '../ui'
import { useFormError } from '../useFormError'
import { message as errorText, today } from '../format'

const GRADERS: Grader[] = ['PSA', 'BGS', 'CGC', 'SGC', 'TAG']

const KIND_LABEL: Record<DealOutcomeKind, string> = {
  GRADED: 'I had it graded',
  RESOLD_RAW: 'I resold it raw',
  KEPT: "I'm keeping it",
}

const CERT_STATUS: Record<NonNullable<RecordedDealOutcome['certStatus']>, string> = {
  UNVERIFIED: 'waiting for Zach to check the cert',
  VERIFIED: 'cert checked',
  MISMATCH: "the cert didn't match this card",
}

/** The buyer org's record: how often its graded cards came back within a grade of what we predicted. */
export function OutcomeRecord({ record }: { record: BuyerOutcomeRecord }) {
  if (record.graded === 0 && record.resold === 0 && record.kept === 0) return null
  return (
    <p className="small">
      {record.graded > 0 ? (
        <>
          Of the {record.graded} card{record.graded === 1 ? '' : 's'} you had graded, <strong>{record.withinOne}</strong> came back within
          one grade of our prediction
          {record.verified < record.graded && ` (${record.verified} with the cert checked)`}.
        </>
      ) : (
        'None of your cards have come back graded yet.'
      )}
      {record.resold > 0 && ` ${record.resold} resold raw.`}
      {record.kept > 0 && ` ${record.kept} kept.`}
    </p>
  )
}

/** What the buyer last said happened to the card. */
function Said({ outcome }: { outcome: RecordedDealOutcome }) {
  const date = new Date(outcome.recordedAt).toLocaleDateString()
  return (
    <div className="alert alert-info small">
      {outcome.kind === 'GRADED' && (
        <p>
          You said it came back {outcome.grader} {outcome.grade ?? outcome.gradeLabel}
          {outcome.grade != null && outcome.gradeLabel && ` (${outcome.gradeLabel})`}, cert {outcome.certNumber}:{' '}
          {outcome.certStatus && CERT_STATUS[outcome.certStatus]}.
        </p>
      )}
      {outcome.kind === 'RESOLD_RAW' && <p>You said you resold it raw for {money(outcome.resalePrice)}.</p>}
      {outcome.kind === 'KEPT' && <p>You said you're keeping it.</p>}
      {outcome.note && <p>Your note: "{outcome.note}"</p>}
      <p className="muted">
        Recorded {date}
        {outcome.earlier > 0 && `, correcting ${outcome.earlier} earlier ${outcome.earlier === 1 ? 'answer' : 'answers'}`}.
      </p>
    </div>
  )
}

/**
 * Once the card has arrived and stayed: was it graded (and what did it get), resold raw, or kept? Each
 * answer is a new row, so a correction keeps the one before. The grade is how we check our predictions.
 *
 * @param path the deal's API path
 * @param latest what the buyer last said, if anything
 */
export function DealOutcomeForm({ path, latest, onSaved }: { path: string; latest: RecordedDealOutcome | null; onSaved: () => void }) {
  const api = useApi()
  const [open, setOpen] = useState(latest == null)
  const [kind, setKind] = useState<DealOutcomeKind | null>(null)
  const [grader, setGrader] = useState<Grader>('PSA')
  const [certNumber, setCertNumber] = useState('')
  const [grade, setGrade] = useState('')
  const [gradeLabel, setGradeLabel] = useState('')
  const [gradedOn, setGradedOn] = useState('')
  const [resalePrice, setResalePrice] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

  function edit<T>(set: (v: T) => void) {
    return (v: T) => {
      setConfirming(false)
      set(v)
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!kind) return
    if (kind === 'GRADED' && grade === '' && gradeLabel.trim() === '') {
      setError('Give the grade, or the label if it has no number (e.g. Authentic).')
      return
    }
    if (!confirming) {
      setConfirming(true)
      return
    }
    setBusy(true)
    try {
      const body =
        kind === 'GRADED'
          ? {
              kind,
              grader,
              certNumber: certNumber.trim(),
              grade: grade === '' ? null : Number(grade),
              gradeLabel: gradeLabel.trim() || null,
              gradedOn: gradedOn || null,
              note: note || null,
            }
          : kind === 'RESOLD_RAW'
            ? { kind, resalePrice: parseMoney(resalePrice), note: note || null }
            : { kind, note: note || null }
      await api('POST', `${path}/outcomes`, body)
      setConfirming(false)
      setOpen(false)
      setKind(null)
      onSaved()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const summary =
    kind === 'GRADED'
      ? `${grader} ${grade || gradeLabel}, cert ${certNumber}`
      : kind === 'RESOLD_RAW'
        ? `resold raw for ${money(parseMoney(resalePrice))}`
        : 'kept'

  return (
    <>
      {latest && <Said outcome={latest} />}
      {!open ? (
        <div className="actions">
          <button type="button" className="secondary" onClick={() => setOpen(true)}>
            Correct it
          </button>
        </div>
      ) : (
        <form onSubmit={save} className="form" aria-describedby={formError.describedBy}>
          <h3>What happened to this card?</h3>
          <p className="muted small">We compare the grades cards come back with against the condition we predicted. Only your org sees your answer.</p>
          <div className="choices" role="radiogroup" aria-label="What happened">
            {(Object.keys(KIND_LABEL) as DealOutcomeKind[]).map((k) => (
              <label key={k} className="choice">
                <input type="radio" name="outcome-kind" value={k} checked={kind === k} onChange={() => edit(setKind)(k)} /> {KIND_LABEL[k]}
              </label>
            ))}
          </div>
          {kind === 'GRADED' && (
            <>
              <Field label="Grader">
                {(control) => (
                  <select {...control} value={grader} onChange={(e) => edit(setGrader)(e.target.value as Grader)} aria-label="Grader">
                    {GRADERS.map((g) => (
                      <option key={g}>{g}</option>
                    ))}
                  </select>
                )}
              </Field>
              <Field label="Grade" hint="As on the label: 8.5 stays 8.5. Leave it empty for a result without a number.">
                {(control) => (
                  <input
                    {...control}
                    type="number"
                    min={1}
                    max={10}
                    step={0.5}
                    value={grade}
                    onChange={(e) => edit(setGrade)(e.target.value)}
                    aria-label="Grade"
                    className="narrow"
                  />
                )}
              </Field>
              <Field label="Cert number">
                {(control) => (
                  <input
                    {...control}
                    value={certNumber}
                    onChange={(e) => edit(setCertNumber)(e.target.value)}
                    aria-label="Cert number"
                    required
                    pattern="[A-Za-z0-9\-]{1,30}"
                    maxLength={30}
                  />
                )}
              </Field>
              <Field label="Label (optional)">
                {(control) => (
                  <input
                    {...control}
                    value={gradeLabel}
                    onChange={(e) => edit(setGradeLabel)(e.target.value)}
                    placeholder="e.g. GEM MT, Authentic"
                    aria-label="Grade label"
                    maxLength={50}
                  />
                )}
              </Field>
              <div className="field">
                <span className="field-label">Graded on (optional)</span>
                <DateInput value={gradedOn} max={today()} onChange={edit(setGradedOn)} aria-label="Graded on" />
              </div>
            </>
          )}
          {kind === 'RESOLD_RAW' && (
            <Field label="Sold for ($)">
              {(control) => (
                <MoneyInput {...control} value={resalePrice} onChange={edit(setResalePrice)} min={0} className="narrow" aria-label="Sold for" required />
              )}
            </Field>
          )}
          {kind && (
            <Field label="Note (optional)">
              {(control) => (
                <input {...control} value={note} onChange={(e) => edit(setNote)(e.target.value)} aria-label="Outcome note" maxLength={2000} />
              )}
            </Field>
          )}
          {kind &&
            (confirming ? (
              <div className="confirm">
                <p>Record it as {summary}? It can't be edited afterwards, only corrected with a new answer.</p>
                <div className="actions">
                  <button type="submit" disabled={busy} aria-busy={busy}>
                    Confirm: record what happened
                  </button>
                  <button type="button" className="secondary" onClick={() => setConfirming(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            ) : (
              <div className="actions">
                <button type="submit">Record what happened</button>
                {latest && (
                  <button type="button" className="secondary" onClick={() => setOpen(false)}>
                    Never mind
                  </button>
                )}
              </div>
            ))}
          {formError.message}
        </form>
      )}
    </>
  )
}
