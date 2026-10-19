import { useState, type FormEvent } from 'react'
import {
  apiPath,
  useApi,
  type AuthMethod,
  type AuthState,
  type CustodyMethod,
  type CustodyRequest,
  type CustodyState,
  type Finding,
  type HolderType,
  type ProvenanceView,
  type VerifyRequest,
} from '../api'
import { useLoad } from '../useLoad'

const FINDINGS: Finding[] = ['VERIFIED_AUTHENTIC', 'SUSPECTED_COUNTERFEIT', 'COUNTERFEIT']
const AUTH_METHODS: AuthMethod[] = ['IN_HAND_INSPECTION', 'GRADER_SLAB', 'THIRD_PARTY_AUTHENTICATION', 'PHOTO_REVIEW']
const HOLDER_TYPES: HolderType[] = ['OWNER', 'PARTNER', 'VAULT', 'GRADER', 'LENDER', 'OTHER']
const CUSTODY_METHODS: CustodyMethod[] = ['IN_PERSON', 'VAULT_STATEMENT', 'GRADER_RECEIPT', 'VIDEO_CALL']

const AUTH_LABEL: Record<AuthState, { text: string; className: string }> = {
  NOT_FLAGGED: { text: 'Not in an authenticity-risk category.', className: 'ok' },
  VERIFIED_AUTHENTIC: { text: 'Verified authentic as its current identity.', className: 'ok' },
  NOT_IDENTIFIED: { text: 'Not identified: counts as flagged, zero collateral value.', className: 'warn' },
  AWAITING_VERIFICATION: { text: 'Flagged: zero collateral value until verified.', className: 'warn' },
  SUSPECTED_COUNTERFEIT: { text: 'Suspected counterfeit: zero collateral value.', className: 'error' },
  COUNTERFEIT: { text: 'Counterfeit: zero collateral value.', className: 'error' },
}

const CUSTODY_LABEL: Record<CustodyState, { text: string; className: string }> = {
  NOT_RECORDED: { text: 'No custody recorded: no advance.', className: 'warn' },
  UNVERIFIED: { text: 'Custody not verified: no advance.', className: 'warn' },
  VERIFIED: { text: 'Custody verified.', className: 'ok' },
}

function message(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}

function label(value: string) {
  return value.replaceAll('_', ' ').toLowerCase()
}

/** Today as YYYY-MM-DD in the reviewer's own time zone. */
function today() {
  return new Date().toLocaleDateString('en-CA')
}

/** Admin: the card's authenticity findings and custody, the two gates on collateral eligibility. */
export function ProvenancePanel({ orgId, cardId }: { orgId: string; cardId: string }) {
  const api = useApi()
  const { data, error, reload } = useLoad(
    () => api<ProvenanceView>('GET', apiPath`/api/orgs/${orgId}/cards/${cardId}/provenance`),
    [api, orgId, cardId],
  )
  const base = apiPath`/api/admin/orgs/${orgId}/cards/${cardId}`

  return (
    <section className="panel">
      <h2>Authenticity and custody</h2>
      {error && <p className="error" role="alert">{error}</p>}
      {data && (
        <>
          <h3>Authenticity</h3>
          <p className={AUTH_LABEL[data.authState].className}>{AUTH_LABEL[data.authState].text}</p>
          {data.verifications.length > 0 && (
            <ul className="list">
              {data.verifications.map((v) => (
                <li key={v.id}>
                  {label(v.status)} · {label(v.method)} · {new Date(v.createdAt).toLocaleString()}
                  {!v.forCurrentIdentity && <span className="muted small"> · for an earlier identity</span>}
                  {v.note && <div className="muted small">{v.note}</div>}
                </li>
              ))}
            </ul>
          )}
          {data.authState === 'NOT_IDENTIFIED' ? (
            <p className="muted">Confirm the card's identity first: a finding is made against it.</p>
          ) : (
            <VerificationForm base={base} onSaved={reload} />
          )}

          <h3>Custody</h3>
          <p className={CUSTODY_LABEL[data.custodyState].className}>{CUSTODY_LABEL[data.custodyState].text}</p>
          {data.custodyHistory.length > 0 && (
            <ul className="list">
              {data.custodyHistory.map((c) => (
                <li key={c.id}>
                  {c.holderName} ({label(c.holderType)}) · {c.location} · since {c.heldSince} ·{' '}
                  {c.verified && c.verificationMethod ? `checked by ${label(c.verificationMethod)}` : 'not checked'}
                  {c.note && <div className="muted small">{c.note}</div>}
                </li>
              ))}
            </ul>
          )}
          <CustodyForm base={base} onSaved={reload} />
        </>
      )}
    </section>
  )
}

export function VerificationForm({ base, onSaved }: { base: string; onSaved: () => void }) {
  const api = useApi()
  const [finding, setFinding] = useState<Finding>('VERIFIED_AUTHENTIC')
  const [method, setMethod] = useState<AuthMethod>('IN_HAND_INSPECTION')
  const [note, setNote] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Photos can raise a doubt but can't clear one.
  const methods = finding === 'VERIFIED_AUTHENTIC' ? AUTH_METHODS.filter((m) => m !== 'PHOTO_REVIEW') : AUTH_METHODS

  function changeFinding(next: Finding) {
    setFinding(next)
    if (next === 'VERIFIED_AUTHENTIC' && method === 'PHOTO_REVIEW') setMethod('IN_HAND_INSPECTION')
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setBusy(true)
    try {
      const body: VerifyRequest = { status: finding, method, note: note.trim() || null }
      await api('POST', `${base}/authenticity-verifications`, body)
      setNote('')
      onSaved()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <div className="row">
        <select value={finding} onChange={(e) => changeFinding(e.target.value as Finding)} aria-label="Finding">
          {FINDINGS.map((f) => (
            <option key={f} value={f}>
              {label(f)}
            </option>
          ))}
        </select>
        <select value={method} onChange={(e) => setMethod(e.target.value as AuthMethod)} aria-label="How it was checked">
          {methods.map((m) => (
            <option key={m} value={m}>
              {label(m)}
            </option>
          ))}
        </select>
      </div>
      <input
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="What was checked, e.g. cert number, light test"
        aria-label="Finding note"
        maxLength={2000}
      />
      <div>
        <button type="submit" disabled={busy}>
          Record finding
        </button>
      </div>
      {err && <p className="error" role="alert">{err}</p>}
    </form>
  )
}

export function CustodyForm({ base, onSaved }: { base: string; onSaved: () => void }) {
  const api = useApi()
  const [holderType, setHolderType] = useState<HolderType>('OWNER')
  const [holderName, setHolderName] = useState('')
  const [location, setLocation] = useState('')
  const [heldSince, setHeldSince] = useState(today())
  const [checked, setChecked] = useState(false)
  const [method, setMethod] = useState<CustodyMethod>('IN_PERSON')
  const [note, setNote] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setBusy(true)
    try {
      const body: CustodyRequest = {
        holderType,
        holderName: holderName.trim(),
        location: location.trim(),
        heldSince,
        verificationMethod: checked ? method : null,
        note: note.trim() || null,
      }
      await api('POST', `${base}/custody-records`, body)
      setChecked(false)
      setNote('')
      onSaved()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <div className="row">
        <select value={holderType} onChange={(e) => setHolderType(e.target.value as HolderType)} aria-label="Holder type">
          {HOLDER_TYPES.map((t) => (
            <option key={t} value={t}>
              {label(t)}
            </option>
          ))}
        </select>
        <input value={holderName} onChange={(e) => setHolderName(e.target.value)} placeholder="Who holds it" aria-label="Holder" required maxLength={200} />
        <input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Where" aria-label="Location" required maxLength={500} />
        <label>
          Since{' '}
          <input type="date" value={heldSince} max={today()} onChange={(e) => setHeldSince(e.target.value)} aria-label="Held since" required />
        </label>
      </div>
      <div className="row">
        <label>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} aria-label="Custody checked" /> Checked
        </label>
        {checked && (
          <select value={method} onChange={(e) => setMethod(e.target.value as CustodyMethod)} aria-label="How custody was checked">
            {CUSTODY_METHODS.map((m) => (
              <option key={m} value={m}>
                {label(m)}
              </option>
            ))}
          </select>
        )}
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" aria-label="Custody note" maxLength={2000} />
      </div>
      <div>
        <button type="submit" disabled={busy}>
          Record custody
        </button>
      </div>
      {err && <p className="error" role="alert">{err}</p>}
    </form>
  )
}
