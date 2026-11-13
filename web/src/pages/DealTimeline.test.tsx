import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Step } from '../dealSteps'
import { DealTimeline } from './DealTimeline'

const steps: Step[] = [
  { key: 'accepted', title: 'Buyer accepted', detail: '11/2/2026', state: 'done' },
  { key: 'label', title: 'Label ready', state: 'done' },
  { key: 'ship', title: 'Ship it by 11/5/2026', state: 'current' },
  { key: 'arrive', title: 'Arrives', state: 'upcoming' },
]

describe('DealTimeline', () => {
  it('marks the current step and says each step is done, now or next', () => {
    render(<DealTimeline steps={steps} />)
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(4)
    expect(items[2]).toHaveAttribute('aria-current', 'step')
    expect(items[0]).not.toHaveAttribute('aria-current')
    expect(items[0]).toHaveTextContent('Done: Buyer accepted')
    expect(items[2]).toHaveTextContent('Now: Ship it by 11/5/2026')
    expect(items[3]).toHaveTextContent('Next: Arrives')
  })

  it('shows the tracking number as plain text, since the API gives no tracker link', () => {
    render(<DealTimeline steps={steps} tracking="9400111" />)
    expect(screen.getByText('Tracking 9400111')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
