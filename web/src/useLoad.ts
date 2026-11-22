import { useCallback, useEffect, useState } from 'react'
import { message } from './format'

/** Runs an async loader on mount and whenever its dependencies change; reload() runs it again. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(loader, deps)

  // New deps mean a different thing is loading: drop the old one's data and error so they're never shown for it.
  const [loaded, setLoaded] = useState(() => load)
  if (loaded !== load) {
    setLoaded(() => load)
    setData(null)
    setError(null)
  }

  useEffect(() => {
    let cancelled = false
    load()
      .then((value) => {
        if (!cancelled) setData(value)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(message(e))
      })
    return () => {
      cancelled = true
    }
  }, [load, version])

  const reload = useCallback(() => {
    setError(null)
    setVersion((v) => v + 1)
  }, [])
  return { data, error, reload }
}

/**
 * Calls {@code reload} while {@code active}, waiting longer each time (from {@code firstMs}, by half again, up to
 * {@code maxMs}), and gives up after {@code giveUpMs}. Returns true once it has given up, so the page can say so.
 */
export function usePollBackoff(active: boolean, reload: () => void, firstMs = 3000, maxMs = 15000, giveUpMs = 10 * 60 * 1000) {
  const [gaveUp, setGaveUp] = useState(false)
  useEffect(() => {
    if (!active) return
    const started = Date.now()
    let delay = firstMs
    let timer: ReturnType<typeof setTimeout>
    const tick = () => {
      if (Date.now() - started >= giveUpMs) {
        setGaveUp(true)
        return
      }
      reload()
      delay = Math.min(Math.round(delay * 1.5), maxMs)
      timer = setTimeout(tick, delay)
    }
    timer = setTimeout(tick, delay)
    return () => {
      clearTimeout(timer)
      setGaveUp(false)
    }
  }, [active, reload, firstMs, maxMs, giveUpMs])
  return gaveUp
}

/** Calls {@code reload} every {@code ms} while {@code active}, e.g. while background work is pending. */
export function usePoll(active: boolean, reload: () => void, ms = 2000) {
  useEffect(() => {
    if (!active) return
    const timer = setInterval(reload, ms)
    return () => clearInterval(timer)
  }, [active, reload, ms])
}
