import { useId, useState, type FormEvent } from 'react'
import {
  apiPath,
  useApi,
  type Edition,
  type IdentifyView,
  type IdentifyWhy,
  type IdentityProposal,
  type PriceHint,
  type SuggestedCard,
  type Variant,
} from '../api'
import { CardPicker, PrintChoice, Thumb } from '../CardPicker'
import { EDITION_LABEL, metaLine, VARIANT_LABEL, type PickedCard } from '../cardPick'
import { message } from '../format'
import { dollars } from '../policy'
import { Loading } from '../ui'
import { useLoad, usePoll } from '../useLoad'

/** Why the collector searches, in their words. Which case gets the search is the server's IdentifyPolicy. */
const WHY_TEXT: Record<IdentifyWhy, string> = {
  READER_OFF: 'Find your card in the catalog.',
  NOT_READ: "We couldn't read your card from the photos. Find it in the catalog instead.",
  READING_SLOW: "Reading your photos is taking longer than usual. Find your card in the catalog instead.",
  READING_FAILED: "We couldn't read your card from the photos. Find it in the catalog instead.",
  NO_MATCH: "We read your photos but couldn't match the card. Find it in the catalog instead.",
}

/** The collector's answer, as stored by the server. */
type Answer = {
  source: 'SUGGESTION' | 'SEARCH' | 'DESCRIBED'
  suggestionId?: string
  externalId?: string
  language?: string
  edition?: Edition | null
  variant?: Variant | null
  stamp?: string | null
  stampUnsure?: boolean
  description?: string
}

/**
 * Says what the card is, for a card added photos first: the reader's "Is this your card?" once front
 * and back pass, or the catalog search when it can't help. Every answer is a proposal: the admin still
 * confirms the card's identity from its photos, and nothing is priced from it.
 *
 * @param photoVersion bumped after each upload, so a finished capture starts the reading
 * @param onAnswer     the collector answered: the card's name changes
 */
export function IdentifyPanel({
  orgId,
  cardId,
  photoVersion,
  onAnswer,
}: {
  orgId: string
  cardId: string
  photoVersion: number
  onAnswer: () => void
}) {
  const api = useApi()
  // Changing an earlier answer: show the suggestions or the search again.
  const [again, setAgain] = useState(false)
  // "Not my card": search instead of the suggestions. Not stored: the answer the collector gives is.
  const [searching, setSearching] = useState(false)
  const { data, error, reload } = useLoad(
    () => api<IdentifyView>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}/identify?again=${String(again)}`),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [api, orgId, cardId, again, photoVersion],
  )
  usePoll(data?.step === 'READING', reload, 3000)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  async function answer(a: Answer) {
    setSaving(true)
    setSaveError(null)
    try {
      await api<IdentityProposal>('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/proposed-identity`, a)
      setSearching(false)
      if (again) setAgain(false)
      else reload()
      onAnswer()
    } catch (e) {
      setSaveError(message(e))
    } finally {
      setSaving(false)
    }
  }

  if (error) {
    return (
      <section className="panel" aria-label="Your card">
        <p className="error" role="alert">
          Couldn't load this step: {error}
        </p>
      </section>
    )
  }
  if (!data || data.step === 'IDENTIFIED') return null

  let body
  if (data.step === 'PHOTOS') {
    body = <p className="muted">Take the front and back below: we'll work out which card it is from them.</p>
  } else if (data.step === 'READING') {
    body = <Loading>Reading your card from the photos…</Loading>
  } else if (data.step === 'PROPOSED' && data.proposal) {
    body = <Proposed proposal={data.proposal} onChange={() => setAgain(true)} />
  } else if (data.step === 'SUGGEST' && !searching) {
    body = <Suggestions suggestions={data.suggestions} saving={saving} onConfirm={answer} onNotMine={() => setSearching(true)} />
  } else {
    body = (
      <Search
        why={searching ? null : data.why}
        initialQuery={data.readQuery ?? ''}
        initialLanguage={data.readLanguage}
        saving={saving}
        onAnswer={answer}
        onBack={searching ? () => setSearching(false) : undefined}
      />
    )
  }
  return (
    <section className="panel identify-panel" aria-label="Your card">
      {data.step !== 'PROPOSED' && <h2>Which card is it?</h2>}
      {body}
      {saveError && (
        <p className="error" role="alert">
          {saveError}
        </p>
      )}
      {again && (
        <p className="small">
          <button type="button" className="link" onClick={() => setAgain(false)}>
            Keep my earlier answer
          </button>
        </p>
      )}
    </section>
  )
}

/** "Is this your card?": the best suggestion, the close ones under it, and "Not my card". */
function Suggestions({
  suggestions,
  saving,
  onConfirm,
  onNotMine,
}: {
  suggestions: SuggestedCard[]
  saving: boolean
  onConfirm: (a: Answer) => void
  onNotMine: () => void
}) {
  const [chosen, setChosen] = useState(0)
  const s = suggestions[Math.min(chosen, suggestions.length - 1)]
  const c = s.card
  // Where the catalog lists one print, that's the card's; where it lists more, the collector says (or doesn't).
  // What the reader saw is only a starting choice: the collector sees it and can change it.
  const start = (opts: string[], seen: string | null) => (opts.length === 1 ? opts[0] : seen && opts.includes(seen) ? seen : null)
  const [prints, setPrints] = useState<Record<string, { edition: Edition | null; variant: Variant | null; stamp: string | null; stampUnsure: boolean }>>({})
  const p = prints[s.suggestionId] ?? {
    edition: start(c.editions, s.edition) as Edition | null,
    variant: start(c.variants, s.variant) as Variant | null,
    stamp: null,
    stampUnsure: c.stamps.length > 0,
  }
  const set = (next: Partial<typeof p>) => setPrints({ ...prints, [s.suggestionId]: { ...p, ...next } })
  const others = suggestions.filter((o) => o !== s)

  return (
    <div className="suggestions">
      <p className="lead">Is this your card?</p>
      <div className="picker-card suggestion-top">
        <SuggestionImage card={c} />
        <div>
          <strong>{c.name}</strong>
          <div className="muted small">{metaLine(c)}</div>
          {c.promo && <div className="muted small">Promo</div>}
        </div>
      </div>
      {c.editions.length > 1 && (
        <PrintChoice
          legend="Edition"
          options={c.editions.map((v) => ({ value: v, label: EDITION_LABEL[v] }))}
          value={p.edition}
          onChange={(v) => set({ edition: v })}
        />
      )}
      {c.variants.length > 1 && (
        <PrintChoice
          legend="Finish"
          options={c.variants.map((v) => ({ value: v, label: VARIANT_LABEL[v] }))}
          value={p.variant}
          onChange={(v) => set({ variant: v })}
        />
      )}
      {c.stamps.length > 0 && (
        <PrintChoice
          legend="Stamp"
          options={[{ value: '', label: 'No stamp' }, ...c.stamps.map((v) => ({ value: v, label: v }))]}
          value={p.stampUnsure ? null : (p.stamp ?? '')}
          onChange={(v) => set({ stamp: v || null, stampUnsure: v == null })}
        />
      )}
      <div className="actions">
        <button
          type="button"
          disabled={saving}
          aria-busy={saving}
          onClick={() =>
            onConfirm({
              source: 'SUGGESTION',
              suggestionId: s.suggestionId,
              edition: p.edition,
              variant: p.variant,
              stamp: p.stamp,
              stampUnsure: p.stampUnsure,
            })
          }
        >
          Yes, this is my card
        </button>
        <button type="button" className="secondary" onClick={onNotMine}>
          Not my card
        </button>
      </div>
      {others.length > 0 && (
        <>
          <p className="small muted">Or is it one of these?</p>
          <ul className="suggestion-others">
            {others.map((o) => (
              <li key={o.suggestionId}>
                <button type="button" className="picker-option" onClick={() => setChosen(suggestions.indexOf(o))}>
                  <Thumb card={o.card} />
                  <span>
                    <strong>{o.card.name}</strong>
                    <span className="picker-meta">{metaLine(o.card)}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/** The catalog's image of the suggested card, large enough to compare with the card in hand. */
function SuggestionImage({ card }: { card: SuggestedCard['card'] }) {
  const src = card.imageUrl ? `${card.imageUrl}/low.webp` : null
  const [failed, setFailed] = useState<string | null>(null)
  return src && failed !== src ? (
    <img className="suggestion-image" src={src} alt={`${card.name}, from the catalog`} onError={() => setFailed(src)} />
  ) : (
    <span className="picker-thumb picker-noimg" aria-hidden="true" />
  )
}

/** The catalog search, and describing the card as the last resort. */
function Search({
  why,
  initialQuery,
  initialLanguage,
  saving,
  onAnswer,
  onBack,
}: {
  why: IdentifyWhy | null
  initialQuery: string
  initialLanguage: string | null
  saving: boolean
  onAnswer: (a: Answer) => void
  onBack?: () => void
}) {
  const pickerId = useId()
  const [picked, setPicked] = useState<PickedCard | null>(null)
  const [describing, setDescribing] = useState<string | null>(null)

  function describe(e: FormEvent) {
    e.preventDefault()
    if (describing?.trim()) onAnswer({ source: 'DESCRIBED', description: describing.trim() })
  }

  if (describing != null) {
    return (
      <form onSubmit={describe} className="form">
        <div className="field">
          <label htmlFor={pickerId} className="field-label">
            Describe your card
          </label>
          <p id={`${pickerId}-hint`} className="field-hint">
            Its name, set and number as printed, and anything else on it: 1st Edition, holo, a stamp.
          </p>
          <input
            id={pickerId}
            aria-describedby={`${pickerId}-hint`}
            value={describing}
            onChange={(e) => setDescribing(e.target.value)}
            placeholder="e.g. Charizard Base Set 4/102, holo"
            required
            maxLength={300}
          />
        </div>
        <div className="actions">
          <button type="submit" disabled={saving} aria-busy={saving}>
            Save
          </button>
          <button type="button" className="secondary" onClick={() => setDescribing(null)}>
            Search the catalog instead
          </button>
        </div>
      </form>
    )
  }
  return (
    <div className="field">
      {why && <p>{WHY_TEXT[why]}</p>}
      <label htmlFor={pickerId} className="field-label">
        Find your card
      </label>
      <p id={`${pickerId}-hint`} className="field-hint">
        Type its name and number as printed.
      </p>
      <CardPicker
        inputId={pickerId}
        describedBy={`${pickerId}-hint`}
        initialQuery={initialQuery}
        initialLanguage={initialLanguage}
        onChange={setPicked}
        onDescribe={(typed) => setDescribing(typed)}
      />
      <div className="actions">
        {picked && (
          <button
            type="button"
            disabled={saving}
            aria-busy={saving}
            onClick={() =>
              onAnswer({
                source: 'SEARCH',
                externalId: picked.card.externalId,
                language: picked.card.language,
                edition: picked.edition,
                variant: picked.variant,
                stamp: picked.stamp,
                stampUnsure: picked.stampUnsure,
              })
            }
          >
            This is my card
          </button>
        )}
        {onBack && (
          <button type="button" className="secondary" onClick={onBack}>
            Back to the suggestions
          </button>
        )}
      </div>
    </div>
  )
}

/** What the collector said, with the early minimum check, and the way to change it. */
function Proposed({ proposal: p, onChange }: { proposal: IdentityProposal; onChange: () => void }) {
  const unsure = [
    p.source !== 'DESCRIBED' && !p.edition ? 'edition' : null,
    p.source !== 'DESCRIBED' && !p.variant ? 'finish' : null,
    p.stampUnsure ? 'stamp' : null,
  ].filter(Boolean)
  return (
    <div>
      <p>
        <span className="muted">You said it's</span>{' '}
        <strong>{p.source === 'DESCRIBED' ? p.description : [p.name, [p.setName, p.number].filter(Boolean).join(' ')].filter(Boolean).join(' · ')}</strong>
        {p.edition && p.edition !== 'UNLIMITED' && <> · {EDITION_LABEL[p.edition]}</>}
        {p.variant && <> · {VARIANT_LABEL[p.variant]}</>}
        {p.stamp && <> · {p.stamp} stamp</>}{' '}
        <button type="button" className="link" onClick={onChange}>
          Change
        </button>
      </p>
      {unsure.length > 0 && <p className="muted small">Not sure of the {unsure.join(', ')} is fine: we'll check from your photos.</p>}
      <p className="muted small">We'll check it against your photos before making an offer.</p>
      <MinimumHint proposal={p} />
    </div>
  )
}

/** Whether the card is clearly under the minimum, whatever its condition: only ever a hint. */
function MinimumHint({ proposal: p }: { proposal: IdentityProposal }) {
  const api = useApi()
  const { externalId, language, edition, variant } = p
  const stamp = p.stamp ?? ''
  const { data } = useLoad(
    () =>
      externalId && language && edition && variant
        ? api<PriceHint>('GET', apiPath`/api/catalog/price-hint?externalId=${externalId}&language=${language}&edition=${edition}&variant=${variant}&stamp=${stamp}`).catch(
            () => null,
          )
        : Promise.resolve(null),
    [api, externalId, language, edition, variant, stamp],
  )
  if (!data?.underMinimum) return null
  return (
    <p className="alert alert-warning" role="status">
      This card usually sells for under our {dollars(data.minFmv)} minimum, whatever its condition, so it's unlikely to get an offer.
    </p>
  )
}
