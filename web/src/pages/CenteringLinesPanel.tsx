import { useState } from 'react'
import { apiPath, useApi, type ReviewPhoto, type StraightenedCard } from '../api'
import { onFrameKey, SIDES, START_PX, worstShare, ratio, type FrameSide } from '../centering'
import { useGraderStandards } from '../graderStandards'
import { useLoad } from '../useLoad'
import { FrameCard, FrameZoom, type Positions } from './CenteringLabelsPage'
import { GraderAllowances } from './GraderAllowances'
import { NudgeButtons, Segmented, ToolPanel } from './reviewTools'

type Face = 'FRONT' | 'BACK'
const FACES: Face[] = ['FRONT', 'BACK']
const START: Positions = { left: START_PX, right: START_PX, top: START_PX, bottom: START_PX }
const NONE_HIDDEN = { left: false, right: false, top: false, bottom: false }

/**
 * Reviewer: drag border lines onto the straightened front and back, and see what each grader's centering
 * standard allows for them. Nothing is stored, and the distribution isn't touched: it's a reading aid.
 * Lines start at a fixed 2.5 mm, like the labelling page. Closed until opened, below the photos.
 */
export function CenteringLinesPanel({ orgId, cardId, photos }: { orgId: string; cardId: string; photos: ReviewPhoto[] }) {
  if (!photos.some((p) => p.side === 'FRONT' || p.side === 'BACK')) return null
  return (
    <ToolPanel title="Centering lines" summary="Drag lines onto the frame to see what each grader allows. Nothing is saved.">
      <LinesTool orgId={orgId} cardId={cardId} photos={photos} />
    </ToolPanel>
  )
}

function LinesTool({ orgId, cardId, photos }: { orgId: string; cardId: string; photos: ReviewPhoto[] }) {
  const faces = FACES.filter((f) => photos.some((p) => p.side === f))
  const [face, setFace] = useState<Face>(faces[0] ?? 'FRONT')
  const [positions, setPositions] = useState<Record<Face, Positions>>({ FRONT: START, BACK: START })
  // A face counts towards the allowance only once its lines have been moved: untouched lines read 50/50.
  const [moved, setMoved] = useState<Record<Face, boolean>>({ FRONT: false, BACK: false })
  const photo = photos.find((p) => p.side === face)
  const shares = Object.fromEntries(FACES.map((f) => [f, moved[f] ? worstShare(asMm(positions[f])) : null])) as Record<Face, number | null>
  return (
    <>
      <p className="muted small">
        Drag each line onto the inside edge of the printed frame; the arrow keys move the chosen line 0.05 mm. Nothing is saved: this is
        for reading the card, the distribution is set below.
      </p>
      {faces.length > 1 && (
        <p>
          <Segmented label="Side" options={faces.map((f) => ({ value: f, label: f === 'FRONT' ? 'Front' : 'Back' }))} value={face} onChange={setFace} />
        </p>
      )}
      {photo && (
        <FaceLines
          key={photo.id}
          base={apiPath`/api/admin/orgs/${orgId}/cards/${cardId}/photos/${photo.id}/straightened`}
          positions={positions[face]}
          onMove={(side, px) => {
            setPositions((p) => ({ ...p, [face]: { ...p[face], [side]: Math.max(0, Math.round(px)) } }))
            setMoved((m) => ({ ...m, [face]: true }))
          }}
        />
      )}
      <Allowances front={shares.FRONT} back={shares.BACK} />
    </>
  )
}

/** The straightened card is 20 px/mm; shares don't depend on it. */
function asMm(p: Positions) {
  return { left: p.left / 20, right: p.right / 20, top: p.top / 20, bottom: p.bottom / 20 }
}

function Allowances({ front, back }: { front: number | null; back: number | null }) {
  const { data: standards, error } = useGraderStandards()
  if (error) return <p className="error" role="alert">{error}</p>
  if (!standards) return null
  if (front == null && back == null) return <p className="muted small">Move the lines to see what each grader allows.</p>
  // "Both" only when both faces are measured: otherwise it would just repeat the one that is.
  const readings = [
    { heading: 'Front', front, back: null },
    { heading: 'Back', front: null, back },
    ...(front != null && back != null ? [{ heading: 'Both', front, back }] : []),
  ].filter((r) => r.front != null || r.back != null)
  return (
    <>
      <GraderAllowances standards={standards} readings={readings} />
      {(front == null || back == null) && (
        <p className="muted small">{front == null ? 'Front' : 'Back'} not measured: move its lines to see both faces together.</p>
      )}
    </>
  )
}

function FaceLines({ base, positions, onMove }: { base: string; positions: Positions; onMove: (side: FrameSide, px: number) => void }) {
  const api = useApi()
  const { data: view, error } = useLoad(() => api<StraightenedCard>('GET', base), [api, base])
  const [selected, setSelected] = useState<FrameSide>('left')
  const [along, setAlong] = useState<number | null>(null)
  const [dragging, setDragging] = useState(false)
  const [contrast, setContrast] = useState(false)
  if (error) return <p className="error" role="alert">{error}</p>
  if (!view) return <p>Straightening the card…</p>
  if (!view.image) return <p className="warn">This photo can't be straightened: {view.refused}.</p>
  const mm = asMm(positions)
  const nudge = (side: FrameSide, by: number) => onMove(side, positions[side] + by)
  return (
    <div className="frame-editor" onKeyDown={(e) => onFrameKey(e, selected, nudge)}>
      <FrameCard
        view={view}
        contrast={contrast}
        positions={positions}
        hidden={NONE_HIDDEN}
        selected={selected}
        dragging={dragging}
        onDrag={(side, px, at) => {
          setSelected(side)
          setAlong(at)
          onMove(side, px)
        }}
        onDragging={setDragging}
      />
      <div className="frame-controls">
        <FrameZoom view={view} contrast={contrast} side={selected} position={positions[selected]} along={along} hidden={false} />
        <table className="table">
          <tbody>
            {SIDES.map((side) => (
              <tr key={side} className={side === selected ? 'frame-chosen' : undefined}>
                <td>
                  <button className="link" aria-pressed={side === selected} onClick={() => setSelected(side)}>
                    {side[0].toUpperCase() + side.slice(1)}
                  </button>
                </td>
                <td>{mm[side].toFixed(2)} mm</td>
                <td>
                  <NudgeButtons side={side} onNudge={(by) => nudge(side, by)} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="small">
          Left/right {ratio(mm.left, mm.right)} · top/bottom {ratio(mm.top, mm.bottom)}
        </p>
        <p>
          <label>
            <input type="checkbox" checked={contrast} onChange={(e) => setContrast(e.target.checked)} /> Raise contrast
          </label>
        </p>
      </div>
    </div>
  )
}
