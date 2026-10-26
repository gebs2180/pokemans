import { expect, type Browser, type Page, type TestInfo } from '@playwright/test'

/** A browser of its own for one person, at the project's viewport, signed in through the mock issuer. */
export async function signIn(browser: Browser, testInfo: TestInfo, email: string, path = '/'): Promise<Page> {
  const { viewport, isMobile, hasTouch, deviceScaleFactor, baseURL, serviceWorkers } = testInfo.project.use
  const context = await browser.newContext({ viewport, isMobile, hasTouch, deviceScaleFactor, baseURL, serviceWorkers })
  const page = await context.newPage()
  await page.goto(path)
  await page.getByRole('button', { name: /log in/i }).click()
  await page.getByLabel('Email').fill(email)
  await page.getByRole('button', { name: 'Sign in' }).click()
  // Back in the app, signed in: the bar offers Log out.
  await expect(page.getByRole('button', { name: /log out/i })).toBeVisible()
  return page
}

/**
 * A photo that passes every photo check: a whole 63 × 88 card, sharp and turned a little, on a dark speckled table,
 * like the backend's own test images (QualityCheckerTest). The speckle is random, so no two photos are the same
 * image (the duplicate check compares hashes). Drawn in the page's canvas and set through the file input: no camera.
 */
export async function cardPhoto(page: Page, side: 'front' | 'back'): Promise<{ name: string; mimeType: string; buffer: Buffer }> {
  const dataUrl = await page.evaluate((side) => {
    const width = 1600
    const height = 2200
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const g = canvas.getContext('2d')!
    const table = g.createImageData(width, height)
    for (let i = 0; i < table.data.length; i += 4) {
      const v = 35 + Math.floor(Math.random() * 12)
      table.data[i] = v
      table.data[i + 1] = v
      table.data[i + 2] = v + 4
      table.data[i + 3] = 255
    }
    g.putImageData(table, 0, 0)
    const cardWidth = 900
    const cardHeight = Math.round((cardWidth * 88) / 63)
    const border = Math.round(cardWidth * 0.05)
    g.translate(800, 1100)
    g.rotate(((side === 'front' ? 4 : -3) * Math.PI) / 180)
    g.translate(-cardWidth / 2, -cardHeight / 2)
    g.fillStyle = side === 'front' ? '#f2c230' : '#2a5bb8'
    g.beginPath()
    g.roundRect(0, 0, cardWidth, cardHeight, border / 2)
    g.fill()
    const cell = 15
    for (let y = border; y < cardHeight - border; y += cell) {
      for (let x = border; x < cardWidth - border; x += cell) {
        g.fillStyle = ((x - border) / cell + (y - border) / cell) % 2 === 0 ? '#3050a0' : '#c8d0e0'
        g.fillRect(x, y, Math.min(cell, cardWidth - border - x), Math.min(cell, cardHeight - border - y))
      }
    }
    return canvas.toDataURL('image/png')
  }, side)
  return { name: `${side}.png`, mimeType: 'image/png', buffer: Buffer.from(dataUrl.split(',')[1], 'base64') }
}

/** YYYY-MM-DD, `days` ago. */
export function daysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10)
}

/** "$1,234.56" → 1234.56 */
export function dollars(text: string): number {
  const match = text.match(/\$([\d,]+\.\d{2})/)
  if (!match) throw new Error(`no dollar amount in "${text}"`)
  return Number(match[1].replace(/,/g, ''))
}

/** On a phone, the page fits the screen's width: nothing to scroll sideways (the audit checked this by hand). */
export async function expectNoSideScroll(page: Page) {
  if (!page.viewportSize() || page.viewportSize()!.width > 500) return
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, `${page.url()} scrolls sideways by ${overflow}px`).toBeLessThanOrEqual(0)
}
