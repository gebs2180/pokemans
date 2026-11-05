import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { PolicyContext, type Policy } from './policy'
import { Loading } from './ui'

/** Loads the policy once and renders its children only when it has it. */
export function PolicyProvider({ children }: { children: ReactNode }) {
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let live = true
    fetch('/api/policy')
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<Policy>
      })
      .then((p) => live && setPolicy(p))
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
  }, [attempt])

  const retry = useCallback(() => {
    setFailed(false)
    setAttempt((a) => a + 1)
  }, [])

  if (policy) return <PolicyContext.Provider value={policy}>{children}</PolicyContext.Provider>
  if (failed) {
    return (
      <main>
        <p className="error" role="alert">
          Rawmark couldn't reach its server.{' '}
          <button className="link" onClick={retry}>
            Try again
          </button>
        </p>
      </main>
    )
  }
  return <Loading />
}
