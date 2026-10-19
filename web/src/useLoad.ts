import { useCallback, useEffect, useState } from 'react'

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
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
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
