import { expect, test, type Browser, type Page, type TestInfo } from '@playwright/test'
import { cardPhoto, daysAgo, dollars, expectNoSideScroll, signIn } from './helpers'

// The closed beta's whole flow, as the admin, a collector and a buyer, each in their own browser at the project's
// viewport: invites, seller terms, a collection, a card and its photos, identity, condition and comps (CSV), an offer,
// posting it, buyer terms, buying it (label from the fake shipping provider), then what happens after the sale: shipped,
// arrived, a payment chased and made, or a return, each seen from both sides and by the admin, then the track record.
// Selectors are roles and labels where the page has them, so copy changes (the Rawmark rename) don't break it.

type Card = { name: string; number: string; set: string; released: string }

const CARD: Card = { name: 'Pikachu VMAX', number: '188/185', set: 'Vivid Voltage', released: '2020-11-13' }
const RETURNED_CARD: Card = { name: 'Zacian V', number: '195/202', set: 'Sword & Shield', released: '2020-02-07' }

/** Three fresh sales at each of PSA 8, 9 and 10: enough for an offer at confidence C (audit finding 1, option B). */
function compsCsv(card: Card, tag: string): string {
  const prices: Record<number, number[]> = { 10: [310, 295, 300], 9: [165, 150, 160], 8: [95, 105, 100] }
  const rows = Object.entries(prices).flatMap(([grade, sold]) =>
    sold.map((price, i) =>
      [card.name, card.set, card.number, 'PSA', grade, price.toFixed(2), daysAgo(3 + 7 * i), 'AUCTION', 'E2E', `https://example.test/e2e/${tag}/${grade}-${i}`].join(','),
    ),
  )
  return ['card,set,number,grader,grade,price,sold_on,listing_type,marketplace,url', ...rows].join('\n')
}

/** The admin's "Deliveries" and "Price paid" counts on the offer track record. */
async function trackRecord(admin: Page) {
  await admin.goto('/admin/offers')
  const bought = await admin.getByRole('row', { name: /^Bought \d/ }).getByRole('cell').innerText()
  const prices = await admin.getByRole('row', { name: /^Below \/ at \/ above the offer/ }).getByRole('cell').innerText()
  const [taken, labelled] = bought.match(/\d+/g)!.map(Number)
  const [below, at, above] = prices.match(/\d+/g)!.map(Number)
  return { taken, labelled, below, at, above }
}

async function newOrgInvite(admin: Page, name: string, type: 'COLLECTOR' | 'BUYER'): Promise<string> {
  await admin.goto('/admin')
  await admin.getByLabel('Org name').fill(name)
  await admin.getByLabel('Org type').selectOption(type)
  await admin.getByRole('button', { name: 'Create' }).click()
  const row = admin.getByRole('listitem').filter({ has: admin.getByRole('link', { name, exact: true }) })
  await row.getByRole('button', { name: /invite link/i }).click()
  const link = await row.getByLabel('Invite link').inputValue()
  return new URL(link).pathname
}

async function fillAddress(page: Page, name: string, handle?: string) {
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByLabel('Street address').fill('1 Main St')
  await page.getByLabel('City').fill('Springfield')
  await page.getByLabel('State').fill('IL')
  await page.getByLabel('ZIP code').fill('62701')
  if (handle) {
    await page.getByLabel('Pay me by').selectOption('VENMO')
    await page.getByLabel('Your payment details').fill(handle)
  }
  await page.getByRole('button', { name: /^save/i }).click()
}

/**
 * Everything up to the sale: the two orgs, the collector's card with its photos, the admin's review, the offer, and the
 * buyer taking it. The admin is signed in already, so a test can read the track record first.
 */
async function sellACard(browser: Browser, testInfo: TestInfo, admin: Page, card: Card, tag: string, handle: string) {
  const label = `${card.name} ${card.number} (${tag})`
  const collectorInvite = await newOrgInvite(admin, `E2E Collector ${tag}`, 'COLLECTOR')
  const buyerInvite = await newOrgInvite(admin, `E2E Buyer ${tag}`, 'BUYER')

  // 1. The collector joins from the invite and accepts the seller terms.
  const collector = await signIn(browser, testInfo, `collector-${tag}@e2e.test`, collectorInvite)
  await collector.getByRole('button', { name: /accept invite/i }).click()
  await expect(collector.getByRole('heading', { level: 1, name: 'How it works' })).toBeVisible()
  await expectNoSideScroll(collector)
  await collector.getByRole('button', { name: /continue to the terms/i }).click()
  await collector.getByRole('button', { name: /i agree/i }).click()

  // 2. Straight to adding a card (a binder is waiting), and its front and back photos through the file inputs.
  await expect(collector).toHaveURL(/\/$/)
  await expect(collector.getByRole('heading', { level: 1, name: 'Your cards' })).toBeVisible()
  await collector.getByLabel('Card name and number').fill(label)
  await collector.getByRole('button', { name: /add card/i }).click()
  await expect(collector.getByRole('heading', { level: 1, name: label })).toBeVisible()
  await expect(collector).toHaveTitle(`${label} · Rawmark`)
  const cardUrl = collector.url()
  for (const side of ['front', 'back'] as const) {
    await collector.getByLabel(`Choose a ${side} photo`).setInputFiles(await cardPhoto(collector, side))
    await expect(collector.getByRole('img', { name: new RegExp(`^${side} photo you took`, 'i') })).toBeVisible()
  }
  await expect(collector.getByText(/all required photos passed/i)).toBeVisible()
  await expectNoSideScroll(collector)

  // 3. The admin identifies the card by hand, imports its comps from a CSV, prices it and sets its condition.
  await admin.goto('/admin/review')
  await admin.getByRole('link', { name: label }).click()
  await admin.getByRole('button', { name: /enter by hand/i }).click()
  await admin.getByLabel('Name as printed').fill(card.name)
  await admin.getByLabel('Number', { exact: true }).fill(card.number)
  await admin.getByLabel('Set name').fill(card.set)
  await admin.getByRole('textbox', { name: 'Set release date' }).fill(card.released)
  await admin.getByLabel('Variant').selectOption('HOLO')
  await admin.getByRole('button', { name: /confirm identity/i }).click()
  await expect(admin.getByText(`${card.set} ${card.number}`, { exact: false }).first()).toBeVisible()

  await admin.locator('summary', { hasText: /csv/i }).click()
  await admin.getByLabel('Comps CSV file').setInputFiles({ name: 'comps.csv', mimeType: 'text/csv', buffer: Buffer.from(compsCsv(card, tag)) })
  await admin.getByRole('button', { name: /^import 9 sold comps/i }).click()
  await expect(admin.getByRole('status').filter({ hasText: /saved 9/i })).toBeVisible()
  await admin.getByRole('button', { name: /refresh prices/i }).click()
  await expect(admin.getByRole('heading', { level: 3, name: /^PSA/ })).toBeVisible()

  await admin.locator('summary', { hasText: /adjust/i }).click()
  for (const [grade, percent] of [['8', '30'], ['9', '50'], ['10', '20']]) {
    await admin.getByLabel(`Grade ${grade}, percent`).fill(percent)
  }
  await admin.getByLabel('Confidence', { exact: true }).selectOption('B')
  await admin.getByLabel('Why', { exact: true }).fill('E2E: set by hand from the photos')
  await admin.getByRole('button', { name: /save version/i }).click()
  await expect(admin.getByText(/offer \$[\d,]+\.\d{2} at confidence/i)).toBeVisible()

  // Identified, reviewed and priced: nothing left to do on it in the queue.
  await admin.goto('/admin/review')
  await expect(admin.getByRole('heading', { name: 'Review queue' })).toBeVisible()
  await expect(admin.getByText(/need comps/i)).toHaveCount(0)

  // 4. The collector gets the offer and posts it, after saying where it ships from and how they get paid.
  await collector.goto(cardUrl)
  await collector.getByRole('button', { name: /get an offer/i }).click()
  await fillAddress(collector, 'E2E Seller', handle)
  // The offer shows its evidence: what each grade recently sold for, and the confidence in words (UX review finding 11).
  await expect(collector.getByText(/^(Fairly|Less) sure, because the photos leave its condition less certain/)).toBeVisible()
  await collector.locator('summary', { hasText: 'How we priced it' }).click()
  await expect(collector.getByRole('columnheader', { name: 'Recently sold for' })).toBeVisible()
  await expect(collector.getByRole('row', { name: /^PSA 9 / })).toContainText(/\$1\d\d\.\d\d \(at least \d+ sales, 90 days\)/) // 3 sales a run; the phone and desktop runs share a database
  await expectNoSideScroll(collector)
  const sell = collector.getByRole('button', { name: /^sell for \$/i })
  const price = dollars(await sell.innerText())
  await sell.click()
  await collector.getByRole('button', { name: /^confirm: post for \$/i }).click()
  await expect(collector.getByRole('button', { name: /withdraw/i })).toBeVisible()
  await expectNoSideScroll(collector)

  // The home page has nothing for the seller while it waits for a buyer.
  await collector.goto('/')
  await expect(collector.getByRole('heading', { level: 1, name: 'Your cards' })).toBeVisible()
  await expect(collector.getByRole('heading', { name: 'Needs you' })).toHaveCount(0)

  // 5. The buyer joins, accepts the buyer terms, sets where cards ship to and buys the listing; the fake provider sells the label.
  const buyer = await signIn(browser, testInfo, `buyer-${tag}@e2e.test`, buyerInvite)
  await buyer.getByRole('button', { name: /accept invite/i }).click()
  await buyer.getByRole('button', { name: /continue to the terms/i }).click()
  await buyer.getByRole('button', { name: /i agree/i }).click()
  // The market asks where cards ship to before it shows anything to buy (UX review finding 8).
  await buyer.getByRole('link', { name: /cards for sale/i }).click()
  await expect(buyer.getByRole('heading', { level: 1, name: 'Where should cards ship to?' })).toBeVisible()
  await expect(buyer.getByRole('button', { name: 'Buy', exact: true })).toHaveCount(0)
  await fillAddress(buyer, 'E2E Buyer')
  const listing = buyer.locator('section').filter({ has: buyer.getByRole('heading', { name: card.name }) })
  await expect(listing).toHaveCount(1)
  await expect(listing.getByRole('img')).toHaveCount(2) // the current front and back
  await expect(listing.getByText('Most likely PSA 9 (50%)')).toBeVisible()
  await expect(buyer.getByText(handle)).toHaveCount(0) // nobody sees it before buying
  await expectNoSideScroll(buyer)
  // The photos open full screen, as the seller took them.
  await listing.getByRole('button', { name: /^Back photo/ }).click()
  const viewer = buyer.getByRole('dialog', { name: 'Photos' })
  await expect(viewer.getByText('Back (2 of 2)')).toBeVisible()
  await expect(viewer.getByRole('img', { name: 'Back photo, full size' })).toBeVisible()
  expect(await viewer.getByRole('img', { name: 'Back photo, full size' }).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
  await viewer.getByRole('button', { name: 'Close' }).click()
  await expect(viewer).toHaveCount(0)
  await listing.getByRole('button', { name: 'Buy', exact: true }).click()
  // Postage and insurance before committing, quoted for the buyer's address, with no label bought for it.
  await expect(listing.getByText(/^Postage and insurance: about \$[\d.]+ \(USPS GroundAdvantage \$5\.10, plus \$[\d.]+ to insure it/)).toBeVisible()
  await expect(listing.getByText(/within 7 days of it arriving/)).toBeVisible()
  await expectNoSideScroll(buyer)
  const confirmBuy = listing.getByRole('button', { name: /^confirm: buy for \$/i })
  expect(dollars(await confirmBuy.innerText())).toBe(price)
  await confirmBuy.click()
  await expect(buyer.getByRole('status')).toBeVisible()

  // The seller's home page says the label's ready, and by when to ship.
  await collector.reload()
  const needs = collector.getByRole('region', { name: 'Needs you' })
  await expect(needs.getByRole('link', { name: label })).toBeVisible()
  await expect(needs.getByText(/^Ship by /)).toBeVisible()
  await expectNoSideScroll(collector)
  await needs.getByRole('link', { name: label }).click()
  await expect(collector.getByRole('heading', { level: 1, name: label })).toBeFocused()
  await expect(collector.getByText(/^Ship by /)).toBeVisible()

  await buyer.getByRole('link', { name: /your deals/i }).click()
  const deal = buyer.locator('section').filter({ has: buyer.getByRole('heading', { name: card.name }) })
  await expect(deal.getByText(/Tracking 9400/)).toBeVisible()
  return { collector, buyer, cardUrl, price, deal }
}

/** The seller says it's with the carrier: the label goes, the tracking stays. */
async function ship(collector: Page, cardUrl: string) {
  await collector.goto(cardUrl)
  await expect(collector.getByRole('link', { name: /prepaid label/i })).toBeVisible()
  await collector.getByRole('button', { name: /i've shipped it/i }).click()
  await collector.getByRole('button', { name: /^confirm: it's shipped/i }).click()
  await expect(collector.getByText('Shipped', { exact: true }).first()).toBeVisible()
  await expect(collector.getByRole('link', { name: /prepaid label/i })).toHaveCount(0)
  await expect(collector.getByText(/Tracking 9400/)).toBeVisible()
}

test('a raw card goes from photos to an offer, a sale, its arrival and its payment', async ({ browser }, testInfo) => {
  const tag = `${testInfo.project.name}-${Date.now().toString(36)}`
  const handle = `@seller-${tag}`
  const admin = await signIn(browser, testInfo, `admin-${tag}@e2e.test`)
  const before = await trackRecord(admin)
  const { collector, buyer, cardUrl, price, deal } = await sellACard(browser, testInfo, admin, CARD, tag, handle)

  // The buyer is told how to pay as soon as they've bought, and the seller how they'll be paid.
  await expect(deal.getByText(handle)).toBeVisible()
  await expect(deal.getByLabel(/arrived as shown/i)).toHaveCount(0)
  await ship(collector, cardUrl)
  await expect(collector.getByText(/7 days to pay you/)).toBeVisible()

  // Shipped, so nothing's left on the seller's home page.
  await collector.goto('/')
  await expect(collector.getByRole('heading', { level: 1, name: 'Your cards' })).toBeVisible()
  await expect(collector.getByRole('heading', { name: 'Needs you' })).toHaveCount(0)
  await collector.goto(cardUrl)

  // 6. The buyer records the arrival, and pays later. The form waits for the seller to ship, so the page is reloaded first.
  await buyer.reload()
  await deal.getByRole('button', { name: 'It arrived' }).click()
  await deal.getByLabel(/arrived as shown/i).check()
  await deal.getByRole('button', { name: 'Record' }).click()
  await deal.getByRole('button', { name: /^confirm: record/i }).click()
  await expect(deal.getByText(/^Pay \$/)).toBeVisible()
  await expectNoSideScroll(buyer)

  // The seller sees the same pay-by date, and hasn't been paid, so they say so. It reaches the admin.
  await collector.reload()
  await expect(collector.getByText(/^Arrived on/)).toBeVisible()
  await collector.goto('/')
  await expect(collector.getByRole('region', { name: 'Needs you' }).getByText(/^Buyer pays by /)).toBeVisible()
  await collector.goto(cardUrl)
  await collector.getByRole('button', { name: "I haven't been paid" }).click()
  await collector.getByLabel(/anything we should know/i).fill('Nothing in my Venmo yet')
  await collector.getByRole('button', { name: "Confirm: I haven't been paid" }).click()
  await expect(collector.getByText(/Zach will be in touch/)).toBeVisible()
  await expectNoSideScroll(collector)

  await admin.goto('/admin')
  await expect(admin.getByRole('link', { name: /offers and deals \(\d+ need you\)/i })).toBeVisible()
  await admin.goto('/admin/deals')
  const raised = admin.getByRole('region', { name: `Needs attention: ${CARD.name}` }).filter({ hasText: 'Nothing in my Venmo yet' })
  await expect(raised.getByText(/haven't been paid/)).toBeVisible()
  await raised.getByRole('button', { name: 'Close' }).click()
  await raised.getByLabel('Close: reason').fill('Spoke to the buyer: paying today')
  await raised.getByRole('button', { name: 'Close' }).click()
  await expect(raised).toHaveCount(0)

  // The buyer sees it too, pays and records it; the amount is the offer's.
  await buyer.reload()
  await expect(deal.getByText(/Spoke to the buyer: paying today/)).toBeVisible()
  expect(Number(await deal.getByLabel('Price paid').inputValue())).toBe(price)
  await deal.getByRole('button', { name: "I've paid" }).click()
  await deal.getByRole('button', { name: /^confirm: record/i }).click()
  await expect(deal.getByText(/you recorded paying/i)).toBeVisible()
  await expect(deal.getByRole('button', { name: "I've paid" })).toHaveCount(0)
  await expectNoSideScroll(buyer)

  // Only the seller's word closes it.
  await collector.reload()
  await expect(collector.getByText(/Did you get it\?/)).toBeVisible()
  await collector.getByRole('button', { name: 'I got paid', exact: true }).click()
  await collector.getByRole('button', { name: 'Confirm: I got paid' }).click()
  await expect(collector.getByText(/Nothing more to do/).first()).toBeVisible()
  await expect(collector.getByRole('button', { name: 'I got paid', exact: true })).toHaveCount(0)

  await buyer.reload()
  await expect(deal.getByText(/seller confirmed they got paid/i)).toBeVisible()

  // The buyer says what grade it came back with; the admin sees it against the offer's prediction.
  const cert = crypto.randomUUID().replaceAll('-', '').slice(0, 16)
  await expect(deal.getByText('What happened to this card?')).toBeVisible()
  await deal.getByRole('radio', { name: 'I had it graded' }).check()
  await deal.getByRole('spinbutton', { name: 'Grade' }).fill('8.5')
  await deal.getByRole('textbox', { name: 'Cert number' }).fill(cert)
  await deal.getByRole('button', { name: 'Record what happened' }).click()
  await deal.getByRole('button', { name: 'Confirm: record what happened' }).click()
  await expect(deal.getByText(new RegExp(`You said it came back PSA 8\\.5, cert ${cert}`))).toBeVisible()
  await expectNoSideScroll(buyer)
  await admin.goto('/admin/deal-outcomes')
  const graded = admin.getByRole('region', { name: CARD.name }).filter({ hasText: cert })
  await expect(graded.getByText(/Predicted median/)).toBeVisible()
  await expect(graded.getByText(/Counts as 8/)).toBeVisible()

  // 7. The track record counts one more card bought, labelled and paid at the offer.
  const after = await trackRecord(admin)
  expect(after.taken).toBe(before.taken + 1)
  expect(after.labelled).toBe(before.labelled + 1)
  expect(after.at).toBe(before.at + 1)
  expect([after.below, after.above]).toEqual([before.below, before.above])

  await Promise.all([admin, collector, buyer].map((p) => p.context().close()))
})

test('a card returned as not genuine goes to the admin, comes back and gets a new offer', async ({ browser }, testInfo) => {
  const tag = `${testInfo.project.name}-back-${Date.now().toString(36)}`
  const handle = `@seller-${tag}`
  const admin = await signIn(browser, testInfo, `admin-${tag}@e2e.test`)
  const before = await trackRecord(admin)
  const { collector, buyer, cardUrl, deal } = await sellACard(browser, testInfo, admin, RETURNED_CARD, tag, handle)
  await ship(collector, cardUrl)
  await buyer.reload()

  // "Not genuine" can't go through without a note, and asks in plain words.
  await deal.getByRole('button', { name: 'It arrived' }).click()
  await deal.getByLabel('Sending it back').check()
  await deal.getByLabel('Return reason').selectOption('NOT_GENUINE')
  await expect(deal.getByLabel('Note')).toHaveAttribute('required', '')
  await deal.getByLabel('Note').fill('The back is the wrong shade of blue')
  await deal.getByRole('button', { name: 'Record' }).click()
  await expect(deal.getByText(/they see it with your note/)).toBeVisible()
  await deal.getByRole('button', { name: "Confirm: it isn't genuine" }).click()
  await expect(deal.getByText('Returned: not genuine.')).toBeVisible()
  await expect(deal.getByText(/Send it back tracked/)).toBeVisible()
  await expect(deal.getByText(handle)).toHaveCount(0) // nothing is owed any more
  await expectNoSideScroll(buyer)

  // The seller sees the stage, the buyer's words and what comes next, with no label and no shipping steps.
  await collector.reload()
  await expect(collector.getByText('Returned: not genuine')).toBeVisible()
  await expect(collector.getByText(/wrong shade of blue/)).toBeVisible()
  await expect(collector.getByText(/What happens next/)).toBeVisible()
  await expect(collector.getByRole('link', { name: /prepaid label/i })).toHaveCount(0)
  await expectNoSideScroll(collector)

  // Back with the seller, but a card called fake waits for the admin before it can go out again.
  await collector.getByRole('button', { name: "The card's back with me" }).click()
  await collector.getByRole('button', { name: "Confirm: it's back" }).click()
  await expect(collector.getByText(/we review that before it can get a new offer/).first()).toBeVisible()
  await expect(collector.getByRole('button', { name: 'Get a new offer' })).toHaveCount(0)

  await admin.goto('/admin/deals')
  const review = admin.getByRole('region', { name: `Needs attention: ${RETURNED_CARD.name}` }).filter({ hasText: 'wrong shade of blue' })
  await expect(review.getByText(/can't get a new offer until you close this/)).toBeVisible()
  await review.getByRole('button', { name: 'Close' }).click()
  await review.getByLabel('Close: reason').fill('Checked the photos: the colour is within print variation')
  await review.getByRole('button', { name: 'Close' }).click()
  await expect(review).toHaveCount(0)

  // Now it can get a new offer, and go on sale again.
  await collector.reload()
  await expect(collector.getByText(/the colour is within print variation/)).toBeVisible()
  await collector.getByRole('button', { name: 'Get a new offer' }).click()
  await collector.getByRole('button', { name: /^sell for \$/i }).click()
  await collector.getByRole('button', { name: /^confirm: post for \$/i }).click()
  // Off the market again, so the next run's buyer sees only its own card.
  await collector.getByRole('button', { name: /withdraw/i }).click()
  await collector.getByRole('button', { name: /^confirm: take it off/i }).click()
  await expect(collector.getByText(/your last posting was withdrawn/i)).toBeVisible()

  // The track record counts the return, and the card's second listing separately.
  const after = await trackRecord(admin)
  expect(after.taken).toBe(before.taken + 1)
  expect(after.at).toBe(before.at)

  await Promise.all([admin, collector, buyer].map((p) => p.context().close()))
})
