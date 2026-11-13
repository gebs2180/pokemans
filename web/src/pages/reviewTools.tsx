import { useState, type ReactNode } from 'react'
import type { FrameSide } from '../centering'
import './reviewTools.css'

/** One control for a pick-one choice: every option a button, the chosen one pressed. */
export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** The − and + for one line, big enough to tap. */
export function NudgeButtons({ side, onNudge }: { side: FrameSide; onNudge: (by: number) => void }) {
  return (
    <span className="nudge">
      <button type="button" className="secondary" aria-label={`Move ${side} out`} onClick={() => onNudge(-1)}>
        −
      </button>
      <button type="button" className="secondary" aria-label={`Move ${side} in`} onClick={() => onNudge(1)}>
        +
      </button>
    </span>
  )
}

/**
 * A reviewer's reading aid, closed until asked for so it doesn't push the card's own panels down. Its
 * contents only render once opened, so nothing loads for a panel nobody looks at.
 */
export function ToolPanel({ title, summary, children }: { title: string; summary: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <details className="panel tool-panel" open={open}>
      {/* Opened by hand rather than the browser's own toggle, so React's state and the element can't disagree. */}
      <summary
        onClick={(e) => {
          e.preventDefault()
          setOpen((o) => !o)
        }}
      >
        <h2>{title}</h2> <span className="muted small">{summary}</span>
      </summary>
      {open && children}
    </details>
  )
}
