import { useState, type ChangeEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { uploadToStorage, useApi, type CardView, type PhotoView, type Side, type UploadSlot } from '../api'
import { useLoad } from '../useLoad'

const SIDE_LABEL: Record<Side, string> = { FRONT: 'Front', BACK: 'Back', DETAIL: 'Close-up' }

/** Capture screen for one card: take each required side, see pass or retake right away. */
export function CardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: card, error, reload } = useLoad(() => api<CardView>('GET', `/api/orgs/${orgId}/cards/${cardId}`), [api, orgId, cardId])
  const [busy, setBusy] = useState<Side | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)

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
    </>
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
