import { useId, useState, type MouseEvent } from 'react'
import { SIDE_LABEL, type ReviewPhoto } from '../api'
import { safeHref } from '../safeHref'
import { Segmented, ToolPanel } from './reviewTools'
import './reviewTools.css'

export type SurfaceMode = 'off' | 'contrast' | 'edges' | 'emboss'

const MODES: { mode: SurfaceMode; label: string; hint: string }[] = [
  { mode: 'off', label: 'Photo', hint: '' },
  { mode: 'contrast', label: 'Contrast', hint: 'Grey and steep contrast: scratches and print lines on foil.' },
  { mode: 'edges', label: 'Edges', hint: 'Sharp changes stand out: whitening on edges and corners, dents.' },
  { mode: 'emboss', label: 'Emboss', hint: 'Lit from one corner: indents, creases and roller lines.' },
]

/**
 * The SVG filter for a mode at a strength from 0 to 1, mixed over the photo. In the browser only: nothing
 * reads the pixels back (so a photo from another origin works) and nothing is stored or sent.
 */
function SurfaceFilter({ id, mode, strength }: { id: string; mode: SurfaceMode; strength: number }) {
  const slope = 1 + 6 * strength
  return (
    <svg width="0" height="0" aria-hidden="true" className="surface-defs">
      <filter id={id} colorInterpolationFilters="sRGB">
        {mode === 'contrast' && (
          <>
            <feColorMatrix type="saturate" values={String(1 - strength)} />
            <feComponentTransfer>
              <feFuncR type="linear" slope={slope} intercept={(1 - slope) / 2} />
              <feFuncG type="linear" slope={slope} intercept={(1 - slope) / 2} />
              <feFuncB type="linear" slope={slope} intercept={(1 - slope) / 2} />
            </feComponentTransfer>
          </>
        )}
        {mode === 'edges' && (
          <>
            <feColorMatrix in="SourceGraphic" type="saturate" values="0" result="grey" />
            <feConvolveMatrix in="grey" order="3" kernelMatrix="-1 -1 -1 -1 8 -1 -1 -1 -1" preserveAlpha="true" result="edges" />
            <feComposite in="SourceGraphic" in2="edges" operator="arithmetic" k1="0" k2={1 - strength} k3={3 * strength} k4="0" />
          </>
        )}
        {mode === 'emboss' && (
          <>
            <feColorMatrix in="SourceGraphic" type="saturate" values="0" result="grey" />
            <feConvolveMatrix in="grey" order="3" kernelMatrix="-2 -1 0 -1 1 1 0 1 2" preserveAlpha="true" result="emboss" />
            <feComposite in="SourceGraphic" in2="emboss" operator="arithmetic" k1="0" k2={1 - strength} k3={strength} k4="0" />
          </>
        )}
      </filter>
    </svg>
  )
}

/**
 * One crop with the overlay on it. A tap zooms it 3× at that spot, inside the same box, and the zoom follows
 * the pointer; another tap zooms back out. From the keyboard it zooms on the middle.
 */
export function SurfaceView({ src, alt, mode, strength }: { src: string; alt: string; mode: SurfaceMode; strength: number }) {
  const id = `surface-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const on = mode !== 'off' && strength > 0
  // Where the zoom is centred, as percentages of the crop; null when not zoomed.
  const [zoom, setZoom] = useState<{ x: number; y: number } | null>(null)

  function at(e: MouseEvent<HTMLElement>) {
    const box = e.currentTarget.getBoundingClientRect()
    if (box.width === 0 || box.height === 0) return { x: 50, y: 50 }
    const clamp = (v: number) => Math.min(100, Math.max(0, v))
    return { x: clamp(((e.clientX - box.left) / box.width) * 100), y: clamp(((e.clientY - box.top) / box.height) * 100) }
  }

  return (
    <>
      {on && <SurfaceFilter id={id} mode={mode} strength={strength} />}
      <button
        type="button"
        className="surface-zoom"
        aria-pressed={zoom != null}
        aria-label={zoom ? `Zoom out of ${alt}` : `Zoom in on ${alt}`}
        onClick={(e) => {
          if (zoom) return setZoom(null)
          // A click from the keyboard has no pointer position: zoom on the middle.
          setZoom(e.detail === 0 ? { x: 50, y: 50 } : at(e))
        }}
        onPointerMove={(e) => zoom && setZoom(at(e))}
      >
        <img
          className="surface-image"
          src={src}
          alt={alt}
          style={{
            ...(on ? { filter: `url(#${id})` } : {}),
            ...(zoom ? { transformOrigin: `${zoom.x}% ${zoom.y}%` } : {}),
          }}
          data-mode={on ? mode : 'off'}
        />
      </button>
    </>
  )
}

/**
 * Reviewer: the card crops with contrast, edge and emboss filters drawn over them, to bring out scratches,
 * whitening and print lines. A viewing aid only: it changes no grade and nothing is stored.
 */
export function SurfacePanel({ photos }: { photos: ReviewPhoto[] }) {
  const shown = photos.filter((p) => p.side === 'FRONT' || p.side === 'BACK')
  if (shown.length === 0) return null
  return (
    <ToolPanel title="Surface view" summary="Contrast, edge and emboss filters over the crops, with zoom. Nothing is saved.">
      <SurfaceTool shown={shown} />
    </ToolPanel>
  )
}

function SurfaceTool({ shown }: { shown: ReviewPhoto[] }) {
  const [mode, setMode] = useState<SurfaceMode>('off')
  const [percent, setPercent] = useState(60)
  const strengthId = useId()
  const hint = MODES.find((m) => m.mode === mode)?.hint
  return (
    <>
      <p className="muted small">Filters over the card crop, in your browser only. Tap a crop to zoom in on that spot.</p>
      <div className="surface-controls">
        <Segmented label="Surface filter" options={MODES.map((m) => ({ value: m.mode, label: m.label }))} value={mode} onChange={setMode} />
        <span className="surface-strength small">
          <label htmlFor={strengthId}>Strength</label>
          <input
            id={strengthId}
            type="range"
            min={0}
            max={100}
            value={percent}
            disabled={mode === 'off'}
            onChange={(e) => setPercent(Number(e.target.value))}
            aria-label="Filter strength"
          />
          <output htmlFor={strengthId}>{percent}%</output>
        </span>
      </div>
      {hint && <p className="muted small">{hint}</p>}
      <div className="surface-grid">
        {shown.map((p) => {
          const src = safeHref(p.cropUrl) ?? safeHref(p.url)
          return src ? (
            <figure key={p.id}>
              <SurfaceView src={src} alt={`${SIDE_LABEL[p.side]} crop, surface view`} mode={mode} strength={percent / 100} />
              <figcaption className="small">{SIDE_LABEL[p.side]}</figcaption>
            </figure>
          ) : null
        })}
      </div>
    </>
  )
}
