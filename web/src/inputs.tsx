import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { parseMoney } from './api'

/**
 * A dollar amount that takes commas and a "$", which a number input refuses. Read it with
 * {@link parseMoney}; the browser's validation says what's wrong before the form is sent.
 */
export function MoneyInput({
  value,
  onChange,
  min = 0.01,
  ...rest
}: {
  value: string
  onChange: (value: string) => void
  min?: number
  'aria-label': string
  placeholder?: string
  className?: string
  required?: boolean
  id?: string
  'aria-describedby'?: string
  'aria-invalid'?: true
}) {
  function change(e: ChangeEvent<HTMLInputElement>) {
    const amount = parseMoney(e.target.value)
    e.target.setCustomValidity(
      e.target.value === '' || amount >= min ? '' : Number.isNaN(amount) ? 'Enter an amount like 1,234.50' : `At least $${min}`,
    )
    onChange(e.target.value)
  }
  return <input {...rest} type="text" inputMode="decimal" autoComplete="off" value={value} onChange={change} />
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
const WEEKDAYS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']

/** YYYY-MM-DD for a day counted in UTC, so no time zone shifts it. */
function iso(year: number, month: number, day: number) {
  return new Date(Date.UTC(year, month, day)).toISOString().slice(0, 10)
}

/** The month a calendar opens on: the chosen date's, else the latest allowed, else today's. */
function monthOf(value: string, max?: string) {
  const d = validDate(value) ? value : (max ?? new Date().toISOString().slice(0, 10))
  return { year: Number(d.slice(0, 4)), month: Number(d.slice(5, 7)) - 1 }
}

function validDate(text: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(text) && iso(Number(text.slice(0, 4)), Number(text.slice(5, 7)) - 1, Number(text.slice(8))) === text
}

/**
 * A date as YYYY-MM-DD, typed or picked from a month calendar. Replaces the native date input,
 * whose picker some browsers (Firefox) draw with weekends in red and can't be restyled.
 */
export function DateInput({
  value,
  onChange,
  max,
  required,
  'aria-label': label,
}: {
  value: string
  onChange: (value: string) => void
  /** Latest pickable date, YYYY-MM-DD. */
  max?: string
  required?: boolean
  'aria-label': string
}) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState(() => monthOf(value, max))
  const box = useRef<HTMLSpanElement>(null)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const el = input.current
    if (!el) return
    el.setCustomValidity(
      value === '' || (validDate(value) && (!max || value <= max))
        ? ''
        : validDate(value)
          ? `Pick a date on or before ${max}`
          : 'Enter a date as YYYY-MM-DD',
    )
  }, [value, max])

  useEffect(() => {
    if (!open) return
    const outside = (e: MouseEvent) => box.current && !box.current.contains(e.target as Node) && setOpen(false)
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  function toggle() {
    if (!open) setView(monthOf(value, max))
    setOpen(!open)
  }

  function step(by: number) {
    const d = new Date(Date.UTC(view.year, view.month + by, 1))
    setView({ year: d.getUTCFullYear(), month: d.getUTCMonth() })
  }

  const lead = new Date(Date.UTC(view.year, view.month, 1)).getUTCDay()
  const days = new Date(Date.UTC(view.year, view.month + 1, 0)).getUTCDate()
  const cells: (string | null)[] = [
    ...Array<null>(lead).fill(null),
    ...Array.from({ length: days }, (_, i) => iso(view.year, view.month, i + 1)),
  ]
  const weeks = Array.from({ length: Math.ceil(cells.length / 7) }, (_, w) => cells.slice(w * 7, w * 7 + 7))
  const nextAllowed = !max || iso(view.year, view.month + 1, 1) <= max

  return (
    <span className="date-input" ref={box}>
      <input
        ref={input}
        type="text"
        inputMode="numeric"
        placeholder="YYYY-MM-DD"
        autoComplete="off"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        required={required}
      />
      <button type="button" className="date-toggle" onClick={toggle} aria-label={`Choose ${label.toLowerCase()}`} aria-expanded={open}>
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <rect x="1.5" y="3" width="13" height="11.5" rx="1.5" fill="none" stroke="currentColor" />
          <path d="M1.5 6.5h13M5 1.5v3M11 1.5v3" stroke="currentColor" />
        </svg>
      </button>
      {open && (
        <div className="calendar" role="dialog" aria-label={label}>
          <div className="calendar-head">
            <button type="button" onClick={() => step(-1)} aria-label="Previous month">
              ‹
            </button>
            <span aria-live="polite">
              {MONTHS[view.month]} {view.year}
            </span>
            <button type="button" onClick={() => step(1)} aria-label="Next month" disabled={!nextAllowed}>
              ›
            </button>
          </div>
          <table>
            <thead>
              <tr>
                {WEEKDAYS.map((d) => (
                  <th key={d} scope="col">
                    {d}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {weeks.map((week, w) => (
                <tr key={w}>
                  {week.map((day, i) => (
                    <td key={i}>
                      {day && (
                        <button
                          type="button"
                          className={day === value ? 'day selected' : 'day'}
                          aria-pressed={day === value}
                          aria-label={day}
                          disabled={!!max && day > max}
                          onClick={() => {
                            onChange(day)
                            setOpen(false)
                          }}
                        >
                          {Number(day.slice(8))}
                        </button>
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </span>
  )
}
