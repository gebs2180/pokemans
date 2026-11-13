import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ReviewPhoto } from '../api'
import { SurfacePanel } from './SurfaceOverlay'

const photos: ReviewPhoto[] = [
  { id: 'p1', side: 'FRONT', url: 'https://img.example/front.jpg', cropUrl: 'https://img.example/front-crop.jpg', capturedAt: null },
  { id: 'p2', side: 'BACK', url: 'https://img.example/back.jpg', cropUrl: null, capturedAt: null },
]

describe('SurfacePanel', () => {
  it('stays closed until opened', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={photos} />)
    expect(screen.queryAllByRole('img')).toHaveLength(0)
    await user.click(screen.getByText('Surface view'))
    expect(screen.getAllByRole('img')).toHaveLength(2)
  })

  it('shows the plain crops first, with the slider off', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={photos} />)
    await user.click(screen.getByText('Surface view'))
    const images = screen.getAllByRole('img')
    expect(images.map((i) => i.getAttribute('src'))).toEqual(['https://img.example/front-crop.jpg', 'https://img.example/back.jpg'])
    expect(images.every((i) => i.dataset.mode === 'off' && !i.getAttribute('style'))).toBe(true)
    expect(screen.getByLabelText('Filter strength')).toBeDisabled()
  })

  it('draws the chosen filter over each crop, and turns it off at zero strength', async () => {
    const user = userEvent.setup()
    const { container } = render(<SurfacePanel photos={photos} />)
    await user.click(screen.getByText('Surface view'))

    await user.click(screen.getByRole('button', { name: 'Edges' }))
    expect(screen.getByRole('button', { name: 'Edges' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Photo' })).toHaveAttribute('aria-pressed', 'false')
    const front = screen.getAllByRole('img')[0]
    expect(front.dataset.mode).toBe('edges')
    // jsdom drops a url() filter from the style, so check the filters drawn: one per crop.
    expect(container.querySelectorAll('filter feConvolveMatrix[kernelMatrix="-1 -1 -1 -1 8 -1 -1 -1 -1"]')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Emboss' }))
    expect(screen.getAllByRole('img')[0].dataset.mode).toBe('emboss')

    fireEvent.change(screen.getByLabelText('Filter strength'), { target: { value: '0' } })
    expect(screen.getAllByRole('img')[0].dataset.mode).toBe('off')
    expect(screen.getByLabelText('Filter strength')).toHaveValue('0')
  })

  it('zooms a crop where it is tapped, and back out on a second tap', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={photos} />)
    await user.click(screen.getByText('Surface view'))

    const zoom = screen.getByRole('button', { name: /Zoom in on Front crop/ })
    zoom.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 400 }) as DOMRect
    fireEvent.click(zoom, { clientX: 50, clientY: 300, detail: 1 })
    expect(zoom).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getAllByRole('img')[0].style.transformOrigin).toBe('25% 75%')

    await user.click(screen.getByRole('button', { name: /Zoom out of Front crop/ }))
    expect(zoom).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getAllByRole('img')[0].style.transformOrigin).toBe('')
  })

  it('zooms on the middle from the keyboard', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={photos} />)
    await user.click(screen.getByText('Surface view'))

    screen.getByRole('button', { name: /Zoom in on Back crop/ }).focus()
    await user.keyboard('{Enter}')
    expect(screen.getAllByRole('img')[1].style.transformOrigin).toBe('50% 50%')
  })

  it('shows nothing without front or back photos', () => {
    const { container } = render(<SurfacePanel photos={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
