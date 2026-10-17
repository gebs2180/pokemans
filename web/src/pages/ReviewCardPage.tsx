import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  OUTCOMES,
  useApi,
  type Assessment,
  type CardCondition,
  type CatalogHit,
  type Confidence,
  type Distribution,
  type Edition,
  type ProviderCard,
  type Variant,
} from '../api'
import { useLoad } from '../useLoad'

const EDITIONS: Edition[] = ['UNLIMITED', 'FIRST_EDITION', 'SHADOWLESS']
const VARIANTS: Variant[] = ['NORMAL', 'HOLO', 'REVERSE_HOLO']
const RISK_LABEL = {
  NONE: null,
  VINTAGE_HOLO: 'Vintage holo / 1st Edition: authenticity risk, zero collateral until verified',
  JAPANESE_PROMO: 'Japanese promo: authenticity risk, zero collateral until verified',
  RECENT_CHASE: 'Recent chase card: authenticity risk, zero collateral until verified',
} as const

function message(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}

/** Admin: identify one card and review its condition distribution. */
export function ReviewCardPage() {
  const { orgId = '', cardId = '' } = useParams()
  const api = useApi()
  const base = `/api/admin/orgs/${orgId}/cards/${cardId}`
  const { data, error, reload } = useLoad(() => api<CardCondition>('GET', `${base}/condition`), [api, base])

  if (error) return <p className="error">{error}</p>
  if (!data) return <p>Loading…</p>

  const current = data.assessments.find((a) => a.current) ?? null
  return (
    <>
      <p>
        <Link to="/admin/review">← Review queue</Link>
      </p>
      <h1>{data.label}</h1>

      <section className="panel">
        <h2>Photos</h2>
        <div className="photos">
          {data.photos.map((p) => (
            <a key={p.id} href={p.url} target="_blank" rel="noreferrer" className="photo">
              <img src={p.cropUrl ?? p.url} alt={`${p.side} photo`} />
              <span className="muted small">
                {p.side.toLowerCase()}
                {p.cropUrl ? ' · card crop, tap for original' : ''}
              </span>
            </a>
          ))}
        </div>
      </section>

      <Identification data={data} base={base} onChange={reload} />
      <Predictions data={data} base={base} onChange={reload} />
      <AssessmentPanel key={current?.id ?? 'none'} data={data} current={current} base={base} onChange={reload} />
    </>
  )
}

function Identification({ data, base, onChange }: { data: CardCondition; base: string; onChange: () => void }) {
  const api = useApi()
  const [name, setName] = useState('')
  const [number, setNumber] = useState('')
  const [language, setLanguage] = useState('en')
  const [hits, setHits] = useState<CatalogHit[] | null>(null)
  const [picked, setPicked] = useState<ProviderCard | null>(null)
  const [edition, setEdition] = useState<Edition>('UNLIMITED')
  const [variant, setVariant] = useState<Variant>('HOLO')
  const [stamp, setStamp] = useState('')
  const [promo, setPromo] = useState(false)
  const [manual, setManual] = useState(false)
  const [setName_, setSetName] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const id = data.identification

  async function search(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setPicked(null)
    setBusy(true)
    try {
      const params = new URLSearchParams({ name, language })
      if (number) params.set('number', number)
      setHits(await api<CatalogHit[]>('GET', `/api/admin/catalog/search?${params}`))
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  async function pick(hit: CatalogHit) {
    setErr(null)
    try {
      const card = await api<ProviderCard>('GET', `/api/admin/catalog/cards/${encodeURIComponent(hit.externalId)}?language=${language}`)
      setPicked(card)
      setEdition(card.editions[0] ?? 'UNLIMITED')
      setVariant(card.variants[0] ?? 'NORMAL')
      setPromo(card.promo)
      setStamp('')
    } catch (e) {
      setErr(message(e))
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setBusy(true)
    try {
      const body = manual
        ? { provider: 'MANUAL', language, edition, variant, stamp, promo, setName: setName_, number, name, note }
        : { provider: 'TCGDEX', externalId: picked?.externalId, language, edition, variant, stamp, promo, note }
      await api('POST', `${base}/identifications`, body)
      setHits(null)
      setPicked(null)
      setManual(false)
      setNote('')
      onChange()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel">
      <h2>Identity</h2>
      {id ? (
        <div className="identity">
          {id.imageUrl && <img src={`${id.imageUrl}/low.webp`} alt="" />}
          <div>
            <strong>{id.name}</strong> · {id.setName} {id.number} · {id.language.toUpperCase()}
            <div className="muted">
              {[id.edition, id.variant, id.stamp, id.promo ? 'promo' : null, id.rarity, id.setReleaseDate?.slice(0, 4)]
                .filter(Boolean)
                .join(' · ')}
            </div>
            {RISK_LABEL[id.authenticityRisk] && <p className="warn">{RISK_LABEL[id.authenticityRisk]}</p>}
          </div>
        </div>
      ) : (
        <p className="warn">Not identified yet.</p>
      )}

      <details open={!id}>
        <summary>{id ? 'Change identity' : 'Identify'}</summary>
        <form onSubmit={search} className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name, e.g. Metagross" required />
          <input value={number} onChange={(e) => setNumber(e.target.value)} placeholder="Number" className="narrow" />
          <select value={language} onChange={(e) => setLanguage(e.target.value)}>
            <option value="en">English</option>
            <option value="ja">Japanese</option>
          </select>
          {!manual && (
            <button type="submit" disabled={busy}>
              Search
            </button>
          )}
          <button type="button" className="link" onClick={() => setManual((m) => !m)}>
            {manual ? 'Search the catalog instead' : 'Not listed? Enter by hand'}
          </button>
        </form>

        {!manual && hits && hits.length === 0 && <p className="muted">No matches.</p>}
        {!manual && hits && hits.length > 0 && !picked && (
          <div className="hits">
            {hits.map((h) => (
              <button key={h.externalId} type="button" className="hit" onClick={() => pick(h)}>
                {h.imageUrl ? <img src={`${h.imageUrl}/low.webp`} alt="" loading="lazy" /> : <span className="noimg">No image</span>}
                <span>
                  {h.name} · {h.setId} {h.number}
                </span>
              </button>
            ))}
          </div>
        )}

        {(picked || manual) && (
          <form onSubmit={confirm} className="stack">
            {picked && !manual && (
              <p>
                <strong>{picked.name}</strong> · {picked.setName} {picked.number} · {picked.rarity}{' '}
                <span className="muted">{picked.setReleaseDate?.slice(0, 4)}</span>
              </p>
            )}
            {manual && (
              <input value={setName_} onChange={(e) => setSetName(e.target.value)} placeholder="Set name" required />
            )}
            <div className="row">
              <select value={edition} onChange={(e) => setEdition(e.target.value as Edition)}>
                {EDITIONS.map((v) => (
                  <option key={v} value={v}>
                    {v.replace('_', ' ').toLowerCase()}
                    {picked && !picked.editions.includes(v) ? ' (not listed)' : ''}
                  </option>
                ))}
              </select>
              <select value={variant} onChange={(e) => setVariant(e.target.value as Variant)}>
                {VARIANTS.map((v) => (
                  <option key={v} value={v}>
                    {v.replace('_', ' ').toLowerCase()}
                    {picked && !picked.variants.includes(v) ? ' (not listed)' : ''}
                  </option>
                ))}
              </select>
              <input
                value={stamp}
                onChange={(e) => setStamp(e.target.value)}
                placeholder={picked?.stamps.length ? `Stamp: ${picked.stamps.join(', ')}` : 'Stamp (if any)'}
              />
              <label>
                <input type="checkbox" checked={promo} onChange={(e) => setPromo(e.target.checked)} /> Promo
              </label>
            </div>
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" />
            <div>
              <button type="submit" disabled={busy}>
                Confirm identity
              </button>
            </div>
          </form>
        )}
        {err && <p className="error">{err}</p>}
      </details>
    </section>
  )
}

function Predictions({ data, base, onChange }: { data: CardCondition; base: string; onChange: () => void }) {
  const api = useApi()
  const [err, setErr] = useState<string | null>(null)
  const open = data.requests.filter((r) => r.status === 'PENDING' || r.status === 'SUBMITTED')
  const failed = data.requests.filter((r) => r.status === 'FAILED')

  async function ask() {
    setErr(null)
    try {
      await api('POST', `${base}/prediction-requests`)
      onChange()
    } catch (e) {
      setErr(message(e))
    }
  }

  return (
    <section className="panel">
      <h2>Pre-grading providers</h2>
      {data.enabledProviders.length === 0 ? (
        <p className="muted">No provider is enabled. Enter the distribution by hand below.</p>
      ) : (
        <p className="muted">
          Enabled: {data.enabledProviders.join(', ')}.{' '}
          {open.length > 0 ? `Waiting on ${open.map((r) => r.provider).join(', ')}.` : ''}{' '}
          <button className="link" onClick={ask}>
            Ask again
          </button>{' '}
          <button className="link" onClick={onChange}>
            Refresh
          </button>
        </p>
      )}
      {failed.slice(0, 3).map((r) => (
        <p key={r.id} className="warn small">
          {r.provider} failed after {r.attempts} attempt(s): {r.lastError}
        </p>
      ))}
      {data.predictions.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Provider</th>
              <th>Overall</th>
              <th>PSA</th>
              <th>Center</th>
              <th>Corners</th>
              <th>Edges</th>
              <th>Surface</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {data.predictions.map((p) =>
              p.succeeded ? (
                <tr key={p.id}>
                  <td>{p.provider}</td>
                  <td>{p.overallGrade ?? '–'}</td>
                  <td>{p.psaGrade ?? '–'}</td>
                  <td>{p.centering ?? '–'}</td>
                  <td>{p.corners ?? '–'}</td>
                  <td>{p.edges ?? '–'}</td>
                  <td>{p.surface ?? '–'}</td>
                  <td className="muted small">{new Date(p.createdAt).toLocaleString()}</td>
                </tr>
              ) : (
                <tr key={p.id}>
                  <td>{p.provider}</td>
                  <td colSpan={6} className="warn">
                    {p.error}
                  </td>
                  <td className="muted small">{new Date(p.createdAt).toLocaleString()}</td>
                </tr>
              ),
            )}
          </tbody>
        </table>
      )}
      {err && <p className="error">{err}</p>}
    </section>
  )
}

function percent(p: number) {
  return `${Math.round(p * 1000) / 10}%`
}

function AssessmentPanel({
  data,
  current,
  base,
  onChange,
}: {
  data: CardCondition
  current: Assessment | null
  base: string
  onChange: () => void
}) {
  const api = useApi()
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(OUTCOMES.map((o) => [o, current ? String(Math.round((current.distribution[o] ?? 0) * 1000) / 10) : '0'])),
  )
  const [confidence, setConfidence] = useState<Confidence>(current?.confidence ?? 'C')
  const [reason, setReason] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const total = OUTCOMES.reduce((sum, o) => sum + (Number(values[o]) || 0), 0)
  const totalOk = Math.abs(total - 100) < 0.001

  async function save(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setBusy(true)
    try {
      const distribution: Distribution = Object.fromEntries(OUTCOMES.map((o) => [o, (Number(values[o]) || 0) / 100]))
      await api('POST', `${base}/assessments`, { distribution, confidence, reason })
      onChange()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  async function rebuild() {
    setErr(null)
    try {
      await api('POST', `${base}/assessments/rebuild`)
      onChange()
    } catch (e) {
      setErr(message(e))
    }
  }

  return (
    <section className="panel">
      <h2>Condition distribution</h2>
      {current ? (
        <>
          <p>
            Version {current.version} · {current.authorType === 'SYSTEM' ? 'system' : 'reviewer'} · median {current.median} ·
            confidence {current.confidence}
          </p>
          {current.reason && <p className="muted">{current.reason}</p>}
          <Bars distribution={current.distribution} />
        </>
      ) : (
        <p className="warn">No distribution yet.</p>
      )}

      <details>
        <summary>Adjust (saves a new version)</summary>
        <form onSubmit={save} className="stack">
          <div className="grades">
            {OUTCOMES.map((o) => (
              <label key={o}>
                <span>{o}</span>
                <input
                  inputMode="decimal"
                  value={values[o]}
                  onChange={(e) => setValues((v) => ({ ...v, [o]: e.target.value }))}
                />
              </label>
            ))}
          </div>
          <p className={totalOk ? 'muted small' : 'warn small'}>Total {Math.round(total * 10) / 10}% (must be 100%)</p>
          <div className="row">
            <select value={confidence} onChange={(e) => setConfidence(e.target.value as Confidence)}>
              {(['A', 'B', 'C', 'D'] as Confidence[]).map((c) => (
                <option key={c} value={c}>
                  Confidence {c}
                </option>
              ))}
            </select>
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why (required)" required maxLength={2000} />
          </div>
          <div className="row">
            <button type="submit" disabled={busy || !totalOk}>
              Save version
            </button>
            {data.predictions.some((p) => p.succeeded) && (
              <button type="button" className="link" onClick={rebuild}>
                Rebuild from provider answers instead
              </button>
            )}
          </div>
        </form>
      </details>
      {err && <p className="error">{err}</p>}

      {data.assessments.length > 1 && (
        <details>
          <summary>History ({data.assessments.length} versions)</summary>
          <ul className="list">
            {data.assessments.map((a) => (
              <li key={a.id}>
                v{a.version} · {a.authorType.toLowerCase()} · median {a.median} · {a.confidence} ·{' '}
                {new Date(a.createdAt).toLocaleString()}
                {a.reason && <div className="muted small">{a.reason}</div>}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function Bars({ distribution }: { distribution: Distribution }) {
  const max = Math.max(...OUTCOMES.map((o) => distribution[o] ?? 0), 0.0001)
  return (
    <div className="bars" role="img" aria-label="Probability per grade">
      {OUTCOMES.map((o) => {
        const p = distribution[o] ?? 0
        return (
          <div key={o} className="bar">
            <span className="value small">{p > 0 ? percent(p) : ''}</span>
            <span className="fill" style={{ height: `${(p / max) * 100}%` }} />
            <span className="label">{o}</span>
          </div>
        )
      })}
    </div>
  )
}
