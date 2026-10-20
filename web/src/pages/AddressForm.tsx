import { useState, type FormEvent } from 'react'
import { apiPath, useApi, type ShipAddress } from '../api'
import { useLoad } from '../useLoad'

const EMPTY = { name: '', company: '', street1: '', street2: '', city: '', state: '', zip: '', phone: '' }

/** A US shipping address for the org: where a seller ships from, or a buyer's cards go to. */
export function AddressForm({ orgId, onSaved }: { orgId: string; onSaved: () => void }) {
  const api = useApi()
  const [a, setA] = useState(EMPTY)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/ship-address`, {
        ...a,
        company: a.company || null,
        street2: a.street2 || null,
        phone: a.phone || null,
      })
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const field = (key: keyof typeof EMPTY, label: string, extra: Record<string, unknown> = {}) => (
    <input
      value={a[key]}
      onChange={(e) => setA({ ...a, [key]: key === 'state' ? e.target.value.toUpperCase() : e.target.value })}
      placeholder={label}
      aria-label={label}
      {...extra}
    />
  )

  return (
    <form onSubmit={save} className="stack">
      {field('name', 'Name', { required: true, maxLength: 100 })}
      {field('company', 'Company (optional)', { maxLength: 100 })}
      {field('street1', 'Street address', { required: true, maxLength: 200 })}
      {field('street2', 'Apartment, suite (optional)', { maxLength: 200 })}
      <div className="row">
        {field('city', 'City', { required: true, maxLength: 100 })}
        {field('state', 'State', { required: true, pattern: '[A-Z]{2}', maxLength: 2, className: 'narrow' })}
        {field('zip', 'ZIP code', { required: true, pattern: '\\d{5}(-\\d{4})?', className: 'narrow' })}
      </div>
      {field('phone', 'Phone (optional, for the carrier)', { maxLength: 30 })}
      <button type="submit" disabled={busy}>
        Save address
      </button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

/** The org's current address, with a form to set or change it. */
export function AddressPanel({ orgId, title }: { orgId: string; title: string }) {
  const api = useApi()
  const { data: current, error, reload } = useLoad(
    () => api<ShipAddress | null>('GET', apiPath`/api/orgs/${orgId}/ship-address`).then((address) => ({ address })),
    [api, orgId],
  )
  const [editing, setEditing] = useState(false)

  if (error) return <p className="error" role="alert">{error}</p>
  if (!current) return null
  const data = current.address
  return (
    <section className="panel">
      <h2>{title}</h2>
      {data && !editing ? (
        <p>
          {data.name}
          {data.company && `, ${data.company}`}, {data.street1}
          {data.street2 && ` ${data.street2}`}, {data.city}, {data.state} {data.zip}{' '}
          <button className="link" onClick={() => setEditing(true)}>
            Change
          </button>
        </p>
      ) : (
        <AddressForm
          orgId={orgId}
          onSaved={() => {
            setEditing(false)
            reload()
          }}
        />
      )}
    </section>
  )
}
