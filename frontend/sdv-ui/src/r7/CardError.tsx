import { AlertTriangle } from 'lucide-react'
import { errorWords, type ErrorWords } from './problemWords'

// Shared error pieces for every screen (task 0008, finding 24; task 0044): plain words on screen, never the raw
// error. The words come from errorWords (problemWords.ts): a refusal, not allowed, Vero's fault or no answer, with
// the code and, for Vero's faults, the reference. The reference is the server's correlation ID when it sent one,
// otherwise a short random code. The raw error is written to the browser console with the same reference, so IT can
// match the two.

/** The words for a failure; logs the raw error to the console once, next to its reference. */
export function describeFailure(what: string, error: unknown, options: { withReference?: boolean } = {}): ErrorWords {
  const words = errorWords(error, options)
  console.error(`[Vero] ${what}${words.reference ? ` (Ref ${words.reference})` : ''}`, error)
  return words
}

/** One plain line (sentence, reference, code), for places that show a message line instead of a card. */
export const failureText = (what: string, error: unknown) => describeFailure(what, error).text

/** Task 0044: an export that didn't work: the same words, and the reference whenever there is one. */
export const exportFailureText = (what: string, error: unknown) => describeFailure(what, error, { withReference: true }).text

export type Failure = { words: ErrorWords; kind?: 'load' | 'action'; /** overrides the notice's title */ title?: string }
/** Turns a caught error into what the screen may show. */
export const toFailure = (what: string, error: unknown): Failure => ({ words: describeFailure(what, error) })
/** Something did not load (shown as a card with Try again). */
export const loadFailed = (what: string, error: unknown, title?: string): Failure => ({ words: describeFailure(what, error), kind: 'load', title })
/** A change could not be made (shown as a plain notice). */
export const actionFailed = (what: string, error: unknown): Failure => ({ words: describeFailure(what, error), kind: 'action' })

/** Shows a failure the right way for its kind. */
export function FailureNotice({ failure, loadTitle, actionTitle = "The change wasn't made", onRetry }: { failure: Failure; loadTitle: string; actionTitle?: string; onRetry: () => void }) {
  return failure.kind === 'action' ? <ActionError title={failure.title ?? actionTitle} failure={failure} /> : <LoadErrorCard title={failure.title ?? loadTitle} failure={failure} onRetry={onRetry} />
}

/** A card that failed to load. "Try again" reloads only that card; the rest of the page keeps working. */
export function LoadErrorCard({ title, failure, onRetry }: { title: string; failure: Failure; onRetry: () => void }) {
  return (
    <section className="v-panel card-error" role="alert" aria-label={title}>
      {/* Canvas: the title, the words (with the code and reference), then Try again. */}
      <div className="v-panel-body stack" style={{ gap: 10 }}>
        <h3 className="card-title">{title}</h3>
        <p className="muted">{failure.words.text} The rest of the page still works.</p>
        <div className="row wrap" style={{ gap: 10, alignItems: 'center' }}>
          <button type="button" className="v-btn primary sm" onClick={onRetry}>Try again</button>
        </div>
      </div>
    </section>
  )
}

/** A change that could not be made (save, activate…). Same rules and words. */
export function ActionError({ title, failure }: { title: string; failure: Failure }) {
  return (
    <div className="v-callout warn" role="alert">
      <AlertTriangle size={18} aria-hidden />
      <div>
        <strong>{title}</strong>
        <p>{failure.words.text}</p>
      </div>
    </div>
  )
}
