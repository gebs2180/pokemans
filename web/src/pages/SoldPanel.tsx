import { useState } from 'react'
import { apiPath, money, PAYMENT_METHOD, RETURN_REASON, type Listing, type ShipAddress } from '../api'
import { safeHref } from '../safeHref'
import { Field } from '../ui'
import { AddressForm } from './AddressForm'
import { days, usePolicy } from '../policy'
import { DealTimeline } from './DealTimeline'
import { dealSteps } from '../dealSteps'

const date = (at: string) => new Date(at).toLocaleDateString()

/** "by Venmo (@sam)", or nothing when the seller hasn't said. */
function how(address: ShipAddress | null | undefined) {
  return address?.paymentMethod ? `, by ${PAYMENT_METHOD[address.paymentMethod]} (${address.paymentHandle})` : ''
}

/**
 * A card a buyer took, from Sold on: each stage says what happened and what the seller does next. Rawmark
 * doesn't handle the money, so all of this only records what the two sides say.
 *
 * @param act runs a seller action, and reloads the sale and the card's status
 */
export function SoldPanel({
  orgId,
  listing,
  price,
  address,
  busy,
  act,
  onAddressSaved,
}: {
  orgId: string
  listing: Listing
  price: number | undefined
  address: ShipAddress | null | undefined
  busy: boolean
  act: (method: string, path: string, body?: unknown) => Promise<void>
  onAddressSaved: () => void
}) {
  const policy = usePolicy()
  // Each of these is recorded for good and the other side sees it: each asks once more.
  const [confirming, setConfirming] = useState<'shipped' | 'paid' | 'notPaid' | 'back' | 'disagree' | null>(null)
  const [note, setNote] = useState('')
  // Read when the seller taps, since the clock has no place in a render.
  const [beforePayBy, setBeforePayBy] = useState(false)
  const progress = listing.progress
  const stage = progress?.stage ?? 'SOLD'
  const label = listing.label
  const labelHref = safeHref(label?.labelUrl)
  const say = (action: string, body?: unknown) => {
    setConfirming(null)
    setNote('')
    return act('POST', `${apiPath`/api/orgs/${orgId}/listings/${listing.id}`}/${action}`, body)
  }
  const amount = price === undefined ? 'the price' : money(price)
  const tracking = label && (
    <p className="small">
      {label.carrier} {label.service}
      {label.trackingCode && `, tracking ${label.trackingCode}`}, insured for {money(label.insuredValue)}
    </p>
  )
  /** @param noteLabel asks for a note too; @param needsNote it can't be left empty */
  const confirmStep = (question: string, button: string, onConfirm: () => void, noteLabel?: string, needsNote = false) => (
    <div className="confirm">
      <p>{question}</p>
      {noteLabel && (
        <Field label={noteLabel}>
          {(control) => <input {...control} value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} required={needsNote} />}
        </Field>
      )}
      <div className="actions">
        <button onClick={onConfirm} disabled={busy || (needsNote && !note.trim())} aria-busy={busy}>
          {button}
        </button>
        <button className="secondary" onClick={() => setConfirming(null)}>
          Cancel
        </button>
      </div>
    </div>
  )

  const paymentAnswer =
    progress?.confirmedAt ? null : confirming === 'paid' ? (
      confirmStep(`Confirm you got ${amount}? This closes the deal, and the buyer sees it.`, 'Confirm: I got paid', () => say('payment-confirmed'))
    ) : confirming === 'notPaid' ? (
      confirmStep(
        beforePayBy && progress?.payBy
          ? `They have until ${date(progress.payBy)} to pay. Tell us now anyway? We'll follow it up with them.`
          : "Tell us you haven't been paid? We'll follow it up with them. We can't pay you ourselves: Rawmark doesn't handle the money.",
        'Confirm: I haven\'t been paid',
        () => say('dispute', { note: note || null }),
        'Anything we should know (optional)',
      )
    ) : (
      <div className="actions">
        <button onClick={() => setConfirming('paid')} disabled={busy}>
          I got paid
        </button>
        {!progress?.disputedAt && (
          <button
            className="secondary"
            onClick={() => {
              setBeforePayBy(!!progress?.payBy && new Date(progress.payBy).getTime() > Date.now())
              setConfirming('notPaid')
            }}
            disabled={busy}
          >
            I haven't been paid
          </button>
        )}
      </div>
    )

  // A handle can be missing on a card posted before sellers gave one: the buyer can't pay until it's there.
  const needsHandle = address && !address.paymentHandle && (stage === 'SOLD' || stage === 'SHIPPED' || stage === 'ARRIVED')

  return (
    <>
      <div className={`alert ${stage === 'RETURNED' || stage === 'BACK' ? 'alert-warning' : stage === 'SOLD' || progress?.confirmedAt ? 'alert-success' : 'alert-info'}`}>
        <p>
          Sold to {listing.buyer?.orgName ?? 'a buyer'} on {date(listing.closedAt!)}.
        </p>
        {(stage === 'SOLD' || stage === 'SHIPPED' || stage === 'ARRIVED' || stage === 'PAID') && (
          <DealTimeline
            tracking={label?.trackingCode}
            steps={dealSteps({
              who: 'seller',
              accepted: listing.closedAt!,
              label,
              progress,
              payWithin: days(policy.payWithinDays),
            })}
          />
        )}
        {stage === 'SOLD' && (
          <>
            <p>
              They pay you {amount} directly{how(address)}, within {days(policy.payWithinDays)} of it arriving as shown. Rawmark doesn't handle the money.
            </p>
            {label && labelHref ? (
              <>
                <p className="actions">
                  <a href={labelHref} target="_blank" rel="noreferrer" className="button">
                    Print your prepaid label
                  </a>
                </p>
                {tracking}
                <p className="alert-title">How to ship it</p>
                <ul className="small">
                  <li>Put the card in a sleeve, then a toploader, in a bubble mailer.</li>
                  <li>Stick this label on the mailer. Use only this label: it's the insured one.</li>
                  <li>Hand it to the carrier by {date(label.shipBy)}.</li>
                </ul>
                {confirming === 'shipped' ? (
                  confirmStep("Has the carrier got it? We take the label away once you say so, since it's used.", "Confirm: it's shipped", () => say('shipped'))
                ) : (
                  <div className="actions">
                    <button className="secondary" onClick={() => setConfirming('shipped')} disabled={busy}>
                      I've shipped it
                    </button>
                  </div>
                )}
              </>
            ) : (
              <p className="alert alert-warning">
                Your prepaid label isn't ready yet: buying it didn't go through. Don't ship the card until the label shows here; we'll be in
                touch.
              </p>
            )}
          </>
        )}
        {stage === 'SHIPPED' && (
          <>
            <p className="alert-title">Shipped</p>
            {tracking}
            <p>
              The buyer records it when it arrives. They then have {days(policy.payWithinDays)} to pay you {amount} directly{how(address)}, and you'll see the
              pay-by date here.
            </p>
          </>
        )}
        {stage === 'ARRIVED' && (
          <>
            <p className="alert-title">Arrived on {date(progress!.arrivedAt!)}</p>
            {listing.outcome?.note && <p>"{listing.outcome.note}"</p>}
            <p>
              They pay you {amount} directly{how(address)}, by <strong>{date(progress!.payBy!)}</strong>.
            </p>
          </>
        )}
        {stage === 'PAID' &&
          (progress!.confirmedAt ? (
            <>
              <p className="alert-title">Paid</p>
              <p>You confirmed you got paid on {date(progress!.confirmedAt)}. Nothing more to do.</p>
            </>
          ) : (
            <>
              <p className="alert-title">Paid, they say</p>
              <p>
                They recorded paying {money(progress!.pricePaid)} on {date(progress!.paidAt!)}
                {progress!.paidNote ? `: "${progress!.paidNote}"` : '.'} Did you get it?
              </p>
            </>
          ))}
        {(stage === 'SHIPPED' || stage === 'ARRIVED' || stage === 'PAID') && paymentAnswer}
        {(stage === 'RETURNED' || stage === 'BACK') && listing.outcome?.returnedReason && (
          <>
            <p className="alert-title">Returned: {RETURN_REASON[listing.outcome.returnedReason]}</p>
            {listing.outcome.note && <p>They said: "{listing.outcome.note}"</p>}
          </>
        )}
        {stage === 'RETURNED' && (
          <>
            <p className="small">
              <strong>What happens next:</strong> they send it back to you, tracked and at their cost, and they don't pay for it. Zach
              looks at every return, from the photos and what each of you says.
              {listing.outcome?.returnedReason === 'NOT_GENUINE' && " Because they say it isn't genuine, it can't get a new offer until he has."}
            </p>
            {confirming === 'back' ? (
              confirmStep('Is the card back in your hands? This ends the sale.', "Confirm: it's back", () => say('card-back'))
            ) : confirming === 'disagree' ? (
              confirmStep('Tell Zach why you disagree. The buyer won\'t see this.', 'Send it to Zach', () => say('dispute', { note }), 'Why you disagree', true)
            ) : (
              <div className="actions">
                <button onClick={() => setConfirming('back')} disabled={busy}>
                  The card's back with me
                </button>
                {!progress?.disputedAt && (
                  <button className="secondary" onClick={() => setConfirming('disagree')} disabled={busy}>
                    I disagree
                  </button>
                )}
              </div>
            )}
          </>
        )}
        {stage === 'BACK' && <p>Back with you since {date(progress!.cardBackAt!)}.</p>}
        {progress?.disputedAt && (
          <p className="small">
            You told us about this on {date(progress.disputedAt)}
            {progress.disputeNote && `: "${progress.disputeNote}"`}. Zach will be in touch.
          </p>
        )}
        {progress?.review && (
          <p className="small">
            From Zach, {date(progress.review.at)}: {progress.review.reason}
          </p>
        )}
      </div>
      {needsHandle && (
        <div className="panel">
          <p className="alert alert-warning">The buyer can't pay you until you say how. Add your payment details.</p>
          <AddressForm orgId={orgId} payment initial={address} onSaved={onAddressSaved} />
        </div>
      )}
    </>
  )
}
