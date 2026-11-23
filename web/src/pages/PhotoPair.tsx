import { useEffect, useRef, useState } from 'react'

/**
 * Two photos chosen at once: the first is taken as the front and the second as the back, with a swap in case they
 * came the other way round (review finding 13).
 */
export function PhotoPair({ files, onUse, onCancel }: { files: [File, File]; onUse: (front: File, back: File) => void; onCancel: () => void }) {
  const [swapped, setSwapped] = useState(false)
  const [front, back] = swapped ? [files[1], files[0]] : files
  return (
    <div className="photo-pair" role="group" aria-label="Front and back">
      <div className="row">
        <PairShot label="Front" file={front} />
        <PairShot label="Back" file={back} />
      </div>
      <div className="actions">
        <button type="button" onClick={() => onUse(front, back)}>
          Use these
        </button>
        <button type="button" className="secondary" onClick={() => setSwapped((s) => !s)}>
          Swap front and back
        </button>
        <button type="button" className="link" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  )
}

const PREVIEW_WIDTH = 120

function PairShot({ label, file }: { label: string; file: File }) {
  // Drawn on a canvas rather than through an object URL in an <img>, so nothing from the chosen file becomes markup.
  // Not every test DOM can decode a file: the name says which it is.
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    if (typeof createImageBitmap !== 'function') return
    let current = true
    createImageBitmap(file)
      .then((bitmap) => {
        const c = canvas.current
        if (current && c) {
          c.width = PREVIEW_WIDTH
          c.height = Math.round((PREVIEW_WIDTH * bitmap.height) / bitmap.width)
          c.getContext('2d')?.drawImage(bitmap, 0, 0, c.width, c.height)
        }
        bitmap.close()
      })
      .catch(() => {})
    return () => {
      current = false
    }
  }, [file])
  return (
    <figure className="pair-shot">
      <canvas ref={canvas} width={PREVIEW_WIDTH} height={0} role="img" aria-label={`${label}: ${file.name}`} />
      <figcaption>
        <strong>{label}</strong> <span className="muted small">{file.name}</span>
      </figcaption>
    </figure>
  )
}
