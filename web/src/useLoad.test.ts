import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useLoad, usePoll, usePollBackoff } from './useLoad'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

describe('useLoad', () => {
  it("doesn't let a slow response for old deps overwrite the newer one", async () => {
    const pending = { a: deferred<string>(), b: deferred<string>() }
    const { result, rerender } = renderHook(({ id }: { id: 'a' | 'b' }) => useLoad(() => pending[id].promise, [id]), {
      initialProps: { id: 'a' },
    })

    rerender({ id: 'b' })
    await act(async () => pending.b.resolve('card b'))
    await act(async () => pending.a.resolve('card a'))

    expect(result.current.data).toBe('card b')
  })

  it('drops the previous data as soon as the deps change', async () => {
    const next = deferred<string>()
    const { result, rerender } = renderHook(
      ({ id }: { id: string }) => useLoad(() => (id === 'a' ? Promise.resolve('card a') : next.promise), [id]),
      { initialProps: { id: 'a' } },
    )
    await waitFor(() => expect(result.current.data).toBe('card a'))

    rerender({ id: 'b' })
    expect(result.current.data).toBeNull()

    await act(async () => next.resolve('card b'))
    expect(result.current.data).toBe('card b')
  })

  it('clears the error when a reload starts', async () => {
    let fail = true
    const { result } = renderHook(() => useLoad(() => (fail ? Promise.reject(new Error('boom')) : Promise.resolve('ok')), []))
    await waitFor(() => expect(result.current.error).toBe('boom'))

    fail = false
    act(() => result.current.reload())
    expect(result.current.error).toBeNull()
    await waitFor(() => expect(result.current.data).toBe('ok'))
  })
})

describe('usePollBackoff', () => {
  it('waits longer each time, stops when inactive, and gives up after the limit', () => {
    vi.useFakeTimers()
    const reload = vi.fn()
    const { result, rerender, unmount } = renderHook(({ active }) => usePollBackoff(active, reload, 1000, 4000, 20000), {
      initialProps: { active: true },
    })
    vi.advanceTimersByTime(1000) // first at 1s
    expect(reload).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1500) // then 1.5s later
    expect(reload).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(2250) // then 2.25s
    expect(reload).toHaveBeenCalledTimes(3)
    expect(result.current).toBe(false)
    act(() => {
      vi.advanceTimersByTime(60000)
    })
    expect(result.current).toBe(true)
    const calls = reload.mock.calls.length
    vi.advanceTimersByTime(60000)
    expect(reload).toHaveBeenCalledTimes(calls)
    rerender({ active: false })
    vi.advanceTimersByTime(60000)
    expect(reload).toHaveBeenCalledTimes(calls)
    unmount()
    vi.useRealTimers()
  })
})

describe('usePoll', () => {
  it('reloads every interval while active, and stops when it is not', () => {
    vi.useFakeTimers()
    const reload = vi.fn()
    const { rerender, unmount } = renderHook(({ active }) => usePoll(active, reload, 2000), { initialProps: { active: true } })

    vi.advanceTimersByTime(6000)
    expect(reload).toHaveBeenCalledTimes(3)
    rerender({ active: false })
    vi.advanceTimersByTime(6000)
    expect(reload).toHaveBeenCalledTimes(3)
    unmount()
    vi.useRealTimers()
  })
})
