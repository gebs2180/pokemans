import { useState, type ChangeEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type CardView } from '../api'
import { message } from '../format'
import { Loading, NotFound } from '../ui'
import { PhotoPair } from './PhotoPair'
import { pairOf, STEP_WORDS, uploadPhoto, type UploadStep } from './photoUpload'

/**
 * A card that doesn't exist yet: it's made with its first photo, so a mis-tap on "Add card" leaves nothing behind
 * (review finding 10). The front comes first; choosing two photos at once takes them as front and back.
 */
export function NewCardPage() {
  const { orgId = '', kind = '', containerId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const [step, setStep] = useState<UploadStep | null>(null)
  const [pair, setPair] = useState<[File, File] | null>(null)
  const [error, setError] = useState<string | null>(null)
  if (kind !== 'batches' && kind !== 'collections') return <NotFound />

  async function start(front: File, back: File | null) {
    setPair(null)
    setError(null)
    setStep('UPLOADING')
    let cardId: string | null = null
    try {
      const card = await api<CardView>('POST', apiPath`/api/orgs/${orgId}/${kind}/${containerId}/cards`, { label: null, lot: false, quantity: 1 })
      cardId = card.id
      await uploadPhoto(api, orgId, card.id, 'FRONT', front, setStep)
      if (back) await uploadPhoto(api, orgId, card.id, 'BACK', back, setStep)
    } catch (err) {
      // Once the card exists, its own page shows what's still needed and takes the retry.
      if (!cardId) {
        setStep(null)
        setError(message(err))
        return
      }
    }
    navigate(`/orgs/${encodeURIComponent(orgId)}/cards/${encodeURIComponent(cardId)}`, { replace: true, state: { focusBack: !back } })
  }

  function chosen(e: ChangeEvent<HTMLInputElement>) {
    const both = pairOf(e.target.files)
    const file = e.target.files?.[0]
    e.target.value = ''
    if (both) setPair(both)
    else if (file) void start(file, null)
  }

  return (
    <>
      <p className="back">
        <Link to={`/orgs/${orgId}/${kind}/${containerId}`}>← Back</Link>
      </p>
      <h1>New card</h1>
      <p className="alert alert-info">Take the front first, then the back. We'll work out which card it is from them.</p>
      <p className="small">
        <strong>For a good first shot:</strong> take the card out of its sleeve, lay it flat on a plain, untextured background that contrasts with
        its border (no patterned mats or wood grain), and don't use the flash.
      </p>
      <section className="side" aria-label="Front photo">
        <div className="panel-head">
          <h2>Front</h2>
        </div>
        <p className="muted small">The side with the artwork and name. Or choose both photos at once, the front then the back.</p>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {step ? (
          <Loading>{STEP_WORDS[step]}</Loading>
        ) : pair ? (
          <PhotoPair files={pair} onUse={(f, b) => void start(f, b)} onCancel={() => setPair(null)} />
        ) : (
          <div className="actions">
            <label className="capture">
              <input type="file" accept="image/jpeg,image/png" capture="environment" onChange={chosen} aria-label="Take front photo" />
              Take photo
            </label>
            <label className="capture secondary">
              <input type="file" accept="image/jpeg,image/png" multiple onChange={chosen} aria-label="Choose front photo, or front and back" />
              Choose photos
            </label>
          </div>
        )}
      </section>
    </>
  )
}
