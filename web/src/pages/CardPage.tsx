import { useState, type ChangeEvent, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { uploadToStorage, useApi, type CardView, type Grader, type OutcomeView, type PhotoView, type Side, type UploadSlot } from '../api'
import { useLoad } from '../useLoad'
import { useOrg } from '../useOrg'

const SIDE_LABEL: Record<Side, string> = { FRONT: 'Front', BACK: 'Back', DETAIL: 'Close-up' }

/** Capture screen for one card: take each required side, see pass or retake right away. */
export function CardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: card, error, reload } = useLoad(() => api<CardView>('GET', `/api/orgs/${orgId}/cards/${cardId}`), [api, orgId, cardId])
  const [busy, setBusy] = useState<Side | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const { data: org } = useOrg(orgId)

  async function capture(side: Side, e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setBusy(side)
    setUploadError(null)
    try {
      const slot = await api<UploadSlot>('POST', `/api/orgs/${orgId}/cards/${cardId}/photos`, {
        side,
        contentType: file.type,
        sizeBytes: file.size,
      })
      await uploadToStorage(slot, file)
      await api<PhotoView>('POST', `/api/orgs/${orgId}/photos/${slot.photoId}/confirm`)
      reload()
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(null)
    }
  }

  if (error) return <p className="error">{error}</p>
  if (!card) return <p>Loading…</p>

  const sides: Side[] = card.lot ? ['FRONT', 'DETAIL'] : ['FRONT', 'BACK', 'DETAIL']
  return (
    <>
      <p>
        <button className="link" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </p>
      <h1>{card.label}</h1>
      <p className={card.captureComplete ? 'ok' : 'warn'}>
        {card.captureComplete
          ? 'All required photos passed.'
          : `Still needed: ${card.missingSides.map((s) => SIDE_LABEL[s].toLowerCase()).join(' and ')}.`}
      </p>
      {card.lot && <p className="muted">Lot of {card.quantity}: one photo of the whole lot, laid out flat, is enough.</p>}
      {uploadError && <p className="error">{uploadError}</p>}

      {sides.map((side) => {
        const photos = card.photos.filter((p) => p.side === side && p.status !== 'AWAITING_UPLOAD')
        const latest = photos.at(-1)
        const required = side !== 'DETAIL'
        return (
          <section key={side} className="side">
            <h2>
              {SIDE_LABEL[side]} {!required && <span className="muted">(optional)</span>}
            </h2>
            {latest && <PhotoResult photo={latest} />}
            <label className="capture">
              <input
                type="file"
                accept="image/jpeg,image/png"
                capture="environment"
                disabled={busy !== null}
                onChange={(e) => capture(side, e)}
              />
              {busy === side ? 'Uploading and checking…' : latest ? 'Retake' : `Take ${SIDE_LABEL[side].toLowerCase()} photo`}
            </label>
            {photos.length > 1 && (
              <p className="muted">
                {photos.length} attempts, all kept.
              </p>
            )}
          </section>
        )
      })}
      {org?.type === 'CONTRIBUTOR' && !card.lot && <GradedResult orgId={orgId} cardId={cardId} ready={card.captureComplete} />}
    </>
  )
}

const GRADERS: Grader[] = ['PSA', 'BGS', 'CGC', 'SGC', 'TAG']
const STATUS_LABEL: Record<OutcomeView['status'], string> = {
  UNVERIFIED: 'Waiting for us to check the cert',
  VERIFIED: 'Cert checked',
  MISMATCH: "Cert didn't match this card",
}

/** Contributors: the grade this card came back with, after the raw photos above. */
function GradedResult({ orgId, cardId, ready }: { orgId: string; cardId: string; ready: boolean }) {
  const api = useApi()
  const path = `/api/orgs/${orgId}/cards/${cardId}/outcomes`
  const { data: outcomes, reload } = useLoad(() => api<OutcomeView[]>('GET', path), [api, path])
  const [grader, setGrader] = useState<Grader>('PSA')
  const [certNumber, setCertNumber] = useState('')
  const [grade, setGrade] = useState('')
  const [gradeLabel, setGradeLabel] = useState('')
  const [err, setErr] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    try {
      await api('POST', path, { grader, certNumber, grade: grade === '' ? null : Number(grade), gradeLabel })
      setCertNumber('')
      setGrade('')
      setGradeLabel('')
      reload()
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <section className="side">
      <h2>Graded result</h2>
      {outcomes?.map((o) => (
        <p key={o.id} className={o.status === 'MISMATCH' ? 'warn' : o.status === 'VERIFIED' ? 'ok' : ''}>
          {o.grader} {o.grade ?? o.gradeLabel} · cert {o.certNumber} · {STATUS_LABEL[o.status]}
        </p>
      ))}
      {!ready ? (
        <p className="muted">Add the raw front and back photos first (taken before the card was graded).</p>
      ) : (
        <form onSubmit={submit} className="row">
          <select value={grader} onChange={(e) => setGrader(e.target.value as Grader)}>
            {GRADERS.map((g) => (
              <option key={g}>{g}</option>
            ))}
          </select>
          <input value={certNumber} onChange={(e) => setCertNumber(e.target.value)} placeholder="Cert number" required pattern="[A-Za-z0-9-]+" />
          <input value={grade} onChange={(e) => setGrade(e.target.value)} placeholder="Grade" inputMode="decimal" className="narrow" />
          <input value={gradeLabel} onChange={(e) => setGradeLabel(e.target.value)} placeholder="Label, e.g. GEM MT" />
          <button type="submit">Save</button>
        </form>
      )}
      {err && <p className="error">{err}</p>}
    </section>
  )
}

function PhotoResult({ photo }: { photo: PhotoView }) {
  if (photo.status === 'PASSED') {
    return (
      <p className="ok">
        Passed · {photo.width}×{photo.height}
      </p>
    )
  }
  return (
    <div className="warn">
      <p>Retake needed:</p>
      <ul>
        {photo.retakeReasons.map((r) => (
          <li key={r.code}>{r.message}</li>
        ))}
      </ul>
    </div>
  )
}
