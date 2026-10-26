import { useState, type FormEvent } from 'react'
import { apiPath, useApi, type ShipAddress } from '../api'
import { Field } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'

const EMPTY = { name: '', company: '', street1: '', street2: '', city: '', state: '', zip: '', phone: '' }

/** A US shipping address for the org: where a seller ships from, or a buyer's cards go to. */
export function AddressForm({ orgId, onSaved }: { orgId: string; onSaved: () => void }) {
  const api = useApi()
  const [a, setA] = useState(EMPTY)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formError = useFormError(error)

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

  // Each field's visible label is its accessible name.
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
    <form onSubmit={save} className="form" aria-describedby={formError.describedBy}>
      {field('name', 'Name', { required: true, maxLength: 100, autoComplete: 'name' })}
      {field('company', 'Company (optional)', { maxLength: 100, autoComplete: 'organization' })}
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
      <div className="actions">
        <button type="submit" disabled={busy} aria-busy={busy}>
          Save address
        </button>
      </div>
      {formError.message}
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
        <>
          <p>
            {data.name}
            {data.company && `, ${data.company}`}, {data.street1}
            {data.street2 && ` ${data.street2}`}, {data.city}, {data.state} {data.zip}
          </p>
          <div className="actions">
            <button className="secondary" onClick={() => setEditing(true)}>
              Change
            </button>
          </div>
        </>
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
