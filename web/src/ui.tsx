import { useId, type ReactNode } from 'react'
import { STAGE_LABEL, type CardStage } from './api'

/** The colour a chip, alert or message takes. Each maps to a token pair in index.css. */
export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export function Chip({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`chip chip-${tone}`}>{children}</span>
}

/** How far along each stage is: waiting on us is neutral, a step for the seller is a warning, money is a success. */
const STAGE_TONE: Record<CardStage, Tone> = {
  PHOTOS: 'warning',
  LOT: 'neutral',
  IDENTIFYING: 'neutral',
  ASSESSING: 'neutral',
  PRICING: 'neutral',
  NO_OFFER: 'warning',
  READY: 'info',
  OFFERED: 'info',
  LISTED: 'info',
  SOLD: 'success',
}

/** Where a seller's card is on the way to a sale. */
export function StageChip({ stage }: { stage: CardStage }) {
  return <Chip tone={STAGE_TONE[stage]}>{STAGE_LABEL[stage]}</Chip>
}

/** A page or panel still loading. Not a live region: pages already announce what they load. */
export function Loading({ children = 'Loading…' }: { children?: ReactNode }) {
  return <p className="loading">{children}</p>
}

/** Nothing to show yet, and what to do about it. */
export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children}
    </div>
  )
}

/**
 * A labelled form control with optional hint and error text, both linked to the control with aria-describedby.
 * The control is a render prop, so it keeps its own props (and accessible name: give it the same aria-label as the
 * visible label, or none).
 */
export function Field({
  label,
  hint,
  error,
  className,
  children,
}: {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  className?: string
  children: (control: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: true }) => ReactNode
}) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={className ? `field ${className}` : 'field'}>
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, ...(error ? { 'aria-invalid': true as const } : {}) })}
      {hint && (
        <p id={hintId} className="field-hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
    </div>
  )
}
