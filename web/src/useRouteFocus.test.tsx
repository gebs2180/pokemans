import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useState } from 'react'
import { Link, MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useRouteFocus } from './useRouteFocus'

function Shell({ children }: { children: React.ReactNode }) {
  useRouteFocus()
  return <main>{children}</main>
}

/** A page whose heading comes with its data, like most of ours. */
function Slow() {
  const [ready, setReady] = useState(false)
  useEffect(() => void setTimeout(() => setReady(true), 0), [])
  return ready ? <h1>Pikachu</h1> : <p>Loading…</p>
}

function First() {
  const navigate = useNavigate()
  return (
    <>
      <h1>List</h1>
      <Link to="/card">Open</Link>
      <button onClick={() => navigate(-1)}>Back</button>
    </>
  )
}

function renderApp() {
  render(
    <MemoryRouter initialEntries={['/']}>
      <Shell>
        <Routes>
          <Route path="/" element={<First />} />
          <Route path="/card" element={<Slow />} />
          <Route path="/other" element={<First />} />
        </Routes>
      </Shell>
    </MemoryRouter>,
  )
}

describe('useRouteFocus', () => {
  const scrollTo = vi.fn()
  beforeEach(() => {
    scrollTo.mockReset()
    vi.stubGlobal('scrollTo', scrollTo)
  })

  it('names the tab on first load without moving focus or scroll', async () => {
    renderApp()
    await waitFor(() => expect(document.title).toBe('List · Rawmark'))
    expect(scrollTo).not.toHaveBeenCalled()
    expect(document.body).toHaveFocus()
  })

  it('starts a new page at the top, focused on a heading that arrives late, and names the tab after it', async () => {
    const user = userEvent.setup()
    renderApp()
    await user.click(screen.getByRole('link', { name: 'Open' }))
    expect(await screen.findByRole('heading', { name: 'Pikachu' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Pikachu' })).toHaveFocus())
    expect(document.title).toBe('Pikachu · Rawmark')
    expect(scrollTo).toHaveBeenCalledWith(0, 0)
  })
})
