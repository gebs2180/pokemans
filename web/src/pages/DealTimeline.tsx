import type { Step, StepState } from '../dealSteps'

const STATE_WORD: Record<StepState, string> = { done: 'Done', current: 'Now', upcoming: 'Next' }

/** A vertical timeline of the steps. The tracking number is plain text: the API gives no carrier tracker link. */
export function DealTimeline({ steps, tracking }: { steps: Step[]; tracking?: string | null }) {
  return (
    <ol className="timeline" aria-label="Where this sale is">
      {steps.map((s) => (
        <li key={s.key} className={`step ${s.state}`} aria-current={s.state === 'current' ? 'step' : undefined}>
          <span className="step-dot" aria-hidden="true" />
          <span className="step-body">
            <span className="step-title">
              <span className="step-state">{STATE_WORD[s.state]}: </span>
              {s.title}
            </span>
            {s.detail && <span className="step-detail">{s.detail}</span>}
            {s.key === 'label' && tracking && <span className="step-detail">Tracking {tracking}</span>}
          </span>
        </li>
      ))}
    </ol>
  )
}
