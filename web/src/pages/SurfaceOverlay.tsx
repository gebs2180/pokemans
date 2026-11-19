import { useId, useState, type MouseEvent, type ReactNode } from 'react'
import { SIDE_LABEL, type DefectMarkView, type Side } from '../api'
import { markWords } from './defectWords'
import { safeHref } from '../safeHref'
import { Segmented, ToolPanel } from './reviewTools'
import './reviewTools.css'

/** 'detail' is the server's local-contrast image; the others are filters drawn in the browser. */
export type SurfaceMode = 'off' | 'contrast' | 'edges' | 'emboss' | 'detail'

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
    mode: 'detail',
    label: 'Detail',
    hint: {
      reviewer: 'Local contrast raised on the server, from the full-resolution crop: scratches and print lines on holo foil without washing it out.',
      collector: 'Brings out the fine detail across the whole card, scratches and print lines on the holo included, without blowing out the shine.',
    },
  },
  {
    mode: 'emboss',
    label: 'Emboss',
    hint: {
      reviewer: 'Flat grey relief lit from one corner: indents, creases and roller lines. Printed edges stand out too, so compare with the photo.',
      collector: 'Turns the card grey and lights it from one corner, so dents, creases and roller lines cast a shadow. The printing stands out too.',
    },
  },
]

/** The zoom a tap gives: enough to see whitening, while an edge or corner tapped on stays in the box with its edge line. */
export const SURFACE_ZOOM = 2

/**
 * A photo the surface view can show: the wide crop (the card with a little background past each edge) when there is
 * one, else the card crop, else the photo itself.
 */
export type SurfacePhoto = {
  id: string
  side: Side
  url: string | null
  cropUrl?: string | null
  wideUrl?: string | null
  /** The wide crop at full resolution, when one was kept: preferred over the wide crop. */
  surfaceUrl?: string | null
  /** That crop with its local contrast raised on the server: what the Detail mode shows. */
  detailUrl?: string | null
}

/**
 * A relief: mid-grey wherever the card is flat, lit from the top left wherever the brightness changes, so the
 * photo's own colours and darks drop out and only edges, dents, creases and print lines keep any shading. It is
 * 0.5 + gain · (lit − shadowed), with the diagonal gradient split into its positive half (lit) and negative half
 * (shadowed), because filter results are clamped to 0..1 between steps. The shadowed half goes in inverted,
 * 1 − shadowed, so the sum's alpha (0.5 + gain) stays at 1 for any gain from 0.5 up. A light blur first keeps
 * sensor noise from turning into grain.
 */
function Relief({ gain }: { gain: number }) {
  return (
    <>
      <feColorMatrix in="SourceGraphic" type="saturate" values="0" result="grey" />
      <feGaussianBlur in="grey" stdDeviation="0.6" result="soft" />
      <feConvolveMatrix in="soft" order="3" kernelMatrix="-1 -1 0 -1 0 1 0 1 1" divisor="3" preserveAlpha="true" result="lit" />
      <feConvolveMatrix in="soft" order="3" kernelMatrix="1 1 0 1 0 -1 0 -1 -1" divisor="3" preserveAlpha="true" result="shadowed" />
      <feComponentTransfer in="shadowed" result="unshadowed">
        <feFuncR type="linear" slope="-1" intercept="1" />
        <feFuncG type="linear" slope="-1" intercept="1" />
        <feFuncB type="linear" slope="-1" intercept="1" />
      </feComponentTransfer>
      <feComposite in="lit" in2="unshadowed" operator="arithmetic" k1="0" k2={gain} k3={gain} k4={0.5 - gain} />
    </>
  )
}

/** Where a tap on the crop landed, and which photo. */
export type Placement = { photoId: string; x: number; y: number }

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
        {mode === 'emboss' && <Relief gain={0.5 + 1.5 * strength} />}
      </filter>
    </svg>
  )
}

/**
 * One crop with the overlay on it. A tap zooms it 2× at that spot, inside the same box, and the zoom follows
 * the pointer; another tap zooms back out. From the keyboard it zooms on the middle.
 */
export function SurfaceView({
  src,
  alt,
  mode,
  strength,
  marks = [],
  pending,
  onPlace,
}: {
  src: string
  alt: string
  mode: SurfaceMode
  strength: number
  /** Defects to draw on the crop, numbered as given. */
  marks?: { n: number; x: number; y: number; label: string }[]
  /** A spot picked but not yet saved. */
  pending?: { x: number; y: number } | null
  /** When set, a tap picks a spot (as shares of the crop) instead of zooming. */
  onPlace?: (x: number, y: number) => void
}) {
  const id = `surface-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const on = (mode === 'contrast' || mode === 'edges' || mode === 'emboss') && strength > 0
  // Where the zoom is centred, as percentages of the crop; null when not zoomed.
  const [zoomed, setZoom] = useState<{ x: number; y: number } | null>(null)
  // Placing a mark works on the whole crop: never while zoomed.
  const zoom = onPlace ? null : zoomed
  const stage = zoom ? { transformOrigin: `${zoom.x}% ${zoom.y}%`, transform: `scale(${SURFACE_ZOOM})` } : {}

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
        aria-label={onPlace ? `Mark a defect on ${alt}` : zoom ? `Zoom out of ${alt}` : `Zoom in on ${alt}`}
        data-placing={onPlace ? 'true' : undefined}
        onClick={(e) => {
          if (onPlace) {
            const spot = e.detail === 0 ? { x: 50, y: 50 } : at(e)
            return onPlace(spot.x / 100, spot.y / 100)
          }
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
            ...stage,
          }}
          data-mode={on || mode === 'detail' ? mode : 'off'}
        />
        {(marks.length > 0 || pending) && (
          <span className="surface-marks" style={stage} aria-hidden="true">
            {marks.map((m) => (
              <span key={m.n} className="surface-mark" style={{ left: `${m.x * 100}%`, top: `${m.y * 100}%` }} title={m.label}>
                {m.n}
              </span>
            ))}
            {pending && <span className="surface-mark pending" style={{ left: `${pending.x * 100}%`, top: `${pending.y * 100}%` }} />}
          </span>
        )}
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
export function SurfacePanel({
  photos,
  audience = 'reviewer',
  marks = [],
  pending = null,
  onPlace,
  children,
}: {
  photos: SurfacePhoto[]
  audience?: SurfaceAudience
  /** Defects a reviewer marked: drawn on their photo, numbered in this order. */
  marks?: DefectMarkView[]
  /** A spot picked and not yet saved. */
  pending?: Placement | null
  /** Reviewer only, while marking: a tap on a crop picks a spot instead of zooming. */
  onPlace?: (p: Placement) => void
  /** Under the images: the marks list, or the form for a new one. */
  children?: ReactNode
}) {
  const shown = photos.filter((p) => (p.side === 'FRONT' || p.side === 'BACK') && surfaceSrc(p) != null)
  if (shown.length === 0) return null
  const words = WORDS[audience]
  return (
    <ToolPanel title={words.title} summary={words.summary}>
      <SurfaceTool shown={shown} audience={audience} marks={marks} pending={pending} onPlace={onPlace}>
        {children}
      </SurfaceTool>
    </ToolPanel>
  )
}

/** The most detailed image there is for the mode: the server's Detail image, else the full-resolution crop, the wide crop, the card crop, the photo. */
function surfaceSrc(p: SurfacePhoto, mode: SurfaceMode = 'off') {
  return (
    (mode === 'detail' ? safeHref(p.detailUrl) : null) ??
    safeHref(p.surfaceUrl) ??
    safeHref(p.wideUrl) ??
    safeHref(p.cropUrl) ??
    safeHref(p.url)
  )
}

function SurfaceTool({
  shown,
  audience,
  marks,
  pending,
  onPlace,
  children,
}: {
  shown: SurfacePhoto[]
  audience: SurfaceAudience
  marks: DefectMarkView[]
  pending: Placement | null
  onPlace?: (p: Placement) => void
  children?: ReactNode
}) {
  const [mode, setMode] = useState<SurfaceMode>('off')
  const [percent, setPercent] = useState(60)
  const strengthId = useId()
  // Detail needs the server's image: offered only where some photo has one.
  const modes = MODES.filter((m) => m.mode !== 'detail' || shown.some((p) => safeHref(p.detailUrl)))
  const hint = modes.find((m) => m.mode === mode)?.hint[audience]
  const words = WORDS[audience]
  return (
    <>
      <p className="muted small">{words.intro}</p>
      <div className="surface-controls">
        <Segmented label="Surface filter" options={modes.map((m) => ({ value: m.mode, label: m.label }))} value={mode} onChange={setMode} />
        <span className="surface-strength small">
          <label htmlFor={strengthId}>Strength</label>
          <input
            id={strengthId}
            type="range"
            min={0}
            max={100}
            value={percent}
            disabled={mode === 'off' || mode === 'detail'}
            onChange={(e) => setPercent(Number(e.target.value))}
            aria-label="Filter strength"
          />
          <output htmlFor={strengthId}>{percent}%</output>
        </span>
      </div>
      {hint && <p className="muted small">{hint}</p>}
      <div className="surface-grid">
        {shown.map((p) => {
          const src = surfaceSrc(p, mode)
          const mine = marks.filter((m) => m.photoId === p.id)
          return src ? (
            <figure key={p.id}>
              <SurfaceView
                src={src}
                alt={`${SIDE_LABEL[p.side]} ${words.image}, surface view`}
                mode={mode}
                strength={percent / 100}
                marks={mine.map((m) => ({ n: marks.indexOf(m) + 1, x: m.x, y: m.y, label: markWords(m) }))}
                pending={pending?.photoId === p.id ? pending : null}
                onPlace={onPlace ? (x, y) => onPlace({ photoId: p.id, x, y }) : undefined}
              />
              <figcaption className="small">{SIDE_LABEL[p.side]}</figcaption>
            </figure>
          ) : null
        })}
      </div>
      {children}
    </>
  )
}
