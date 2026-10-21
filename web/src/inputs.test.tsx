import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { parseMoney } from './api'
import { DateInput, MoneyInput } from './inputs'

describe('parseMoney', () => {
  it('takes commas and a dollar sign, and nothing else', () => {
    expect(parseMoney('1,234.50')).toBe(1234.5)
    expect(parseMoney('$40,401')).toBe(40401)
    expect(parseMoney(' 120 ')).toBe(120)
    expect(parseMoney('12.345')).toBeNaN()
    expect(parseMoney('1e3')).toBeNaN()
    expect(parseMoney('')).toBeNaN()
  })
})

describe('MoneyInput', () => {
  it('accepts an amount with commas, and says what is wrong otherwise', async () => {
    const user = userEvent.setup()
    function Form() {
      const [v, setV] = useState('')
      return <MoneyInput value={v} onChange={setV} aria-label="Price" required />
    }
    render(<Form />)
    const input = screen.getByLabelText('Price') as HTMLInputElement

    await user.type(input, '28,750.00')
    expect(input).toHaveValue('28,750.00')
    expect(input.validity.valid).toBe(true)

    await user.clear(input)
    await user.type(input, 'abc')
    expect(input.validationMessage).toBe('Enter an amount like 1,234.50')
  })
})

describe('DateInput', () => {
  function Picker({ max, onPick }: { max?: string; onPick?: (d: string) => void }) {
    const [v, setV] = useState('2026-10-17')
    return (
      <DateInput
        value={v}
        max={max}
        onChange={(d) => {
          setV(d)
          onPick?.(d)
        }}
        aria-label="Sold on"
      />
    )
  }

  it('picks a day from the month calendar, weekends styled like any day', async () => {
    const user = userEvent.setup()
    const onPick = vi.fn()
    render(<Picker onPick={onPick} />)

    await user.click(screen.getByRole('button', { name: 'Choose sold on' }))
    expect(screen.getByText('October 2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2026-10-17' })).toHaveAttribute('aria-pressed', 'true')
    // Saturday the 10th and Wednesday the 14th get the same class.
    expect(screen.getByRole('button', { name: '2026-10-10' }).className).toBe(screen.getByRole('button', { name: '2026-10-14' }).className)

    await user.click(screen.getByRole('button', { name: '2026-10-11' }))
    expect(onPick).toHaveBeenCalledWith('2026-10-11')
    expect(screen.getByLabelText('Sold on')).toHaveValue('2026-10-11')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('refuses days after the latest date, and moves between months', async () => {
    const user = userEvent.setup()
    render(<Picker max="2026-10-20" />)

    await user.click(screen.getByRole('button', { name: 'Choose sold on' }))
    expect(screen.getByRole('button', { name: '2026-10-21' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    expect(screen.getByText('September 2026')).toBeInTheDocument()
  })

  it('says when a typed date is malformed or too late', async () => {
    const user = userEvent.setup()
    render(<Picker max="2026-10-20" />)
    const input = screen.getByLabelText('Sold on') as HTMLInputElement

    await user.clear(input)
    await user.type(input, '2026-13-01')
    expect(input.validationMessage).toBe('Enter a date as YYYY-MM-DD')
    await user.clear(input)
    await user.type(input, '2026-10-25')
    expect(input.validationMessage).toBe('Pick a date on or before 2026-10-20')
  })
})
