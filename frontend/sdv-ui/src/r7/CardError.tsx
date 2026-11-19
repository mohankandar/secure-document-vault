import { AlertTriangle } from 'lucide-react'
import { ApiRequestError } from '../api'

// Shared error pieces for the admin areas (task 0008, finding 24): plain words on screen, never the raw error.
// The reference is the server's correlation ID when it sent one, otherwise a short random code. Either way the
// raw error is written to the browser console with the same reference, so IT can match the two.

const randomRef = () => Array.from(crypto.getRandomValues(new Uint8Array(4)), (b) => b.toString(16).padStart(2, '0')).join('')

/** The reference for a failure; logs the raw error to the console once, next to that reference. */
export function failureRef(what: string, error: unknown): string {
  const ref = error instanceof ApiRequestError && error.correlationId ? error.correlationId : randomRef()
  console.error(`[Vero] ${what} (Ref ${ref})`, error)
  return ref
}

/** One plain sentence with a reference, for places that show a message line instead of a card. */
export const failureText = (what: string, error: unknown) =>
  `Vero couldn't reach the server just now. If it keeps happening, give your IT team this reference: Ref ${failureRef(what, error)}`

export type Failure = { ref: string; kind?: 'load' | 'action'; /** overrides the notice's title */ title?: string }
/** Turns a caught error into what the screen may show: only a reference. */
export const toFailure = (what: string, error: unknown): Failure => ({ ref: failureRef(what, error) })
/** Something did not load (shown as a card with Try again). */
export const loadFailed = (what: string, error: unknown, title?: string): Failure => ({ ref: failureRef(what, error), kind: 'load', title })
/** A change could not be made (shown as a plain notice). */
export const actionFailed = (what: string, error: unknown): Failure => ({ ref: failureRef(what, error), kind: 'action' })

/** Shows a failure the right way for its kind. */
export function FailureNotice({ failure, loadTitle, actionTitle = "The change wasn't made", onRetry }: { failure: Failure; loadTitle: string; actionTitle?: string; onRetry: () => void }) {
  return failure.kind === 'action' ? <ActionError title={failure.title ?? actionTitle} failure={failure} /> : <LoadErrorCard title={failure.title ?? loadTitle} failure={failure} onRetry={onRetry} />
}

/** A card that failed to load. "Try again" reloads only that card; the rest of the page keeps working. */
export function LoadErrorCard({ title, failure, onRetry }: { title: string; failure: Failure; onRetry: () => void }) {
  return (
    <section className="v-panel card-error" role="alert" aria-label={title}>
      {/* Canvas: the title, one sentence, then Try again and the reference. */}
      <div className="v-panel-body stack" style={{ gap: 10 }}>
        <h3 className="card-title">{title}</h3>
        <p className="muted">Vero couldn&apos;t reach this setting just now. The rest of the page still works. If it keeps happening, give your IT team this reference.</p>
        <div className="row wrap" style={{ gap: 10, alignItems: 'center' }}>
          <button type="button" className="v-btn primary sm" onClick={onRetry}>Try again</button>
          <span className="mono faint">Ref {failure.ref}</span>
        </div>
      </div>
    </section>
  )
}

/** A change that could not be made (save, activate…). Same rules: plain words and a reference. */
export function ActionError({ title, failure }: { title: string; failure: Failure }) {
  return (
    <div className="v-callout warn" role="alert">
      <AlertTriangle size={18} aria-hidden />
      <div>
        <strong>{title}</strong>
        <p>Vero couldn&apos;t make this change just now. Try again in a moment. If it keeps happening, give your IT team this reference: <span className="mono">Ref {failure.ref}</span></p>
      </div>
    </div>
  )
}
