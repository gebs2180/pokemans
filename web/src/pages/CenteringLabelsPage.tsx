import { useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  apiPath,
  useApi,
  type CenteringCard,
  type CenteringFace,
  type CenteringFaceView,
  type CenteringLabel,
  type CenteringQueueItem,
  type RevealedLabel,
  type StraightenedCard,
} from '../api'
import { faceShares, ratio, SIDES, START_PX, worstShare, type Borders, type FrameSide } from '../centering'
import { GraderAllowances } from './GraderAllowances'
import { useGraderStandards } from '../graderStandards'
import { useLoad } from '../useLoad'
import { Loading, PageError } from '../ui'
import { message } from '../format'

const FACES: CenteringFace[] = ['FRONT', 'BACK']
/** How much of the card the zoom shows, in pixels of the straightened card (20 px/mm). */
const ZOOM_PX = 100

function bordersText(label: CenteringLabel) {
  return SIDES.map((s) => label[s]?.toFixed(2) ?? '–').join(' / ')
}

function FaceCell({ face }: { face: CenteringFaceView }) {
  return (
    <>
      {face.label ? bordersText(face.label) : <span className="muted">Not labelled</span>}
      <div className="muted small">
        {face.photos} photo{face.photos === 1 ? '' : 's'}
        {face.refused && ' · measurer refused'}
      </div>
    </>
  )
}

/** Admin: cards to label by hand, what the centering scoreboard scores the measurer against. */
export function CenteringLabelsPage() {
  const api = useApi()
  const { data, error } = useLoad(() => api<CenteringQueueItem[]>('GET', '/api/admin/centering-labels'), [api])
  const [downloadError, setDownloadError] = useState<string | null>(null)

  /** The file's links are short-lived, so it's made when asked for. */
  async function download() {
    setDownloadError(null)
    try {
      const file = await api<unknown>('GET', '/api/admin/centering-labels/export')
      const link = document.createElement('a')
      link.href = URL.createObjectURL(new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' }))
      link.download = `centering-scoreboard-${new Date().toISOString().slice(0, 10)}.json`
      link.click()
      URL.revokeObjectURL(link.href)
    } catch (e) {
      setDownloadError(message(e))
    }
  }

  if (error) return <PageError message={error} />
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Centering labels</h1>
      <p className="muted">
        Borders in mm, left / right / top / bottom, as a person reads them. One label covers every photo of that side of the card. Cards
        the measurer refused come first. Labelling is blind: the ratios, the grades they allow and the measurer's reading show only
        after you press Done.
      </p>
      <p className="row">
        <button onClick={download}>Download scoreboard file</button>
      </p>
      {downloadError && <p className="error" role="alert">{downloadError}</p>}
      {!data && <Loading />}
      {data?.length === 0 && <p className="muted">No cards have a complete capture yet.</p>}
      {data && data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Card</th>
              <th>Front</th>
              <th>Back</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.cardId}>
                <td>
                  {c.identifiedAs ?? c.label}
                  <div className="muted small">{c.orgName}</div>
                </td>
                <td>
                  <FaceCell face={c.front} />
                </td>
                <td>
                  <FaceCell face={c.back} />
                </td>
                <td>
                  <Link to={apiPath`/admin/centering/${c.orgId}/${c.cardId}`}>Label</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}

/** Admin: one card, a face at a time. */
export function CenteringLabelCardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const base = apiPath`/api/admin/orgs/${orgId}/cards/${cardId}`
  const { data, error } = useLoad(() => api<CenteringCard>('GET', `${base}/centering-labels`), [api, base])
  const [face, setFace] = useState<CenteringFace>('FRONT')

  if (error) return <PageError message={error} />
  if (!data) return <Loading />
  return (
    <>
      <p>
        <Link to="/admin/centering">← Centering labels</Link>
      </p>
      <h1>{data.identifiedAs ?? data.label}</h1>
      <p className="row">
        {FACES.map((f) => (
          <button key={f} className={f === face ? undefined : 'link'} aria-pressed={f === face} onClick={() => setFace(f)}>
            {f === 'FRONT' ? 'Front' : 'Back'}
          </button>
        ))}
      </p>
      <FaceEditor key={face} card={data} face={face} base={base} onOtherFace={() => setFace(face === 'FRONT' ? 'BACK' : 'FRONT')} />
    </>
  )
}

export type Positions = Record<FrameSide, number>
export type Hidden = Record<FrameSide, boolean>

function FaceEditor({ card, face, base, onOtherFace }: { card: CenteringCard; face: CenteringFace; base: string; onOtherFace: () => void }) {
  const api = useApi()
  const [revealed, setRevealed] = useState<RevealedLabel | null>(null)
  const photos = card.photos.filter((p) => p.face === face)
  const label = face === 'FRONT' ? card.front : card.back
  const [photoId, setPhotoId] = useState(photos.at(-1)?.id ?? '')
  const { data: view, error } = useLoad(
    () => (photoId ? api<StraightenedCard>('GET', `${base}/photos/${encodeURIComponent(photoId)}/straightened`) : Promise.resolve(null)),
    [api, base, photoId],
  )
  // A label is in mm, shown again on the card at its 20 px/mm.
  const [positions, setPositions] = useState<Positions>(() => ({
    left: Math.round((label?.left ?? START_PX / 20) * 20),
    right: Math.round((label?.right ?? START_PX / 20) * 20),
    top: Math.round((label?.top ?? START_PX / 20) * 20),
    bottom: Math.round((label?.bottom ?? START_PX / 20) * 20),
  }))
  const [hidden, setHidden] = useState<Hidden>(() => ({
    left: label != null && label.left == null,
    right: label != null && label.right == null,
    top: label != null && label.top == null,
    bottom: label != null && label.bottom == null,
  }))
  const [selected, setSelected] = useState<FrameSide>('left')
  const [along, setAlong] = useState<number | null>(null)
  const [contrast, setContrast] = useState(false)
  const [hard, setHard] = useState(label?.hardCard ?? false)
  const [dragging, setDragging] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  function move(side: FrameSide, px: number) {
    setPositions((p) => ({ ...p, [side]: Math.max(0, Math.round(px)) }))
  }

  function nudge(side: FrameSide, by: number) {
    setPositions((p) => ({ ...p, [side]: Math.max(0, p[side] + by) }))
  }

  /** Arrow keys move the chosen line 1 px (0.05 mm), inward on up or left of the card's own direction. */
  function onKey(e: KeyboardEvent) {
    const vertical = selected === 'left' || selected === 'right'
    const towardsEnd = vertical ? e.key === 'ArrowRight' : e.key === 'ArrowDown'
    const towardsStart = vertical ? e.key === 'ArrowLeft' : e.key === 'ArrowUp'
    if (!towardsEnd && !towardsStart) return
    e.preventDefault()
    const inward = selected === 'left' || selected === 'top' ? towardsEnd : towardsStart
    nudge(selected, inward ? 1 : -1)
  }

  async function save() {
    setErr(null)
    setBusy(true)
    try {
      const mm = (side: FrameSide) => (hidden[side] ? null : positions[side] / (view?.pxPerMm ?? 20))
      // Saving is the reveal: the reply is the first time this page sees the measurer's numbers.
      const result = await api<RevealedLabel>('POST', `${base}/centering-labels`, {
        face,
        photoId,
        left: mm('left'),
        right: mm('right'),
        top: mm('top'),
        bottom: mm('bottom'),
        hardCard: hard,
      })
      setRevealed(result)
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  if (revealed) return <Reveal face={face} revealed={revealed} onOtherFace={onOtherFace} />

  if (photos.length === 0) return <p className="warn">No passing photo of this side.</p>
  return (
    <section className="panel" onKeyDown={onKey}>
      {photos.length > 1 && (
        <p>
          <label>
            Photo{' '}
            <select value={photoId} onChange={(e) => setPhotoId(e.target.value)} aria-label="Photo">
              {photos.map((p, i) => (
                <option key={p.id} value={p.id}>
                  {i + 1} of {photos.length}, {new Date(p.uploadedAt).toLocaleString()}
                </option>
              ))}
            </select>
          </label>{' '}
          <span className="muted small">Pick the clearest: the label covers all of them.</span>
        </p>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      {!view && !error && <p>Straightening the card…</p>}
      {view?.refused && <p className="warn">This photo can't be straightened: {view.refused}. Try another photo of this side.</p>}
      {view?.image && (
        <div className="frame-editor">
          <FrameCard
            view={view}
            contrast={contrast}
            positions={positions}
            hidden={hidden}
            selected={selected}
            dragging={dragging}
            onDrag={(side, px, at) => {
              setSelected(side)
              setAlong(at)
              move(side, px)
            }}
            onDragging={setDragging}
          />
          <div className="frame-controls">
            <FrameZoom view={view} contrast={contrast} side={selected} position={positions[selected]} along={along} hidden={hidden[selected]} />
            <p className="muted small">Drag the lines onto the inside edge of the printed frame. Arrow keys move the chosen line 0.05 mm.</p>
            <table className="table">
              <tbody>
                {SIDES.map((side) => (
                  <tr key={side} className={side === selected ? 'frame-chosen' : undefined}>
                    <td>
                      <button className="link" aria-pressed={side === selected} onClick={() => setSelected(side)}>
                        {side[0].toUpperCase() + side.slice(1)}
                      </button>
                    </td>
                    <td>{hidden[side] ? '–' : `${(positions[side] / view.pxPerMm).toFixed(2)} mm`}</td>
                    <td>
                      <button className="link" aria-label={`Move ${side} out`} onClick={() => nudge(side, -1)}>
                        −
                      </button>{' '}
                      <button className="link" aria-label={`Move ${side} in`} onClick={() => nudge(side, 1)}>
                        +
                      </button>
                    </td>
                    <td>
                      <label className="small">
                        <input type="checkbox" checked={hidden[side]} onChange={(e) => setHidden((h) => ({ ...h, [side]: e.target.checked }))} />{' '}
                        Can't see it
                      </label>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              <label>
                <input type="checkbox" checked={contrast} onChange={(e) => setContrast(e.target.checked)} /> Raise contrast
              </label>
            </p>
            <p>
              <label>
                <input type="checkbox" checked={hard} onChange={(e) => setHard(e.target.checked)} /> Hard card (gold, foil, full art)
              </label>
            </p>
            {err && <p className="error" role="alert">{err}</p>}
            <p className="muted small">Ratios, grades and the measurer's reading show after Done, so they can't pull the lines.</p>
            <button onClick={save} disabled={busy}>
              Done
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

/** After Done: the label beside the measurer's reading of the same photo, and what each grader allows. */
function Reveal({ face, revealed, onOtherFace }: { face: CenteringFace; revealed: RevealedLabel; onOtherFace: () => void }) {
  const { data: standards, error } = useGraderStandards()
  const yours: Borders = revealed.label
  const system: Borders = revealed.system
  const mm = (v: number | null) => (v == null ? '–' : v.toFixed(2))
  const rows: { name: string; b: Borders }[] = [{ name: 'Yours', b: yours }]
  if (!revealed.system.refused) rows.push({ name: 'Measurer', b: system })
  return (
    <section className="panel" aria-label="Your label and the measurer's">
      <h2>Saved</h2>
      <table className="table">
        <thead>
          <tr>
            <th />
            <th>Left / right / top / bottom (mm)</th>
            <th>Left/right</th>
            <th>Top/bottom</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ name, b }) => (
            <tr key={name}>
              <td>{name}</td>
              <td>{SIDES.map((s) => mm(b[s])).join(' / ')}</td>
              <td>{ratio(b.left, b.right) ?? '–'}</td>
              <td>{ratio(b.top, b.bottom) ?? '–'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {revealed.system.refused && <p className="muted">The measurer gave no reading of this photo: {revealed.system.refused}.</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {standards && (
        <GraderAllowances
          standards={standards}
          readings={rows.map(({ name, b }) => ({ heading: name, ...faceShares(face, worstShare(b)) }))}
        />
      )}
      <p className="row">
        <button onClick={onOtherFace}>Label the {face === 'FRONT' ? 'back' : 'front'}</button>
        <Link to="/admin/centering">Back to the queue</Link>
      </p>
    </section>
  )
}

/** A line's position on the card, from its distance in from that side's own edge. */
function linePosition(side: FrameSide, px: number, view: StraightenedCard) {
  if (side === 'left' || side === 'top') return px
  return (side === 'right' ? view.width : view.height) - 1 - px
}

function FrameLines({ view, positions, selected }: { view: StraightenedCard; positions: Partial<Positions>; selected: FrameSide }) {
  return (
    <>
      {SIDES.map((side) => {
        const px = positions[side]
        if (px === undefined) return null
        const at = linePosition(side, px, view)
        const vertical = side === 'left' || side === 'right'
        return (
          <line
            key={side}
            className={side === selected ? 'frame-line frame-line-chosen' : 'frame-line'}
            x1={vertical ? at : 0}
            x2={vertical ? at : view.width}
            y1={vertical ? 0 : at}
            y2={vertical ? view.height : at}
            vectorEffect="non-scaling-stroke"
          />
        )
      })}
    </>
  )
}

export function FrameCard({
  view,
  contrast,
  positions,
  hidden,
  selected,
  dragging,
  onDrag,
  onDragging,
}: {
  view: StraightenedCard
  contrast: boolean
  positions: Positions
  hidden: Hidden
  selected: FrameSide
  dragging: boolean
  onDrag: (side: FrameSide, px: number, along: number) => void
  onDragging: (on: boolean) => void
}) {
  const shown = Object.fromEntries(SIDES.filter((s) => !hidden[s]).map((s) => [s, positions[s]])) as Partial<Positions>
  const [held, setHeld] = useState<FrameSide>(selected)

  function drag(e: PointerEvent<SVGSVGElement>, side: FrameSide) {
    const box = e.currentTarget.getBoundingClientRect()
    const x = ((e.clientX - box.left) / box.width) * view.width
    const y = ((e.clientY - box.top) / box.height) * view.height
    const px = side === 'left' ? x : side === 'top' ? y : side === 'right' ? view.width - 1 - x : view.height - 1 - y
    onDrag(side, px, side === 'left' || side === 'right' ? y : x)
  }

  /** Grabs the line nearest the pointer. */
  function start(e: PointerEvent<SVGSVGElement>) {
    const box = e.currentTarget.getBoundingClientRect()
    const x = ((e.clientX - box.left) / box.width) * view.width
    const y = ((e.clientY - box.top) / box.height) * view.height
    let nearest: FrameSide = selected
    let distance = Infinity
    for (const side of SIDES.filter((s) => !hidden[s])) {
      const d = Math.abs((side === 'left' || side === 'right' ? x : y) - linePosition(side, positions[side], view))
      if (d < distance) {
        distance = d
        nearest = side
      }
    }
    setHeld(nearest)
    onDragging(true)
    // Focus stays here, so the arrow keys fine-tune the line just dragged.
    e.currentTarget.focus()
    e.currentTarget.setPointerCapture?.(e.pointerId)
    drag(e, nearest)
  }

  return (
    <svg
      className="frame-card"
      viewBox={`0 0 ${view.width} ${view.height}`}
      role="img"
      aria-label="Straightened card with the frame lines on it"
      tabIndex={0}
      onPointerDown={start}
      onPointerMove={(e) => dragging && drag(e, held)}
      onPointerUp={() => onDragging(false)}
      onPointerCancel={() => onDragging(false)}
    >
      <image href={(contrast ? view.contrast : view.image) ?? undefined} width={view.width} height={view.height} />
      <FrameLines view={view} positions={shown} selected={selected} />
    </svg>
  )
}

/** The chosen line close up, where its pixels can be told apart. */
function FrameZoom({
  view,
  contrast,
  side,
  position,
  along,
  hidden,
}: {
  view: StraightenedCard
  contrast: boolean
  side: FrameSide
  position: number
  along: number | null
  hidden: boolean
}) {
  const vertical = side === 'left' || side === 'right'
  const at = linePosition(side, position, view)
  const middle = along ?? (vertical ? view.height : view.width) / 2
  const x = (vertical ? at : middle) - ZOOM_PX / 2
  const y = (vertical ? middle : at) - ZOOM_PX / 2
  return (
    <svg className="frame-zoom" viewBox={`${x} ${y} ${ZOOM_PX} ${ZOOM_PX}`} role="img" aria-label={`Close-up of the ${side} line`}>
      <image href={(contrast ? view.contrast : view.image) ?? undefined} width={view.width} height={view.height} />
      {!hidden && <FrameLines view={view} positions={{ [side]: position }} selected={side} />}
    </svg>
  )
}
