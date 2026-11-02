import { useAuth0 } from '@auth0/auth0-react'
import { useEffect } from 'react'
import { Link, useLocation } from 'react-router'
import { useApi, type Me, type OrgType } from '../api'
import { CONTACT } from '../contact'
import { useLoad } from '../useLoad'

const TERMS_LABEL: Partial<Record<OrgType, string>> = { COLLECTOR: 'seller terms', CONTRIBUTOR: 'contributor terms', BUYER: 'buyer terms' }

export type GuideFor = 'seller' | 'buyer' | 'contributor'

function TheTest() {
  return (
    <section>
      <h2>It's a test</h2>
      <ul>
        <li>
          Rawmark gives you one firm price for a raw card, worked out from the grade it's likely to get. Offers go up to{' '}
          <strong>$5,000</strong>, the most a shipping label can insure.
        </li>
        <li>Offers can be wrong, and the app can stop working or be paused.</li>
        <li>
          <strong>There are no notifications yet.</strong> Nobody gets an email when a buyer takes a card or a label is ready. Your home
          page lists what needs you, so check it.
        </li>
        <li>During the test, don't buy from or sell to someone you met here outside the app.</li>
      </ul>
    </section>
  )
}

function Selling() {
  return (
    <section>
      <h2>Selling a card</h2>
      <ul>
        <li>
          <strong>Photos.</strong> Take a front and a back photo of each card, plus a close-up if you like. If Android asks to let Chrome
          take pictures, tap "While using the app".
        </li>
        <li>
          <strong>One firm price.</strong> Once your card is identified and checked, you can get one price. "How we priced it" shows how we
          got there. An offer is good for 7 days.
        </li>
        <li>
          <strong>Selling</strong> puts your card in front of every buyer for 14 days. Posting isn't a sale yet: you can withdraw it until a
          buyer takes it. While it's posted, don't sell it anywhere else.
        </li>
        <li>
          <strong>The price is final.</strong> Once a buyer takes it, the sale is firm for both of you at that price, whatever grade the
          card gets later. The buyer won't re-grade it on arrival and come back with a lower offer.
        </li>
        <li>
          <strong>Shipping.</strong> You don't pay postage: the buyer does. Wait until the prepaid label shows on the card's page, then ship{' '}
          <strong>within 3 days</strong>, with that label, the card in a <strong>sleeve and toploader inside a bubble mailer</strong>. Don't
          ship before the label shows. Press <strong>I've shipped it</strong> on the card's page once it's with the carrier. If you can't
          ship, tell us and we'll cancel the deal.
        </li>
        <li>
          <strong>Getting paid.</strong> The buyer pays you the offer price directly, not through the app,{' '}
          <strong>within 7 days of the card arriving</strong> as shown.
        </li>
        <li>
          <strong>Lost or damaged in the post?</strong> We claim on the label's insurance and pass on what the insurer pays.
        </li>
      </ul>
    </section>
  )
}

function Buying() {
  return (
    <section>
      <h2>Buying a card</h2>
      <ul>
        <li>
          <strong>Cards for sale</strong> shows the photos each price was based on. The first buyer to accept gets the card at that price.
        </li>
        <li>
          <strong>Accepting means you're buying.</strong> The price doesn't change with the grade you think it is when it arrives, or the
          grade it later gets.
        </li>
        <li>
          <strong>Postage is yours.</strong> We buy an insured label when you accept, and bill you for postage and insurance once a month.
        </li>
        <li>
          <strong>When it arrives,</strong> record it in Your deals and pay the seller within <strong>7 days</strong>.
        </li>
        <li>
          <strong>You can send a card back for only three reasons:</strong> it isn't genuine, it isn't the card that was listed, or it
          doesn't match its photos. Send it back within those 7 days, tracked and at your cost, and record why in the app. "I think it's an
          8, not a 9" isn't a reason: condition the photos show is your risk.
        </li>
        <li>A card marked "not yet authenticated" is sold subject to you checking it in hand. If it isn't genuine, send it back.</li>
        <li>Not paying for a card or sending it back within 7 days ends your part in the test.</li>
      </ul>
    </section>
  )
}

function Contributing() {
  return (
    <section>
      <h2>Sharing graded cards</h2>
      <ul>
        <li>You share photos of cards from before they were graded, with the grade each one got. They help us check our condition estimates.</li>
        <li>You don't have to sell anything. If you do post a card for sale, the seller terms apply to it too.</li>
      </ul>
    </section>
  )
}

function WrongAndWho() {
  return (
    <>
      <section>
        <h2>If something goes wrong</h2>
        <p>
          If buyer and seller disagree about whether a card matches its photos, Zach decides from the photos and what each of you says. We
          can also withdraw an offer or cancel a deal that went wrong, and the app shows you why.
        </p>
      </section>
      <section id="contact">
        <h2>Who runs this</h2>
        <p>
          Rawmark is a small closed test run by Zach, a Pokémon card collector. We don't buy your card, hold it or handle the money: a buyer
          on Rawmark pays you directly.
        </p>
        <p>
          Reach us at <a href={`mailto:${CONTACT}`}>{CONTACT}</a>.
        </p>
      </section>
    </>
  )
}

/** The guide, shown whole on its page, and for one kind of member before their terms. */
export function HowItWorksGuide({ only }: { only?: GuideFor }) {
  return (
    <>
      <TheTest />
      {(!only || only === 'seller') && <Selling />}
      {(!only || only === 'buyer') && <Buying />}
      {(!only || only === 'contributor') && <Contributing />}
      <WrongAndWho />
    </>
  )
}

/** The terms the signed-in member agreed to, one link per org. */
function TermsLinks() {
  const api = useApi()
  const { data: me } = useLoad(() => api<Me>('GET', '/api/me'), [api])
  const orgs = me?.memberships.filter((m) => TERMS_LABEL[m.orgType]) ?? []
  return (
    <ul>
      {orgs.map((m) => (
        <li key={m.orgId}>
          <Link to={`/orgs/${m.orgId}/terms`}>
            Read your {TERMS_LABEL[m.orgType]}
            {orgs.length > 1 && ` for ${m.orgName}`}
          </Link>
        </li>
      ))}
    </ul>
  )
}

/** The permanent page, linked from the header and open without logging in. */
export function HowItWorksPage() {
  const { isAuthenticated } = useAuth0()
  const { hash } = useLocation()

  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView()
  }, [hash])

  return (
    <div className="guide">
      <h1>How it works</h1>
      <HowItWorksGuide />
      <section>
        <h2>The terms</h2>
        <p>This page is the short version. The terms you accept in the app are what count.</p>
        {isAuthenticated ? <TermsLinks /> : <p>You'll read them in full before you agree to them, and can read them again here once you've joined.</p>}
      </section>
    </div>
  )
}
