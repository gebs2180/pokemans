import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import {
  apiPath,
  deadlineText,
  shortDate,
  SIDE_LABEL,
  useApi,
  type CardStage,
  type CardStatus,
  type CardView,
  type ConditionSummary,
  type SideCentering,
  type Grader,
  type OutcomeView,
  type PhotoView,
  type Side,
} from '../api'
import { safeHref } from '../safeHref'
import { useLoad, usePollBackoff } from '../useLoad'
import { useOrg } from '../useOrg'
import { Chip, Loading, PageError, StageChip } from '../ui'
import { IdentifyPanel } from './IdentifyPanel'
import { OfferPanel } from './OfferPanel'
import { ProvenancePanel } from './ProvenancePanel'
import { MarkedSurfacePanel } from './DefectMarks'
import { message } from '../format'
import { PhotoPair } from './PhotoPair'
import { pairOf, STEP_WORDS, uploadPhoto, type UploadStep } from './photoUpload'

/** What each side should show, so a back in the Front slot is caught before it's sent. */
const SIDE_HINT: Record<Side, string> = {
  FRONT: 'The side with the artwork and name.',
  BACK: 'The blue Poké Ball side.',
  DETAIL: 'A close-up of anything worth a closer look, e.g. the holo surface or a corner.',
}

/** Stages whose next step the offer panel says, right below the status line, in place of its button. */
const SAID_BY_OFFER_PANEL = new Set<CardStage>(['IDENTIFYING', 'ASSESSING', 'PRICING', 'NO_OFFER', 'OVER_LIMIT', 'READY', 'LISTED', 'SOLD', 'SHIPPED', 'ARRIVED', 'PAID', 'RETURNED'])

/** Stages we're working through ourselves, so the page checks back by itself. */
const WORKING_ON_IT = new Set<CardStage>(['IDENTIFYING', 'ASSESSING', 'PRICING'])
const WORKING_WORDS = {
  IDENTIFYING: 'Once the card is confirmed, we check it against your photos.',
  ASSESSING: 'We\'re checking its condition.',
  PRICING: 'We\'re finding recent sold prices for it.',
}

/** Capture screen for one card: take each required side, see pass or retake right away. */
export function CardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: card, error, reload } = useLoad(() => api<CardView>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}`), [api, orgId, cardId])
  const location = useLocation()
  const [busy, setBusy] = useState<Side | null>(null)
  const [step, setStep] = useState<UploadStep>('UPLOADING')
  // Front and back chosen in one go, waiting on the collector to check which is which.
  const [pair, setPair] = useState<[File, File] | null>(null)
  // After the front passes, the back's Take control comes next (review finding 13).
  const focusBack = useRef((location.state as { focusBack?: boolean } | null)?.focusBack === true)
  const backTake = useRef<HTMLInputElement>(null)
  // Shown in the side's own panel: on a phone the top of the page is off-screen.
  const [uploadError, setUploadError] = useState<{ side: Side; message: string } | null>(null)
  const { data: org } = useOrg(orgId)
  const seller = org != null && (org.type === 'COLLECTOR' || org.type === 'CONTRIBUTOR')
  const contributor = org?.type === 'CONTRIBUTOR'
  // Bumped after each upload, so the card's status (and offer panel) catch up with the photos.
  const [photoVersion, setPhotoVersion] = useState(0)
  // Bumped when the offer panel posts or withdraws: photos freeze or thaw, and the stage moves on.
  const [saleVersion, setSaleVersion] = useState(0)
  // Bumped when polling sees the card finish being worked on, so the offer panel reloads its status.
  const [settledVersion, setSettledVersion] = useState(0)
  const onSettled = useCallback(() => setSettledVersion((v) => v + 1), [])

  useEffect(() => {
    if (!focusBack.current || !card) return
    const frontPassed = card.photos.some((p) => p.side === 'FRONT' && p.current)
    if (frontPassed && card.missingSides.includes('BACK') && backTake.current) {
      backTake.current.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
      backTake.current.focus()
    }
    if (frontPassed || !card.missingSides.includes('FRONT')) focusBack.current = false
  }, [card])

  function chosen(side: Side, e: ChangeEvent<HTMLInputElement>) {
    const both = side === 'FRONT' ? pairOf(e.target.files) : null
    const file = e.target.files?.[0]
    e.target.value = ''
    if (both) setPair(both)
    else if (file) void capture([[side, file]])
  }

  async function capture(shots: [Side, File][]) {
    setPair(null)
    setUploadError(null)
    for (const [side, file] of shots) {
      setBusy(side)
      try {
        await uploadPhoto(api, orgId, cardId, side, file, setStep)
      } catch (err) {
        setUploadError({ side, message: message(err) })
        break
      }
    }
    if (shots.length === 1 && shots[0][0] === 'FRONT') focusBack.current = true
    setBusy(null)
    reload()
    setPhotoVersion((v) => v + 1)
  }

  if (error) return <PageError message={error} />
  if (!card) return <Loading />

  const sides: Side[] = card.lot ? ['FRONT', 'DETAIL'] : ['FRONT', 'BACK', 'DETAIL']
  const offerPanel = seller && !card.lot && (
    <OfferPanel
      key={`offer:${photoVersion}:${settledVersion}`}
      orgId={orgId}
      cardId={cardId}
      frontUrl={safeHref(card.photos.find((p) => p.side === 'FRONT' && p.current)?.url)}
      onChange={() => {
        reload()
        setSaleVersion((v) => v + 1)
      }}
    />
  )
  // Once the photos pass, the offer is the next thing to do: it comes first, above the photos.
  const offerFirst = card.captureComplete && !contributor
  // Until front and back pass there's one thing to do: one instruction, and nothing else asking for them (review finding 11).
  const starting = !card.captureComplete && !card.lot
  const shown = starting ? sides.filter((s) => s !== 'DETAIL') : sides
  return (
    <>
      <p className="back">
        <button className="link" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </p>
      <h1>{card.label}</h1>
      {starting && (
        <p className="alert alert-info" role="status">
          {card.missingSides.includes('FRONT')
            ? "Take the front first, then the back. We'll work out which card it is from them."
            : 'Now the back: the blue Poké Ball side.'}
        </p>
      )}
      {org != null && !starting && !card.lot && <IdentifyPanel orgId={orgId} cardId={cardId} photoVersion={photoVersion} onAnswer={reload} />}
      {seller && !contributor && !starting && !card.lot && (
        <StatusLine
          key={`status:${photoVersion}:${saleVersion}`}
          orgId={orgId}
          cardId={cardId}
          heading={card.label}
          offerFirst={offerFirst}
          onSettled={onSettled}
        />
      )}
      {contributor && !card.lot && (
        <p className="alert alert-info">
          We need photos of this card from before it was graded: the raw card, out of the slab. Photos of the slab can't be used.
        </p>
      )}
      {!starting && (
        <p className={`capture-summary alert ${card.captureComplete ? 'alert-success' : 'alert-warning'}`}>
          {card.captureComplete
            ? 'All required photos passed.'
            : `Still needed: ${card.missingSides.map((s) => SIDE_LABEL[s].toLowerCase()).join(' and ')}.`}
        </p>
      )}
      {card.photosFrozen && <p className="muted small">{card.photosFrozen}</p>}
      {card.lot && <p className="muted">Lot of {card.quantity}: one photo of the whole lot, laid out flat, is enough. Lots don't get offers.</p>}
      {offerFirst && offerPanel}

      {!contributor && !card.lot && !card.photosFrozen && !card.photos.some((p) => p.status !== 'AWAITING_UPLOAD') && (
        <p className="small">
          <strong>For a good first shot:</strong> take the card out of its sleeve, lay it flat on a plain, untextured background that contrasts
          with its border (no patterned mats or wood grain), and don't use the flash.
        </p>
      )}

      {shown.map((side) => {
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
              {current ? (
                <CurrentPhoto side={side} photo={current} />
              ) : retake ? (
                <RejectedPhoto side={side} photo={retake} />
              ) : (
                <div className="shot-empty" aria-hidden="true">No photo yet</div>
              )}
              <div className="shot-body">
                <p className="muted small">{SIDE_HINT[side]}</p>
                {current && (
                  <p className="muted small">
                    {current.width}×{current.height}
                  </p>
                )}
                {current && attempts.length > 1 && <p className="muted small">{attempts.length} attempts, all kept; the one shown is used.</p>}
                {current?.lookalikeCardId && (
                  <p className="warn small" role="note">
                    This looks like the same photo as the front of{' '}
                    <Link to={`/orgs/${orgId}/cards/${current.lookalikeCardId}`}>another of your cards</Link>. If it's the wrong
                    card, take a new photo of this one.
                  </p>
                )}
              </div>
            </div>
            {/* A new upload replaces the last verdict: don't leave the old "retake" up while it's checked. */}
            {retake && busy !== side && <RetakeResult side={side} photo={retake} keeping={current !== null} />}
            {uploadError?.side === side && <p className="error" role="alert">{uploadError.message}</p>}
            {!card.photosFrozen &&
              (busy === side ? (
                <Loading>{STEP_WORDS[step]}</Loading>
              ) : pair && side === 'FRONT' ? (
                <PhotoPair
                  files={pair}
                  onUse={(f, b) =>
                    void capture([
                      ['FRONT', f],
                      ['BACK', b],
                    ])
                  }
                  onCancel={() => setPair(null)}
                />
              ) : (
                <div className="actions">
                  {/* The camera straight away, or a photo already taken (e.g. from a lightbox setup): Android opens only the camera with capture set. */}
                  <label className={current || contributor ? 'capture secondary' : 'capture'}>
                    <input
                      ref={side === 'BACK' ? backTake : undefined}
                      type="file"
                      accept="image/jpeg,image/png"
                      capture="environment"
                      disabled={busy !== null}
                      onChange={(e) => chosen(side, e)}
                      aria-label={`${latest ? 'Retake' : 'Take'} ${SIDE_LABEL[side].toLowerCase()} photo`}
                    />
                    {latest ? 'Retake' : 'Take photo'}
                  </label>
                  {/* Contributors are looking for photos from before grading, so choosing one is their main button. */}
                  <label className={contributor && !current ? 'capture' : 'capture secondary'}>
                    <input
                      type="file"
                      accept="image/jpeg,image/png"
                      // Front and back in one go, while neither has passed: the first is the front (review finding 13).
                      multiple={side === 'FRONT' && !card.lot && starting && !current}
                      disabled={busy !== null}
                      onChange={(e) => chosen(side, e)}
                      aria-label={`Choose a ${SIDE_LABEL[side].toLowerCase()} photo`}
                    />
                    {side === 'FRONT' && !card.lot && starting && !current ? 'Choose photos' : 'Choose a photo'}
                  </label>
                </div>
              ))}
          </section>
        )
      })}
      {/* A closer look at the passing photos, filters and zoom in the browser only: it changes nothing about the offer. */}
      {!starting && !card.lot && <MarkedSurfacePanel audience="collector" orgId={orgId} cardId={cardId} photos={card.photos.filter((p) => p.current)} />}
      {org?.type === 'CONTRIBUTOR' && !starting && !card.lot && <GradedResult orgId={orgId} cardId={cardId} ready={card.captureComplete} />}
      {!offerFirst &&
        !starting &&
        (contributor && offerPanel ? (
          <details className="disclosure">
            <summary>Selling this card instead?</summary>
            {offerPanel}
          </details>
        ) : (
          offerPanel
        ))}
      {/* Authenticity, and who holds the card: only once we know which card it is (review finding 11). */}
      {org && org.type !== 'CONTRIBUTOR' && (card.named || card.lot) && <ProvenancePanel orgId={orgId} cardId={cardId} admin={false} />}
      {seller && !contributor && !card.photosFrozen && <ArchiveCard orgId={orgId} cardId={cardId} />}
      {seller && (
        <p className="muted small">
          Something wrong with this card? <Link to="/how-it-works#contact">Tell us</Link>
        </p>
      )}
    </>
  )
}

/** Where the card is on the way to a sale, the card as we identified it, and what happens next. */
function StatusLine({
  orgId,
  cardId,
  heading,
  offerFirst,
  onSettled,
}: {
  orgId: string
  cardId: string
  heading: string
  offerFirst: boolean
  onSettled?: () => void
}) {
  const api = useApi()
  const { data, reload } = useLoad(() => api<CardStatus>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}/status`), [api, orgId, cardId])
  const working = data != null && WORKING_ON_IT.has(data.stage)
  const gaveUp = usePollBackoff(working, reload)
  // Once polling has seen the card move on from a working stage, the offer panel loads its status again.
  const wasWorking = useRef(false)
  useEffect(() => {
    if (!data) return
    if (WORKING_ON_IT.has(data.stage)) {
      wasWorking.current = true
    } else if (wasWorking.current) {
      wasWorking.current = false
      onSettled?.()
    }
  }, [data, onSettled])
  if (!data) return null
  const deadline = deadlineText(data)
  return (
    <div className="status-line">
      {data.identity && <IdentityExtras identity={data.identity} heading={heading} />}
      {data.condition && <LikelyGrade condition={data.condition} />}
      <p>
        <StageChip stage={data.stage} />
      </p>
      {working && !gaveUp && (
        <p role="status" className="small">
          {WORKING_WORDS[data.stage as keyof typeof WORKING_WORDS]} {data.waitingSince && `We've been on it since ${sinceText(data.waitingSince)}. `}
          This page updates by itself.
        </p>
      )}
      {working && gaveUp && <p className="muted small">This is taking longer than usual. Reload the page to check again.</p>}
      {data.stage === 'READY' && (
        <p role="status">
          <strong>Your card is ready: get your instant offer below.</strong>
        </p>
      )}
      {deadline && (
        <p>
          <strong>{deadline}</strong>
        </p>
      )}
      {!(offerFirst && SAID_BY_OFFER_PANEL.has(data.stage)) && <p className="muted small">{data.nextStep}</p>}
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
        <div className="small">
          <p>
            {centering?.bestPsaGrade != null ? (
              <>
                <span className="muted">Centering allows up to a</span> <strong>PSA {centering.bestPsaGrade}</strong>
              </>
            ) : (
              <span className="muted">Centering measured from your photos</span>
            )}
          </p>
          <details className="disclosure">
            <summary>The measurements</summary>
            <p>{measured.join(' · ')}</p>
          </details>
        </div>
      )}
      {mayBeUngradable && <p className="small warn">It may not be gradable at all, e.g. if it's been altered or damaged.</p>}
      {graded && (
        <p className="muted small">Our estimate from your photos, not a grade. An offer's price stays the same whatever grade the card gets.</p>
      )}
    </div>
  )
}

/**
 * What the card's identity adds to the heading, e.g. language and edition, and the way to say it's wrong; the name
 * isn't said twice (review finding 17).
 */
export function IdentityExtras({ identity, heading }: { identity: string; heading: string }) {
  const said = new Set(heading.split('·').map((t) => t.trim().toLowerCase()))
  const extras = identity
    .split('·')
    .map((t) => t.trim())
    .filter((t) => t && !said.has(t.toLowerCase()))
  return (
    <p className="small">
      {extras.length > 0 && <span className="muted">{extras.join(' · ')} · </span>}
      <Link to="/how-it-works#contact">Wrong card? Tell us</Link>
    </p>
  )
}

/** When a wait began, as a time today or a date before that. */
function sinceText(iso: string) {
  const at = new Date(iso)
  return at.toDateString() === new Date().toDateString()
    ? at.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
    : shortDate(iso)
}

/** Takes a card off the collector's list while nothing depends on it; its photos are kept (review finding 10). */
function ArchiveCard({ orgId, cardId }: { orgId: string; cardId: string }) {
  const api = useApi()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function archive() {
    setBusy(true)
    setError(null)
    try {
      await api<void>('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/archive`)
      navigate(`/orgs/${encodeURIComponent(orgId)}`, { replace: true })
    } catch (err) {
      setError(message(err))
      setBusy(false)
    }
  }
  return (
    <details className="disclosure">
      <summary>Remove this card</summary>
      <p className="small">It comes off your list. You can't remove a card with an offer or one that's been posted to buyers.</p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <button type="button" className="secondary" onClick={archive} disabled={busy} aria-busy={busy}>
        Remove card
      </button>
    </details>
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

/** A photo that didn't pass, so the seller can see what the reasons are about. Some can't be shown, e.g. a file that isn't an image. */
function RejectedPhoto({ side, photo }: { side: Side; photo: PhotoView }) {
  const [broken, setBroken] = useState(false)
  const href = safeHref(photo.url)
  if (!href || broken) return <div className="shot-empty">Can't show this photo</div>
  return (
    <div className="current-photo">
      <img src={href} alt={`${SIDE_LABEL[side]} photo that didn't pass`} className="thumb rejected" onError={() => setBroken(true)} />
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
      setErr(message(e))
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
function RetakeResult({ side, photo, keeping }: { side: Side; photo: PhotoView; keeping: boolean }) {
  return (
    <div className="alert alert-warning">
      <p className="alert-title">{keeping ? 'Your last try needs a retake (the photo above is still the one used):' : 'Retake needed:'}</p>
      {/* With no passing photo, the rejected one is already in the side's slot above. */}
      <div className={keeping ? 'shot' : undefined}>
        {keeping && <RejectedPhoto side={side} photo={photo} />}
        <ul>
          {photo.retakeReasons.map((r) => (
            <li key={r.code}>{r.message}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}
