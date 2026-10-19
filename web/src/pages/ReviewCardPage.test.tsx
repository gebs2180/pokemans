import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CardCondition } from '../api'
import { AssessmentPanel } from './ReviewCardPage'

const card: CardCondition = {
  cardId: 'card1',
  orgId: 'org1',
  label: 'Charizard',
  enabledProviders: [],
  photos: [],
  identification: null,
  requests: [],
  predictions: [],
  assessments: [],
}

describe('AssessmentPanel', () => {
  it('only allows saving a distribution that totals 100%', async () => {
    const user = userEvent.setup()
    render(<AssessmentPanel data={card} current={null} base="/api/admin/orgs/org1/cards/card1" onChange={() => {}} />)
    await user.click(screen.getByText('Adjust (saves a new version)'))
    const save = screen.getByRole('button', { name: 'Save version' })
    expect(save).toBeDisabled()

    const nine = screen.getByLabelText('9')
    await user.clear(nine)
    await user.type(nine, '60')
    expect(save).toBeDisabled()

    const eight = screen.getByLabelText('8')
    await user.clear(eight)
    await user.type(eight, '40')
    expect(save).toBeEnabled()

    await user.type(eight, '.5')
    expect(save).toBeDisabled()
  })
})
