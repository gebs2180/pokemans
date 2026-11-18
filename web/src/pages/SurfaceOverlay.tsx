import { useId, useState, type MouseEvent } from 'react'
import { SIDE_LABEL, type Side } from '../api'
import { safeHref } from '../safeHref'
import { Segmented, ToolPanel } from './reviewTools'
import './reviewTools.css'

export type SurfaceMode = 'off' | 'contrast' | 'edges' | 'emboss'

/** Who is looking: a reviewer on the admin page, or the collector on their own card. Only the wording differs. */
export type SurfaceAudience = 'reviewer' | 'collector'

const MODES: { mode: SurfaceMode; label: string; hint: Record<SurfaceAudience, string> }[] = [
  { mode: 'off', label: 'Photo', hint: { reviewer: '', collector: '' } },
  {
    mode: 'contrast',
    label: 'Contrast',
    hint: {
      reviewer: 'Grey and steep contrast: scratches and print lines on foil.',
      collector: 'Takes out the colour and turns up the contrast, so scratches and print lines on the holo show up.',
    },
  },
  {
    mode: 'edges',
    label: 'Edges',
    hint: {
      reviewer: 'Sharp changes stand out: whitening on edges and corners, dents.',
      collector: 'Makes sharp changes stand out, like whitening on the edges and corners, and dents.',
    },
  },
  {
    mode: 'emboss',
    label: 'Emboss',
    hint: {
      reviewer: 'Lit from one corner: indents, creases and roller lines.',
      collector: 'Lights the card from one corner, so dents, creases and roller lines cast a shadow.',
    },
  },
]

/** The zoom a tap gives: enough to see whitening, while an edge or corner tapped on stays in the box with its edge line. */
export const SURFACE_ZOOM = 2

/**
 * A photo the surface view can show: the wide crop (the card with a little background past each edge) when there is
 * one, else the card crop, else the photo itself.
 */
export type SurfacePhoto = { id: string; side: Side; url: string | null; cropUrl?: string | null; wideUrl?: string | null }

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
 * One crop with the overlay on it. A tap zooms it 2× at that spot, inside the same box, and the zoom follows
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
            ...(zoom ? { transformOrigin: `${zoom.x}% ${zoom.y}%`, transform: `scale(${SURFACE_ZOOM})` } : {}),
          }}
          data-mode={on ? mode : 'off'}
        />
      </button>
    </>
  )
}

/** What the panel says, for each audience. */
const WORDS: Record<SurfaceAudience, { title: string; summary: string; intro: string; image: string }> = {
  reviewer: {
    title: 'Surface view',
    summary: 'Contrast, edge and emboss filters over the crops, with zoom. Nothing is saved.',
    intro: 'Filters over the card crop, in your browser only. Tap a crop to zoom in on that spot.',
    image: 'crop',
  },
  collector: {
    title: 'Take a closer look',
    summary: 'Filters that bring out scratches, whitening and dents in your photos.',
    intro:
      "Tap a photo to zoom in on that spot, and tap again to zoom out. This only changes how the photo looks on your screen: it doesn't change your offer, and nothing is saved or sent.",
    image: 'photo',
  },
}

/**
 * The card photos with contrast, edge and emboss filters drawn over them, to bring out scratches, whitening and print
 * lines. Reviewers have it on the admin page; collectors on their own card, as a way to look their card over. A
 * viewing aid only: it changes no grade or price, and nothing is stored.
 */
export function SurfacePanel({ photos, audience = 'reviewer' }: { photos: SurfacePhoto[]; audience?: SurfaceAudience }) {
  const shown = photos.filter((p) => (p.side === 'FRONT' || p.side === 'BACK') && surfaceSrc(p) != null)
  if (shown.length === 0) return null
  const words = WORDS[audience]
  return (
    <ToolPanel title={words.title} summary={words.summary}>
      <SurfaceTool shown={shown} audience={audience} />
    </ToolPanel>
  )
}

function surfaceSrc(p: SurfacePhoto) {
  return safeHref(p.wideUrl) ?? safeHref(p.cropUrl) ?? safeHref(p.url)
}

function SurfaceTool({ shown, audience }: { shown: SurfacePhoto[]; audience: SurfaceAudience }) {
  const [mode, setMode] = useState<SurfaceMode>('off')
  const [percent, setPercent] = useState(60)
  const strengthId = useId()
  const hint = MODES.find((m) => m.mode === mode)?.hint[audience]
  const words = WORDS[audience]
  return (
    <>
      <p className="muted small">{words.intro}</p>
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
          const src = surfaceSrc(p)
          return src ? (
            <figure key={p.id}>
              <SurfaceView src={src} alt={`${SIDE_LABEL[p.side]} ${words.image}, surface view`} mode={mode} strength={percent / 100} />
              <figcaption className="small">{SIDE_LABEL[p.side]}</figcaption>
            </figure>
          ) : null
        })}
      </div>
    </>
  )
}
