import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RouteErrorBoundary } from './ui'

function Boom(): never {
  throw new Error('chunk 404')
}

describe('RouteErrorBoundary', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows its children when nothing fails', () => {
    render(<RouteErrorBoundary><p>Fine</p></RouteErrorBoundary>)
    expect(screen.getByText('Fine')).toBeInTheDocument()
  })

  it('replaces a crashed page with a message and a Reload button that reloads', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    render(<RouteErrorBoundary><Boom /></RouteErrorBoundary>)
    expect(screen.getByRole('alert')).toHaveTextContent("couldn't be shown")
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledOnce()
  })
})
