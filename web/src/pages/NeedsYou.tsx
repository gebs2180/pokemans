import { Link } from 'react-router'
import { apiPath, shortDate, SIDE_LABEL, useApi, type Membership, type NeedsYouItem } from '../api'
import { Chip, type Tone } from '../ui'
import { useLoad } from '../useLoad'

/** The chip and the line under it: what's waiting, and by when. */
function describe(item: NeedsYouItem): { chip: string; tone: Tone; detail: string } {
  const due = item.due ? shortDate(item.due) : ''
  switch (item.need) {
    case 'LABEL_READY':
      return item.due && new Date(item.due) < new Date()
        ? { chip: `Was due ${due}`, tone: 'danger', detail: "Your prepaid label is ready and the ship-by date has passed. If you've shipped it, there's nothing more to do." }
        : { chip: `Ship by ${due}`, tone: 'warning', detail: "Your prepaid label is ready. If you've shipped it, there's nothing more to do." }
    case 'OFFER_EXPIRING':
      return { chip: `Offer ends ${due}`, tone: 'warning', detail: 'Sell at it before then, or it lapses.' }
    case 'OFFER_READY':
      return { chip: `Good until ${due}`, tone: 'info', detail: 'You have an offer: sell at it, or let it expire.' }
    case 'GET_OFFER':
      return { chip: 'Get your offer', tone: 'info', detail: "We've reviewed it, so it's ready to price." }
    case 'RETAKE':
      return {
        chip: `Retake ${item.sides.map((s) => SIDE_LABEL[s].toLowerCase()).join(' and ')}`,
        tone: 'warning',
        detail: "A photo didn't pass. Open the card to see why.",
      }
  }
}

/** Everything waiting on the seller, soonest first, across their orgs. There are no notifications yet, so this is how they find out. */
export function NeedsYou({ orgs }: { orgs: Membership[] }) {
  const api = useApi()
  const { data, error } = useLoad(async () => {
    const lists = await Promise.all(
      orgs.map(async (org) => {
        const items = await api<NeedsYouItem[]>('GET', apiPath`/api/orgs/${org.orgId}/needs-you`)
        return items.map((item) => ({ ...item, orgId: org.orgId }))
      }),
    )
    return lists.flat().sort((a, b) => (a.due ?? '9').localeCompare(b.due ?? '9'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, orgs.map((o) => o.orgId).join()])

  if (error) return <p className="error" role="alert">We couldn't load what needs you: {error}</p>
  if (!data || data.length === 0) return null

  return (
    <section aria-label="Needs you">
      <h2>Needs you</h2>
      <ul className="tile-list">
        {data.map((item) => {
          const { chip, tone, detail } = describe(item)
          return (
            <li key={item.cardId} className="tile">
              <div className="tile-head">
                <Link to={`/orgs/${item.orgId}/cards/${item.cardId}`}>{item.label}</Link>
                <Chip tone={tone}>{chip}</Chip>
              </div>
              <span className="muted">{detail}</span>
            </li>
          )
        })}
      </ul>
      <p className="muted small">We don't send notifications yet, so check back here.</p>
    </section>
  )
}
