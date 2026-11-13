import { useId, useState } from 'react'
import { SIDE_LABEL, type ReviewPhoto } from '../api'
import { safeHref } from '../safeHref'
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

/** One crop with the overlay on it. */
export function SurfaceView({ src, alt, mode, strength }: { src: string; alt: string; mode: SurfaceMode; strength: number }) {
  const id = `surface-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const on = mode !== 'off' && strength > 0
  return (
    <>
      {on && <SurfaceFilter id={id} mode={mode} strength={strength} />}
      <img className="surface-image" src={src} alt={alt} style={on ? { filter: `url(#${id})` } : undefined} data-mode={on ? mode : 'off'} />
    </>
  )
}

/**
 * Reviewer: the card crops with contrast, edge and emboss filters drawn over them, to bring out scratches,
 * whitening and print lines. A viewing aid only: it changes no grade and nothing is stored.
 */
export function SurfacePanel({ photos }: { photos: ReviewPhoto[] }) {
  const [mode, setMode] = useState<SurfaceMode>('off')
  const [percent, setPercent] = useState(60)
  const shown = photos.filter((p) => p.side === 'FRONT' || p.side === 'BACK')
  if (shown.length === 0) return null
  const hint = MODES.find((m) => m.mode === mode)?.hint
  return (
    <section className="panel">
      <h2>Surface view</h2>
      <p className="muted small">Filters over the card crop, in your browser only. Nothing is saved.</p>
      <div className="row surface-controls" role="group" aria-label="Surface filter">
        {MODES.map((m) => (
          <button key={m.mode} className={m.mode === mode ? undefined : 'link'} aria-pressed={m.mode === mode} onClick={() => setMode(m.mode)}>
            {m.label}
          </button>
        ))}
        <label className="small">
          Strength{' '}
          <input
            type="range"
            min={0}
            max={100}
            value={percent}
            disabled={mode === 'off'}
            onChange={(e) => setPercent(Number(e.target.value))}
            aria-label="Filter strength"
          />{' '}
          {percent}%
        </label>
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
    </section>
  )
}
