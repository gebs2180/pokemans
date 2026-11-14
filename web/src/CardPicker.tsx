import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { apiPath, useApi, type CatalogPick, type CatalogPickDetail, type PriceHint } from './api'
import { EDITION_LABEL, LANGUAGES, metaLine, VARIANT_LABEL, type PickedCard } from './cardPick'
import { message } from './format'
import { dollars } from './policy'
import './cardPicker.css'

function tooShort(q: string) {
  return q.replace(/[^\p{L}]/gu, '').length < 2
}

/**
 * The card's catalog image, or an empty box when TCGdex has none or it fails to load. Not lazy: lazy
 * images inside the results' scroll box were sometimes never fetched, and there are at most ten.
 */
function Thumb({ card }: { card: CatalogPick }) {
  const src = card.imageUrl ? `${card.imageUrl}/low.webp` : null
  const [failed, setFailed] = useState<string | null>(null)
  return src && failed !== src ? (
    <img className="picker-thumb" src={src} alt="" onError={() => setFailed(src)} />
  ) : (
    <span className="picker-thumb picker-noimg" aria-hidden="true" />
  )
}

/**
 * Search-as-you-type over the card catalog, then the prints the catalog knows the card came in. The
 * pick is only a proposal: the admin still confirms the card's identity from its photos.
 *
 * Photo-first suggestions ("Is this your card?") would feed the same list from a reading of the
 * photos instead of typed words, and end in the same {@link PickedCard}.
 *
 * @param inputId    the search box's id, for the form's own label
 * @param onChange   the current pick, or null while there's none
 * @param onDescribe the collector can't find their card and will describe it instead, starting from what they typed
 */
export function CardPicker({
  inputId,
  describedBy,
  onChange,
  onDescribe,
}: {
  inputId: string
  describedBy?: string
  onChange: (pick: PickedCard | null) => void
  onDescribe: (typed: string) => void
}) {
  const api = useApi()
  const listId = useId()
  const [language, setLanguage] = useState('en')
  const [query, setQuery] = useState('')
  // The last answer; a box cleared below two letters shows none.
  const [found, setFound] = useState<CatalogPick[] | null>(null)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [picked, setPicked] = useState<PickedCard | null>(null)
  const [hint, setHint] = useState<{ key: string; hint: PriceHint } | null>(null)
  const seq = useRef(0)
  const input = useRef<HTMLInputElement>(null)

  // Search a moment after typing stops; a late answer to an older search is dropped.
  useEffect(() => {
    if (picked) return
    const q = query.trim()
    const mine = ++seq.current
    if (tooShort(q)) return
    const timer = window.setTimeout(() => {
      setSearching(true)
      api<CatalogPick[]>('GET', apiPath`/api/catalog/search?q=${q}&language=${language}`)
        .then((cards) => {
          if (seq.current !== mine) return
          setFound(cards)
          setActive(-1)
          setError(null)
          setOpen(true)
        })
        .catch((e: unknown) => {
          if (seq.current === mine) setError(message(e))
        })
        .finally(() => {
          if (seq.current === mine) setSearching(false)
        })
    }, 250)
    return () => window.clearTimeout(timer)
  }, [api, query, language, picked])

  // The minimum, early: only once the print is known, and only ever as a hint.
  const edition = picked?.edition
  const variant = picked?.variant
  const externalId = picked?.card.externalId
  const pickedLanguage = picked?.card.language
  const stamp = picked?.stamp ?? ''
  const hintKey = externalId && pickedLanguage && edition && variant ? [externalId, pickedLanguage, edition, variant, stamp].join('|') : null
  useEffect(() => {
    if (!hintKey || !externalId || !pickedLanguage || !edition || !variant) return
    let cancelled = false
    api<PriceHint>('GET', apiPath`/api/catalog/price-hint?externalId=${externalId}&language=${pickedLanguage}&edition=${edition}&variant=${variant}&stamp=${stamp}`)
      .then((h) => {
        if (!cancelled) setHint({ key: hintKey, hint: h })
      })
      .catch(() => {
        // No hint: the card can be added as before.
      })
    return () => {
      cancelled = true
    }
  }, [api, hintKey, externalId, pickedLanguage, edition, variant, stamp])
  const underMinimum = hint != null && hint.key === hintKey && hint.hint.underMinimum ? hint.hint.minFmv : null
  const results = tooShort(query.trim()) ? null : found

  function update(next: PickedCard | null) {
    setPicked(next)
    onChange(next)
  }

  async function choose(hit: CatalogPick) {
    setOpen(false)
    setError(null)
    try {
      const card = await api<CatalogPickDetail>('GET', apiPath`/api/catalog/cards/${hit.externalId}?language=${hit.language}`)
      // Where the catalog lists one print, that's the card's; where it lists more, the collector says (or doesn't).
      update({
        card,
        edition: card.editions.length === 1 ? card.editions[0] : null,
        variant: card.variants.length === 1 ? card.variants[0] : null,
        stamp: null,
        stampUnsure: card.stamps.length > 0,
      })
    } catch (e) {
      setError(message(e))
    }
  }

  function change() {
    update(null)
    setOpen(results != null)
    window.setTimeout(() => input.current?.focus())
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    const n = results?.length ?? 0
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (!open) setOpen(true)
      setActive((i) => (n === 0 ? -1 : (i + 1) % n))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) setOpen(true)
      setActive((i) => (n === 0 ? -1 : i <= 0 ? n - 1 : i - 1))
    } else if (e.key === 'Enter' && open && results && active >= 0) {
      e.preventDefault()
      void choose(results[active])
    } else if (e.key === 'Escape' && open) {
      e.preventDefault()
      setOpen(false)
      setActive(-1)
    }
  }

  if (picked) {
    const c = picked.card
    return (
      <div className="picker-picked">
        <div className="picker-card">
          <Thumb card={c} />
          <div>
            <strong>{c.name}</strong>
            <div className="muted small">{metaLine(c)}</div>
            {c.promo && <div className="muted small">Promo</div>}
          </div>
          <button type="button" className="link" onClick={change}>
            Change
          </button>
        </div>
        {c.editions.length > 1 && (
          <PrintChoice
            legend="Edition"
            options={c.editions.map((v) => ({ value: v, label: EDITION_LABEL[v] }))}
            value={picked.edition}
            onChange={(v) => update({ ...picked, edition: v })}
          />
        )}
        {c.variants.length > 1 && (
          <PrintChoice
            legend="Finish"
            options={c.variants.map((v) => ({ value: v, label: VARIANT_LABEL[v] }))}
            value={picked.variant}
            onChange={(v) => update({ ...picked, variant: v })}
          />
        )}
        {c.stamps.length > 0 && (
          <PrintChoice
            legend="Stamp"
            options={[{ value: '', label: 'No stamp' }, ...c.stamps.map((s) => ({ value: s, label: s }))]}
            value={picked.stampUnsure ? null : (picked.stamp ?? '')}
            onChange={(v) => update({ ...picked, stamp: v || null, stampUnsure: v == null })}
          />
        )}
        {(picked.edition == null && c.editions.length > 1) || (picked.variant == null && c.variants.length > 1) || picked.stampUnsure ? (
          <p className="muted small">Not sure is fine: we'll check from your photos.</p>
        ) : null}
        {underMinimum != null && (
          <p className="alert alert-warning" role="status">
            This card usually sells for under our {dollars(underMinimum)} minimum, whatever its condition, so it's unlikely to get an
            offer. You can still add it.
          </p>
        )}
      </div>
    )
  }

  const showList = open && results != null && results.length > 0
  return (
    <div className="picker">
      <div className="picker-search">
        <select value={language} onChange={(e) => setLanguage(e.target.value)} aria-label="Card language">
          {Object.entries(LANGUAGES).map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>
        <input
          ref={input}
          id={inputId}
          aria-describedby={describedBy}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          autoComplete="off"
          spellCheck={false}
          value={query}
          placeholder="Name and number, e.g. Charizard 4/102"
          maxLength={100}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
          }}
          onKeyDown={onKeyDown}
          onBlur={() => setOpen(false)}
          onFocus={() => results && setOpen(true)}
        />
      </div>
      <ul id={listId} role="listbox" aria-label="Matching cards" className="picker-list" hidden={!showList}>
        {showList &&
          results.map((r, i) => (
            <li
              key={`${r.externalId}:${r.language}`}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              className="picker-option"
              // Keep focus in the box, so the click isn't lost to its blur.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => void choose(r)}
              onMouseMove={() => setActive(i)}
            >
              <Thumb card={r} />
              <span>
                <strong>{r.name}</strong>
                <span className="picker-meta">{metaLine(r)}</span>
              </span>
            </li>
          ))}
      </ul>
      <p className="visually-hidden" role="status" aria-live="polite">
        {searching ? '' : results == null ? '' : results.length === 0 ? 'No matching cards' : `${results.length} matching card${results.length === 1 ? '' : 's'}, use the arrow keys to choose`}
      </p>
      {results != null && results.length === 0 && !searching && <p className="muted small">No matching cards. Check the spelling, or describe it below.</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <p className="small">
        <button type="button" className="link" onClick={() => onDescribe(query.trim())}>
          Can't find it? Describe it
        </button>
      </p>
    </div>
  )
}

/** A pick-one print choice, with "Not sure" for a collector who can't tell. */
function PrintChoice<T extends string>({
  legend,
  options,
  value,
  onChange,
}: {
  legend: string
  options: { value: T; label: string }[]
  value: T | null
  onChange: (value: T | null) => void
}) {
  const name = useId()
  return (
    <fieldset className="picker-choice">
      <legend className="field-label">{legend}</legend>
      <div className="choices">
        {[...options, { value: null, label: 'Not sure' }].map((o) => (
          <label key={o.value ?? '?'} className="choice">
            <input type="radio" name={name} checked={value === o.value} onChange={() => onChange(o.value)} />
            {o.label}
          </label>
        ))}
      </div>
    </fieldset>
  )
}
