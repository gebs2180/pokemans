import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Offer } from '../api'
import { hasSeenOffer } from '../offerSeen'
import { OfferReveal } from './OfferReveal'

const offer = {
  id: 'offer-1',
  price: 636.12,
  ladder: [
    { grade: '10', probability: 0.6, price: 843.12 },
    { grade: '9', probability: 0.3, price: 337.25 },
    { grade: '8', probability: 0.1, price: 290.78 },
    { grade: '7', probability: 0, price: 250 },
    { grade: 'U', probability: 0, price: 0 },
  ],
} as Offer

const reducedMotion = (reduce: boolean) =>
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce'), media: query }))

afterEach(() => vi.unstubAllGlobals())

/** jsdom has no AnimationEvent, so React listens for the prefixed name there; send both. */
const finishTurn = () => {
  const el = document.querySelector('.flip-inner')!
  fireEvent(el, new Event('animationend', { bubbles: true }))
  fireEvent(el, new Event('webkitAnimationEnd', { bubbles: true }))
}

/** The dollar amount at the end of each grade row, in cents. */
const rowCents = () =>
  [...document.querySelectorAll('.adds-up li:not(:last-child) .adds-total')].map((el) => Math.round(Number(el.textContent!.replace(/[$,]/g, '')) * 100))

describe('OfferReveal', () => {
  it('flips the first time, with the offer, how it adds up and the promise on the back', () => {
    render(<OfferReveal offer={offer} frontUrl="https://example.com/front.jpg" />)
    const reveal = screen.getByTestId('offer-reveal')
    expect(reveal).toHaveClass('flip')
    expect(reveal).toHaveTextContent('Firm offer')
    expect(reveal).toHaveTextContent('$636.12')
    expect(reveal).toHaveTextContent('Final. No re-grade on arrival.')
    expect(screen.getByRole('img', { name: 'Chance of each grade: PSA 10 60%, PSA 9 30%, PSA 8 10%' })).toBeInTheDocument()
    expect(reveal).toHaveTextContent('60% × $843.12')
    expect(reveal).toHaveTextContent('PSA 7 or lower0%, not counted$0.00')
    expect(reveal).toHaveTextContent('not a grade we\'re predicting')
  })

  it('adds the rows up to the offer exactly, the last taking the cent', () => {
    render(<OfferReveal offer={offer} />)
    expect(rowCents()).toEqual([50587, 10118, 2907])
    expect(rowCents().reduce((a, b) => a + b, 0)).toBe(63612)
  })

  it('gives the strongest colour to the most likely grade', () => {
    render(
      <OfferReveal
        offer={{ ...offer, ladder: [{ grade: '10', probability: 0.2, price: 800 }, { grade: '9', probability: 0.5, price: 300 }, { grade: '8', probability: 0.3, price: 200 }] } as Offer}
      />,
    )
    const swatches = [...document.querySelectorAll('.adds-up .swatch')].map((el) => el.className)
    expect(swatches).toEqual(['swatch seg-3', 'swatch seg-1', 'swatch seg-2'])
  })

  it('only counts as seen once the turn has finished', () => {
    render(<OfferReveal offer={offer} />)
    expect(hasSeenOffer('offer-1')).toBe(false)
    act(() => {
      finishTurn()
    })
    expect(hasSeenOffer('offer-1')).toBe(true)
    // After the turn the back is a content-sized panel, not the card shape.
    expect(screen.getByTestId('offer-reveal')).toHaveClass('reveal-static')
    expect(screen.getByTestId('offer-reveal')).toHaveTextContent('Final. No re-grade on arrival.')
  })

  it('waits until the card is mostly on screen before it turns', () => {
    let notify: (entries: Partial<IntersectionObserverEntry>[]) => void = () => {}
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(cb: typeof notify) {
          notify = cb
        }
        observe() {}
        disconnect() {}
      },
    )
    render(<OfferReveal offer={offer} />)
    const inner = document.querySelector('.flip-inner')!
    expect(inner).not.toHaveClass('play')
    act(() => notify([{ isIntersecting: true, intersectionRatio: 0.3 }]))
    expect(inner).not.toHaveClass('play')
    act(() => notify([{ isIntersecting: true, intersectionRatio: 0.7 }]))
    expect(inner).toHaveClass('play')
  })

  it('shows the same back, without the turn, on a later visit', () => {
    const first = render(<OfferReveal offer={offer} />)
    act(() => {
      finishTurn()
    })
    first.unmount()
    render(<OfferReveal offer={offer} />)
    const reveal = screen.getByTestId('offer-reveal')
    expect(reveal).toHaveClass('reveal-static')
    expect(reveal).toHaveTextContent('$636.12')
    expect(reveal).toHaveTextContent('Final. No re-grade on arrival.')
    expect(reveal).toHaveTextContent('60% × $843.12')
  })

  it('reveals a different offer again', () => {
    render(<OfferReveal offer={offer} />).unmount()
    render(<OfferReveal offer={{ ...offer, id: 'offer-2' }} />)
    expect(screen.getByTestId('offer-reveal')).toHaveClass('flip')
  })

  it('does not flip with reduced motion, but still shows it and marks it seen', () => {
    reducedMotion(true)
    render(<OfferReveal offer={offer} />)
    const reveal = screen.getByTestId('offer-reveal')
    expect(reveal).toHaveClass('reveal-static')
    expect(reveal).toHaveTextContent('$636.12')
    expect(hasSeenOffer('offer-1')).toBe(true)
  })

  it('flips when the browser says it does not mind motion', () => {
    reducedMotion(false)
    render(<OfferReveal offer={offer} />)
    expect(screen.getByTestId('offer-reveal')).toHaveClass('flip')
  })

  it('shows the static back when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    render(<OfferReveal offer={offer} />)
    expect(screen.getByTestId('offer-reveal')).toHaveClass('reveal-static')
    expect(screen.getByTestId('offer-reveal')).toHaveTextContent('$636.12')
    vi.restoreAllMocks()
  })
})
