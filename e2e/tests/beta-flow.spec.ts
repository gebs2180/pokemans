import { expect, test, type Page } from '@playwright/test'
import { cardPhoto, daysAgo, dollars, expectNoSideScroll, signIn } from './helpers'

// The closed beta's whole flow, as the admin, a collector and a buyer, each in their own browser at the project's
// viewport: invites, seller terms, a collection, a card and its photos, identity, condition and comps (CSV), an offer,
// posting it, buyer terms, buying it (label from the fake shipping provider), recording the arrival, the track record.
// Selectors are roles and labels where the page has them, so copy changes (the Rawmark rename) don't break it.

const CARD = { name: 'Pikachu VMAX', number: '188/185', set: 'Vivid Voltage', released: '2020-11-13' }

/** Three fresh sales at each of PSA 8, 9 and 10: enough for an offer at confidence C (audit finding 1, option B). */
function compsCsv(tag: string): string {
  const prices: Record<number, number[]> = { 10: [310, 295, 300], 9: [165, 150, 160], 8: [95, 105, 100] }
  const rows = Object.entries(prices).flatMap(([grade, sold]) =>
    sold.map((price, i) =>
      [CARD.name, CARD.set, CARD.number, 'PSA', grade, price.toFixed(2), daysAgo(3 + 7 * i), 'AUCTION', 'E2E', `https://example.test/e2e/${tag}/${grade}-${i}`].join(','),
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

async function fillAddress(page: Page, name: string) {
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByLabel('Street address').fill('1 Main St')
  await page.getByLabel('City').fill('Springfield')
  await page.getByLabel('State').fill('IL')
  await page.getByLabel('ZIP code').fill('62701')
  await page.getByRole('button', { name: /save address/i }).click()
}

test('a raw card goes from photos to an offer, a sale and its arrival', async ({ browser }, testInfo) => {
  const tag = `${testInfo.project.name}-${Date.now().toString(36)}`
  const label = `${CARD.name} ${CARD.number} (${tag})`

  const admin = await signIn(browser, testInfo, `admin-${tag}@e2e.test`)
  const before = await trackRecord(admin)
  const collectorInvite = await newOrgInvite(admin, `E2E Collector ${tag}`, 'COLLECTOR')
  const buyerInvite = await newOrgInvite(admin, `E2E Buyer ${tag}`, 'BUYER')

  // 1. The collector joins from the invite and accepts the seller terms.
  const collector = await signIn(browser, testInfo, `collector-${tag}@e2e.test`, collectorInvite)
  await collector.getByRole('button', { name: /accept invite/i }).click()
  await collector.getByRole('button', { name: /i agree/i }).click()

  // 2. A collection, a card, and its front and back photos through the file inputs.
  await collector.getByLabel(/new collection name/i).fill('Binder 1')
  await collector.getByRole('button', { name: 'Create' }).click()
  await collector.getByRole('link', { name: 'Binder 1' }).click()
  await collector.getByLabel('Card name and number').fill(label)
  await collector.getByRole('button', { name: /add card/i }).click()
  await expect(collector.getByRole('heading', { level: 1, name: label })).toBeVisible()
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
  await admin.getByLabel('Name as printed').fill(CARD.name)
  await admin.getByLabel('Number', { exact: true }).fill(CARD.number)
  await admin.getByLabel('Set name').fill(CARD.set)
  await admin.getByRole('textbox', { name: 'Set release date' }).fill(CARD.released)
  await admin.getByLabel('Variant').selectOption('HOLO')
  await admin.getByRole('button', { name: /confirm identity/i }).click()
  await expect(admin.getByText(`${CARD.set} ${CARD.number}`, { exact: false }).first()).toBeVisible()

  await admin.locator('summary', { hasText: /csv/i }).click()
  await admin.getByLabel('Comps CSV file').setInputFiles({ name: 'comps.csv', mimeType: 'text/csv', buffer: Buffer.from(compsCsv(tag)) })
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

  // 4. The collector gets the offer and posts it, after saying where it ships from.
  await collector.goto(cardUrl)
  await collector.getByRole('button', { name: /get an offer/i }).click()
  await fillAddress(collector, 'E2E Seller')
  const sell = collector.getByRole('button', { name: /^sell for \$/i })
  const price = dollars(await sell.innerText())
  await sell.click()
  await collector.getByRole('button', { name: /^confirm: post for \$/i }).click()
  await expect(collector.getByRole('button', { name: /withdraw/i })).toBeVisible()
  await expectNoSideScroll(collector)

  // 5. The buyer joins, accepts the buyer terms, sets where cards ship to and buys the listing; the fake provider sells the label.
  const buyer = await signIn(browser, testInfo, `buyer-${tag}@e2e.test`, buyerInvite)
  await buyer.getByRole('button', { name: /accept invite/i }).click()
  await buyer.getByRole('button', { name: /i agree/i }).click()
  await fillAddress(buyer, 'E2E Buyer')
  await expect(buyer.getByRole('button', { name: /change/i })).toBeVisible()
  await buyer.getByRole('link', { name: /cards for sale/i }).click()
  const listing = buyer.locator('section').filter({ has: buyer.getByRole('heading', { name: CARD.name }) })
  await expect(listing).toHaveCount(1)
  await expect(listing.getByRole('img')).toHaveCount(2) // the current front and back
  await expectNoSideScroll(buyer)
  await listing.getByRole('button', { name: 'Buy', exact: true }).click()
  const confirmBuy = listing.getByRole('button', { name: /^confirm: buy for \$/i })
  expect(dollars(await confirmBuy.innerText())).toBe(price)
  await confirmBuy.click()
  await expect(buyer.getByRole('status')).toBeVisible()

  // 6. The buyer records the arrival at the offer price.
  await buyer.getByRole('link', { name: /your deals/i }).click()
  const deal = buyer.locator('section').filter({ has: buyer.getByRole('heading', { name: CARD.name }) })
  await expect(deal.getByText(/tracking 9400/)).toBeVisible()
  expect(Number(await deal.getByLabel('Price paid').inputValue())).toBe(price)
  await deal.getByRole('button', { name: 'Record' }).click()
  await expect(deal.getByText(/arrived/i)).toBeVisible()
  await expect(deal.getByRole('button', { name: 'Record' })).toHaveCount(0)
  await expectNoSideScroll(buyer)

  // The seller sees the label and the arrival.
  await collector.goto(cardUrl)
  await expect(collector.getByRole('link', { name: /prepaid label/i })).toBeVisible()
  await expect(collector.getByText(/arrived/i)).toBeVisible()

  // 7. The track record counts one more card bought, labelled and paid at the offer.
  const after = await trackRecord(admin)
  expect(after.taken).toBe(before.taken + 1)
  expect(after.labelled).toBe(before.labelled + 1)
  expect(after.at).toBe(before.at + 1)
  expect([after.below, after.above]).toEqual([before.below, before.above])

  await Promise.all([admin, collector, buyer].map((p) => p.context().close()))
})
