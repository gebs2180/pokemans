import { useState, type FormEvent } from 'react'
import { apiPath, SIDE_LABEL, useApi, type DefectMarkView, type DefectSeverity, type DefectType, type Side } from '../api'
import { message } from '../format'
import { useLoad } from '../useLoad'
import { DEFECT_LABEL, markWords, SEVERITY_LABEL } from './defectWords'
import { SurfacePanel, type Placement, type SurfaceAudience, type SurfacePhoto } from './SurfaceOverlay'
import './reviewTools.css'

const TYPES = Object.keys(DEFECT_LABEL) as DefectType[]
const SEVERITIES = Object.keys(SEVERITY_LABEL) as DefectSeverity[]

/**
 * The surface view with the defects a reviewer marked on it. The admin can add and remove marks (tap "Mark a defect",
 * then a spot); the collector sees them read-only, in plain words. Marks are shown only: they change neither the
 * condition nor any price.
 */
export function MarkedSurfacePanel({
  orgId,
  cardId,
  photos,
  audience = 'reviewer',
}: {
  orgId: string
  cardId: string
  photos: SurfacePhoto[]
  audience?: SurfaceAudience
}) {
  const api = useApi()
  const admin = audience === 'reviewer'
  const path = admin ? apiPath`/api/admin/orgs/${orgId}/cards/${cardId}/defect-marks` : apiPath`/api/orgs/${orgId}/cards/${cardId}/defect-marks`
  // A failed load just shows no marks: the surface view works without them.
  const { data, reload } = useLoad(() => api<DefectMarkView[]>('GET', path), [api, path])
  const [marking, setMarking] = useState(false)
  const [pending, setPending] = useState<Placement | null>(null)
  const [type, setType] = useState<DefectType>('SCRATCH')
  const [severity, setSeverity] = useState<DefectSeverity>('MINOR')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Only marks on a photo shown here, in the order they are numbered.
  const shownIds = new Set(photos.map((p) => p.id))
  const marks = (Array.isArray(data) ? data : []).filter((m) => shownIds.has(m.photoId))

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!pending) return
    setBusy(true)
    setError(null)
    try {
      await api<DefectMarkView>('POST', path, { ...pending, type, severity, note: note.trim() || null })
      setPending(null)
      setNote('')
      reload()
    } catch (err) {
      setError(message(err))
    } finally {
      setBusy(false)
    }
  }

  async function remove(m: DefectMarkView) {
    setError(null)
    try {
      await api('DELETE', `${path}/${encodeURIComponent(m.id)}`)
      reload()
    } catch (err) {
      setError(message(err))
    }
  }

  function toggle() {
    setMarking((on) => !on)
    setPending(null)
  }

  return (
    <SurfacePanel photos={photos} audience={audience} marks={marks} pending={pending} onPlace={admin && marking ? setPending : undefined}>
      {admin && (
        <div className="defect-tools">
          <button type="button" aria-pressed={marking} onClick={toggle}>
            {marking ? 'Done marking' : 'Mark a defect'}
          </button>
          {marking && !pending && <span className="muted small"> Tap the spot on a crop.</span>}
          {pending && (
            <form className="defect-form" onSubmit={save}>
              <p className="small">
                <strong>New mark on the {SIDE_LABEL[sideOf(photos, pending.photoId)].toLowerCase()}</strong>
              </p>
              <label>
                Kind
                <select value={type} onChange={(e) => setType(e.target.value as DefectType)}>
                  {TYPES.map((t) => (
                    <option key={t} value={t}>
                      {DEFECT_LABEL[t]}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                How bad
                <select value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)}>
                  {SEVERITIES.map((s) => (
                    <option key={s} value={s}>
                      {SEVERITY_LABEL[s]}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Note (optional)
                <input type="text" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
              </label>
              <button type="submit" disabled={busy}>
                {busy ? 'Saving…' : 'Add mark'}
              </button>{' '}
              <button type="button" onClick={() => setPending(null)}>
                Cancel
              </button>
            </form>
          )}
        </div>
      )}
      {error && (
        <p role="alert" className="error small">
          {error}
        </p>
      )}
      {marks.length > 0 && (
        <div className="defect-list">
          <p className="small">
            <strong>{admin ? 'Marked defects' : 'What our reviewer marked'}</strong>
          </p>
          <ol className="small">
            {marks.map((m) => (
              <li key={m.id}>
                {SIDE_LABEL[m.side as Side] ?? m.side}: {markWords(m)}
                {admin && (
                  <>
                    {' '}
                    <button type="button" className="link" aria-label={`Remove mark ${marks.indexOf(m) + 1}`} onClick={() => remove(m)}>
                      Remove
                    </button>
                  </>
                )}
              </li>
            ))}
          </ol>
          <p className="muted small">
            {admin
              ? 'Shown to the collector. Marks change no grade or price.'
              : "These are shown for your information: they don't change your offer."}
          </p>
        </div>
      )}
    </SurfacePanel>
  )
}

function sideOf(photos: SurfacePhoto[], photoId: string): Side {
  return photos.find((p) => p.id === photoId)?.side ?? 'FRONT'
}
