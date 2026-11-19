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
    // 2×, not more: a corner tapped on stays in the box with its edge line.
    expect(screen.getAllByRole('img')[0].style.transform).toBe('scale(2)')

    await user.click(screen.getByRole('button', { name: /Zoom out of Front crop/ }))
    expect(zoom).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getAllByRole('img')[0].style.transformOrigin).toBe('')
    expect(screen.getAllByRole('img')[0].style.transform).toBe('')
  })

  it('zooms on the middle from the keyboard', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={photos} />)
    await user.click(screen.getByText('Surface view'))

    screen.getByRole('button', { name: /Zoom in on Back crop/ }).focus()
    await user.keyboard('{Enter}')
    expect(screen.getAllByRole('img')[1].style.transformOrigin).toBe('50% 50%')
  })

  it('shows the wide crop when there is one, so the edges show past the card', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={[{ ...photos[0], wideUrl: 'https://img.example/front-wide.jpg' }, photos[1]]} />)
    await user.click(screen.getByText('Surface view'))
    expect(screen.getAllByRole('img').map((i) => i.getAttribute('src'))).toEqual(['https://img.example/front-wide.jpg', 'https://img.example/back.jpg'])
  })

  it('speaks plainly to a collector, and says it changes nothing about the offer', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel audience="collector" photos={photos} />)
    expect(screen.queryByText('Surface view')).not.toBeInTheDocument()
    await user.click(screen.getByText('Take a closer look'))
    expect(screen.getByText(/doesn't change your offer, and nothing is saved or sent/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Zoom in on Front photo/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Edges' }))
    expect(screen.getByText(/whitening on the edges and corners/)).toBeInTheDocument()
    expect(screen.queryByText(/crop/i)).not.toBeInTheDocument()
  })

  it('offers Clean back only when a back has one, and shows it on the back alone', async () => {
    const user = userEvent.setup()
    const withClean: ReviewPhoto[] = [
      { ...photos[0], cleanUrl: 'https://img.example/front-clean.jpg' },
      { ...photos[1], cleanUrl: 'https://img.example/back-clean.jpg' },
    ]
    render(<SurfacePanel photos={withClean} />)
    await user.click(screen.getByText('Surface view'))
    await user.click(screen.getByRole('button', { name: 'Clean back' }))

    const images = screen.getAllByRole('img')
    // The front never shows a clean image, even one sent by mistake: it stays the plain crop.
    expect(images.map((i) => i.getAttribute('src'))).toEqual(['https://img.example/front-crop.jpg', 'https://img.example/back-clean.jpg'])
    expect(images.map((i) => i.dataset.mode)).toEqual(['off', 'clean'])
    expect(screen.getByLabelText('Filter strength')).toBeDisabled()
    expect(screen.getByText(/printed back design subtracted/)).toBeInTheDocument()
    expect(screen.getByText(/A viewing aid only/)).toBeInTheDocument()
  })

  it('has no Clean back button when no back has a clean image', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel photos={[{ ...photos[0], cleanUrl: 'https://img.example/front-clean.jpg' }, photos[1]]} />)
    await user.click(screen.getByText('Surface view'))
    expect(screen.queryByRole('button', { name: 'Clean back' })).not.toBeInTheDocument()
  })

  it('tells a collector Clean back takes away the shared printing', async () => {
    const user = userEvent.setup()
    render(<SurfacePanel audience="collector" photos={[photos[0], { ...photos[1], cleanUrl: 'https://img.example/back-clean.jpg' }]} />)
    await user.click(screen.getByText('Take a closer look'))
    await user.click(screen.getByRole('button', { name: 'Clean back' }))
    expect(screen.getByText(/Takes away the printing every card back shares/)).toBeInTheDocument()
  })

  it('shows nothing without front or back photos', () => {
    const { container } = render(<SurfacePanel photos={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
