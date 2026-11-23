import { useEffect, useMemo, useState } from 'react'

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

function PairShot({ label, file }: { label: string; file: File }) {
  // Not every test DOM can preview a file: the name says which it is.
  const url = useMemo(() => (typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null), [file])
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url)
  }, [url])
  return (
    <figure className="pair-shot">
      {url && <img src={url} alt={`${label}: ${file.name}`} width={120} />}
      <figcaption>
        <strong>{label}</strong> <span className="muted small">{file.name}</span>
      </figcaption>
    </figure>
  )
}
