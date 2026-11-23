import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ORG_TYPE_LABEL, useApi, type BuyingState, type Me } from '../api'
import { message } from '../format'
import { Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { soleOrg } from '../useOrg'

const EMPTY = { name: '', street1: '', street2: '', city: '', state: '', zip: '', phone: '' }

/**
 * One login, two sides (Zach, 2026-11-22): what the person sells from, and where they stand on buying, with the way to
 * start. Buying waits on the admin's approval until shipping labels are paid at accept.
 */
export function AccountPage() {
  const api = useApi()
  const me = useLoad(() => api<Me>('GET', '/api/me'), [api])
  const buying = useLoad(() => api<BuyingState>('GET', '/api/me/buying'), [api])

  const error = me.error ?? buying.error
  if (error) return <PageError message={error} />
  if (!me.data || !buying.data) return <Loading />

  const sole = soleOrg(me.data)
  const selling = me.data.memberships.filter((m) => m.orgType !== 'BUYER')
  return (
    <>
      <h1>Your account</h1>
      <section className="panel" aria-labelledby="selling">
        <h2 id="selling">Selling</h2>
        {selling.length === 0 ? (
          <p className="muted">You don't sell here yet. Ask Zach for an invite link.</p>
        ) : (
          <ul>
            {selling.map((m) => (
              <li key={m.orgId}>
                <Link to={sole?.orgId === m.orgId ? '/' : `/orgs/${m.orgId}`}>{m.orgName}</Link>{' '}
                <span className="muted">{ORG_TYPE_LABEL[m.orgType]}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="panel" aria-labelledby="buying">
        <h2 id="buying">Buying</h2>
        <BuyingSide state={buying.data} me={me.data} onChange={() => {
          buying.reload()
          me.reload()
        }} />
      </section>
    </>
  )
}

function BuyingSide({ state, me, onChange }: { state: BuyingState; me: Me; onChange: () => void }) {
  const [asking, setAsking] = useState(false)
  const date = (at: string | null) => (at ? new Date(at).toLocaleDateString() : '')

  if (state.status === 'APPROVED') {
    return (
      <>
        <p>You can buy. Every approved buyer sees new cards at the same time.</p>
        {state.orgId && (
          <p>
            <Link to={`/orgs/${state.orgId}`}>{state.orgName ?? 'Your buyer account'}</Link>
          </p>
        )}
      </>
    )
  }
  if (state.status === 'WAITING') {
    return (
      <p role="status">
        Waiting for approval. You asked on {date(state.appliedAt)}. We'll open the market to you once Zach approves it.
      </p>
    )
  }
  if (me.memberships.length === 0) {
    return <p className="muted">Buying starts from an account you already use here.</p>
  }
  const declined = state.status === 'DECLINED'
  return (
    <>
      {declined ? (
        <>
          <p role="status">We couldn't approve buying on {date(state.decidedAt)}.</p>
          {state.note && <blockquote>{state.note}</blockquote>}
        </>
      ) : (
        <p>Buy raw cards at one firm price each, from the same login. Zach approves each new buyer first.</p>
      )}
      {asking ? (
        <StartBuying state={state} defaultName={state.orgName ?? me.memberships[0].orgName} onDone={onChange} />
      ) : (
        <button onClick={() => setAsking(true)}>{declined ? 'Ask again' : 'Start buying'}</button>
      )}
    </>
  )
}

/** The buyer terms, a name and a ship-to address: everything the request needs, sent together. */
function StartBuying({ state, defaultName, onDone }: { state: BuyingState; defaultName: string; onDone: () => void }) {
  const api = useApi()
  const [name, setName] = useState(defaultName)
  const [a, setA] = useState(EMPTY)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api('POST', '/api/me/buying', {
        name,
        version: state.terms.version,
        shipTo: { ...a, company: null, street2: a.street2 || null, phone: a.phone || null },
      })
      onDone()
    } catch (err) {
      setError(message(err))
    } finally {
      setBusy(false)
    }
  }

  const field = (key: keyof typeof EMPTY, label: string, extra: Record<string, unknown> = {}) => (
    <Field label={label}>
      {(control) => (
        <input
          {...control}
          value={a[key]}
          onChange={(e) => setA({ ...a, [key]: key === 'state' ? e.target.value.toUpperCase() : e.target.value })}
          {...extra}
        />
      )}
    </Field>
  )

  return (
    <form onSubmit={submit} className="form" aria-describedby={formError.describedBy}>
      <h3>The buyer terms</h3>
      <pre className="terms" tabIndex={0} role="region" aria-label="Buyer terms">
        {state.terms.text}
      </pre>
      <Field label="Name buyers' deals go under" hint="Your name or your shop's. Sellers never see it.">
        {(control) => <input {...control} value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />}
      </Field>
      <h3>Where cards ship to</h3>
      {field('name', 'Ship to (name)', { required: true, maxLength: 100, autoComplete: 'name' })}
      {field('street1', 'Street address', { required: true, maxLength: 200, autoComplete: 'address-line1' })}
      {field('street2', 'Apartment, suite (optional)', { maxLength: 200, autoComplete: 'address-line2' })}
      <div className="field-row">
        {field('city', 'City', { required: true, maxLength: 100, autoComplete: 'address-level2' })}
        {field('state', 'State', {
          required: true,
          pattern: '[A-Z]{2}',
          maxLength: 2,
          autoComplete: 'address-level1',
          placeholder: 'State, e.g. IL',
          title: 'The two-letter state code, e.g. IL',
        })}
        {field('zip', 'ZIP code', {
          required: true,
          pattern: '\\d{5}(-\\d{4})?',
          autoComplete: 'postal-code',
          inputMode: 'numeric',
          title: 'A 5-digit ZIP code, e.g. 62701',
        })}
      </div>
      {field('phone', 'Phone (optional, for the carrier)', { maxLength: 30, autoComplete: 'tel', type: 'tel' })}
      <label className="check">
        <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required /> I accept the buyer terms
      </label>
      <div className="actions">
        <button type="submit" disabled={busy || !agreed} aria-busy={busy}>
          Ask to buy
        </button>
      </div>
      {formError.message}
    </form>
  )
}

/** On a buyer account that can't buy yet: says so and where to look, in place of a market that would refuse. */
export function BuyingNotice({ orgId }: { orgId: string }) {
  const api = useApi()
  const { data } = useLoad(() => api<BuyingState>('GET', '/api/me/buying'), [api])
  // Quiet on failure: the market says the same thing when it refuses.
  if (!data || data.orgId !== orgId || data.status === 'APPROVED' || data.status === 'NOT_STARTED') return null
  return (
    <p className="panel" role="status">
      {data.status === 'WAITING' ? 'Buying opens once Zach approves your request.' : "We couldn't approve buying on this account."}{' '}
      <Link to="/account">See where it stands</Link>
    </p>
  )
}
