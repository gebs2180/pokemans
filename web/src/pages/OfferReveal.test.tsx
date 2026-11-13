import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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
    { grade: 'U', probability: 0, price: 0 },
  ],
} as Offer

const reducedMotion = (reduce: boolean) =>
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce'), media: query }))

describe('OfferReveal', () => {
  it('flips the first time, with the offer, the chances and the promise on the back', () => {
    render(<OfferReveal offer={offer} frontUrl="https://example.com/front.jpg" />)
    const reveal = screen.getByTestId('offer-reveal')
    expect(reveal).toHaveClass('flip')
    expect(reveal).toHaveTextContent('Firm offer')
    expect(reveal).toHaveTextContent('$636.12')
    expect(reveal).toHaveTextContent('Final. No re-grade on arrival.')
    expect(screen.getByRole('img', { name: 'Chance of each grade: PSA 10 60%, PSA 9 30%, PSA 8 10%' })).toBeInTheDocument()
    expect(screen.getAllByText('$636.12')).toHaveLength(1)
  })

  it('remembers it was seen, so the next time is a plain price', () => {
    const first = render(<OfferReveal offer={offer} />)
    expect(hasSeenOffer('offer-1')).toBe(true)
    first.unmount()
    render(<OfferReveal offer={offer} />)
    expect(screen.queryByTestId('offer-reveal')).not.toBeInTheDocument()
    expect(screen.getByText('$636.12')).toBeInTheDocument()
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

  it('shows a plain price when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    render(<OfferReveal offer={offer} />)
    expect(screen.queryByTestId('offer-reveal')).not.toBeInTheDocument()
    expect(screen.getByText('$636.12')).toBeInTheDocument()
    vi.restoreAllMocks()
  })
})
