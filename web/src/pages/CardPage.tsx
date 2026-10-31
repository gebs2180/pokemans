import { useState, type ChangeEvent, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  apiPath,
  SIDE_LABEL,
  uploadToStorage,
  useApi,
  type CardStatus,
  type CardView,
  type ConditionSummary,
  type SideCentering,
  type Grader,
  type OutcomeView,
  type PhotoView,
  type Side,
  type UploadSlot,
} from '../api'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'
import { useOrg } from '../useOrg'
import { Chip, Loading, StageChip } from '../ui'
import { OfferPanel } from './OfferPanel'
import { ProvenancePanel } from './ProvenancePanel'

/** What each side should show, so a back in the Front slot is caught before it's sent. */
const SIDE_HINT: Record<Side, string> = {
  FRONT: 'The side with the artwork and name.',
  BACK: 'The blue Poké Ball side.',
  DETAIL: 'A close-up of anything worth a closer look, e.g. the holo surface or a corner.',
}

/** Capture screen for one card: take each required side, see pass or retake right away. */
export function CardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: card, error, reload } = useLoad(() => api<CardView>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}`), [api, orgId, cardId])
  const [busy, setBusy] = useState<Side | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const { data: org } = useOrg(orgId)
  const seller = org != null && (org.type === 'COLLECTOR' || org.type === 'CONTRIBUTOR')
  // Bumped after each upload, so the card's status (and offer panel) catch up with the photos.
  const [photoVersion, setPhotoVersion] = useState(0)
  // Bumped when the offer panel posts or withdraws: photos freeze or thaw, and the stage moves on.
  const [saleVersion, setSaleVersion] = useState(0)

  async function capture(side: Side, e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setBusy(side)
    setUploadError(null)
    try {
      const slot = await api<UploadSlot>('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/photos`, {
        side,
        contentType: file.type,
        sizeBytes: file.size,
      })
      await uploadToStorage(slot, file)
      await api<PhotoView>('POST', apiPath`/api/orgs/${orgId}/photos/${slot.photoId}/confirm`)
      reload()
      setPhotoVersion((v) => v + 1)
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(null)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!card) return <Loading />

  const sides: Side[] = card.lot ? ['FRONT', 'DETAIL'] : ['FRONT', 'BACK', 'DETAIL']
  const offerPanel = seller && !card.lot && (
    <OfferPanel
      key={photoVersion}
      orgId={orgId}
      cardId={cardId}
      onChange={() => {
        reload()
        setSaleVersion((v) => v + 1)
      }}
    />
  )
  // Once the photos pass, the offer is the next thing to do: it comes first, above the photos.
  const offerFirst = card.captureComplete
  return (
    <>
      <p className="back">
        <button className="link" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </p>
      <h1>{card.label}</h1>
      {seller && !card.lot && <StatusLine key={`${photoVersion}:${saleVersion}`} orgId={orgId} cardId={cardId} />}
      <p className={`capture-summary alert ${card.captureComplete ? 'alert-success' : 'alert-warning'}`}>
        {card.captureComplete
          ? 'All required photos passed.'
          : `Still needed: ${card.missingSides.map((s) => SIDE_LABEL[s].toLowerCase()).join(' and ')}.`}
      </p>
      {card.photosFrozen && <p className="muted small">{card.photosFrozen}</p>}
      {card.lot && <p className="muted">Lot of {card.quantity}: one photo of the whole lot, laid out flat, is enough. Lots don't get offers.</p>}
      {uploadError && <p className="error" role="alert">{uploadError}</p>}
      {offerFirst && offerPanel}

      {sides.map((side) => {
        const attempts = card.photos.filter((p) => p.side === side && p.status !== 'AWAITING_UPLOAD')
        const current = attempts.find((p) => p.current) ?? null
        const latest = attempts.at(-1)
        const required = side !== 'DETAIL'
        const retake = latest && latest !== current ? latest : null
        return (
          <section key={side} className="side" aria-label={`${SIDE_LABEL[side]} photo`}>
            <div className="panel-head">
              <h2>{SIDE_LABEL[side]}</h2>
              {current ? (
                <Chip tone="success">Passed</Chip>
              ) : retake ? (
                <Chip tone="warning">Retake needed</Chip>
              ) : required ? (
                <Chip tone="warning">Needed</Chip>
              ) : (
                <Chip>Optional</Chip>
              )}
            </div>
            <div className="shot">
              {current ? <CurrentPhoto side={side} photo={current} /> : <div className="shot-empty" aria-hidden="true">No photo yet</div>}
              <div className="shot-body">
                <p className="muted small">{SIDE_HINT[side]}</p>
                {current && (
                  <p className="muted small">
                    {current.width}×{current.height}
                  </p>
                )}
                {attempts.length > 1 && <p className="muted small">{attempts.length} attempts, all kept; the one shown is used.</p>}
              </div>
            </div>
            {retake && <RetakeResult photo={retake} keeping={current !== null} />}
            {!card.photosFrozen &&
              (busy === side ? (
                <Loading>Uploading and checking…</Loading>
              ) : (
                <div className="actions">
                  {/* The camera straight away, or a photo already taken (e.g. from a lightbox setup): Android opens only the camera with capture set. */}
                  <label className={current ? 'capture secondary' : 'capture'}>
                    <input
                      type="file"
                      accept="image/jpeg,image/png"
                      capture="environment"
                      disabled={busy !== null}
                      onChange={(e) => capture(side, e)}
                      aria-label={`${latest ? 'Retake' : 'Take'} ${SIDE_LABEL[side].toLowerCase()} photo`}
                    />
                    {latest ? 'Retake' : 'Take photo'}
                  </label>
                  <label className="capture secondary">
                    <input
                      type="file"
                      accept="image/jpeg,image/png"
                      disabled={busy !== null}
                      onChange={(e) => capture(side, e)}
                      aria-label={`Choose a ${SIDE_LABEL[side].toLowerCase()} photo`}
                    />
                    Choose a photo
                  </label>
                </div>
              ))}
          </section>
        )
      })}
      {org?.type === 'CONTRIBUTOR' && !card.lot && <GradedResult orgId={orgId} cardId={cardId} ready={card.captureComplete} />}
      {!offerFirst && offerPanel}
      {/* Authenticity, and who holds the card (needed before it can back an advance; hidden while collateral is off). */}
      {org && org.type !== 'CONTRIBUTOR' && <ProvenancePanel orgId={orgId} cardId={cardId} admin={false} />}
    </>
  )
}

/** Where the card is on the way to a sale, the card as we identified it, and what happens next. */
function StatusLine({ orgId, cardId }: { orgId: string; cardId: string }) {
  const api = useApi()
  const { data } = useLoad(() => api<CardStatus>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}/status`), [api, orgId, cardId])
  if (!data) return null
  return (
    <div className="status-line">
      {data.identity && (
        <p>
          <span className="muted">Identified as</span> <strong>{data.identity}</strong>
        </p>
      )}
      {data.condition && <LikelyGrade condition={data.condition} />}
      <p>
        <StageChip stage={data.stage} />
      </p>
      <p className="muted small">{data.nextStep}</p>
    </div>
  )
}

/** Our estimate of the grade from the photos, in words. Never a provider's grade: the offer is what's firm. */
export function LikelyGrade({ condition }: { condition: ConditionSummary }) {
  const { low, high, mostLikely, mayBeUngradable, holdingBack, centering } = condition
  const measured = [centeringText('front', centering?.front), centeringText('back', centering?.back)].filter((t) => t != null)
  const graded = mostLikely !== 0
  if (!graded && measured.length === 0 && !mayBeUngradable) return null
  const range = low === high ? `PSA ${low}` : `PSA ${low}–${high}, most likely ${mostLikely}`
  return (
    <div className="likely-grade">
      {graded && (
        <p>
          <span className="muted">Likely grade</span> <strong>{range}</strong>
        </p>
      )}
      {holdingBack.length > 0 && (
        <p className="small">
          <span className="muted">Holding it back:</span> {holdingBack.join(' and ')}
        </p>
      )}
      {measured.length > 0 && (
        <p className="small">
          <span className="muted">Centering, measured from your photos:</span> {measured.join(' · ')}
        </p>
      )}
      {mayBeUngradable && <p className="small warn">It may not be gradable at all, e.g. if it's been altered or damaged.</p>}
      {graded && (
        <p className="muted small">Our estimate from your photos, not a grade. An offer's price stays the same whatever grade the card gets.</p>
      )}
    </div>
  )
}

function centeringText(side: string, c: SideCentering | null | undefined) {
  return c ? `${side} ${c.leftRight} left-right, ${c.topBottom} top-bottom` : null
}

function CurrentPhoto({ side, photo }: { side: Side; photo: PhotoView }) {
  const href = safeHref(photo.url)
  return (
    <div className="current-photo">
      {href ? <img src={href} alt={`${SIDE_LABEL[side]} photo you took`} className="thumb" /> : <div className="shot-empty">Passed</div>}
    </div>
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
  const path = apiPath`/api/orgs/${orgId}/cards/${cardId}/outcomes`
  const { data: outcomes, reload } = useLoad(() => api<OutcomeView[]>('GET', path), [api, path])
  const [grader, setGrader] = useState<Grader>('PSA')
  const [certNumber, setCertNumber] = useState('')
  const [grade, setGrade] = useState('')
  const [gradeLabel, setGradeLabel] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const gradeValue = grade === '' ? null : Number(grade)
    if (gradeValue !== null && !Number.isFinite(gradeValue)) {
      setErr('Grade must be a number from 1 to 10.')
      return
    }
    setErr(null)
    setBusy(true)
    try {
      await api('POST', path, { grader, certNumber, grade: gradeValue, gradeLabel })
      setCertNumber('')
      setGrade('')
      setGradeLabel('')
      reload()
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
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
          <select value={grader} onChange={(e) => setGrader(e.target.value as Grader)} aria-label="Grader">
            {GRADERS.map((g) => (
              <option key={g}>{g}</option>
            ))}
          </select>
          <input value={certNumber} onChange={(e) => setCertNumber(e.target.value)} placeholder="Cert number" aria-label="Cert number" required pattern="[A-Za-z0-9-]+" />
          <input
            type="number"
            min={1}
            max={10}
            step={0.5}
            value={grade}
            onChange={(e) => setGrade(e.target.value)}
            placeholder="Grade"
            aria-label="Grade"
            className="narrow"
          />
          <input value={gradeLabel} onChange={(e) => setGradeLabel(e.target.value)} placeholder="Label, e.g. GEM MT" aria-label="Grade label" />
          <button type="submit" disabled={busy}>
            Save
          </button>
        </form>
      )}
      {err && <p className="error" role="alert">{err}</p>}
    </section>
  )
}

/** The newest attempt, when it didn't pass: why, and whether an earlier photo is still the one used. */
function RetakeResult({ photo, keeping }: { photo: PhotoView; keeping: boolean }) {
  return (
    <div className="alert alert-warning">
      <p className="alert-title">{keeping ? 'Your last try needs a retake (the photo above is still the one used):' : 'Retake needed:'}</p>
      <ul>
        {photo.retakeReasons.map((r) => (
          <li key={r.code}>{r.message}</li>
        ))}
      </ul>
    </div>
  )
}
