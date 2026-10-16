import { useCallback, useEffect, useState } from 'react'

/** Runs an async loader on mount and whenever its dependencies change; reload() runs it again. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(loader, deps)

  useEffect(() => {
    let cancelled = false
    load()
      .then((value) => {
        if (!cancelled) {
          setData(value)
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : String(e))
        }
      })
    return () => {
      cancelled = true
    }
  }, [load, version])

  const reload = useCallback(() => setVersion((v) => v + 1), [])
  return { data, error, reload }
}
