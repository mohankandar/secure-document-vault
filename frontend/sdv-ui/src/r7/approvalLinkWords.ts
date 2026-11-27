// Task 0082: an Approve or Decline link from an e-mail opens <app>/approvals/link/<token>. The token is kept in this tab's
// session storage only until it is used or put aside, and the address becomes Approvals without it, so it is in no
// address bar, history entry or log of Vero's. Opening the link decides nothing: the page asks for one more click.

export const LINK_TOKEN_KEY = 'vero.approvalLink'

/** The token of a link's path, or null. Tokens are base64url with one dot; nothing else is taken. */
export function linkTokenFromPath(pathname: string): string | null {
  const m = /^\/approvals\/link\/([A-Za-z0-9_-]{8,400}\.[A-Za-z0-9_-]{8,200})\/?$/.exec(pathname)
  return m ? m[1] : null
}

/** Before sign-in: a link's path becomes Approvals, and its token waits in session storage. True when it was a link. */
export function adoptLinkPath(storage: Pick<Storage, 'setItem'> = window.sessionStorage): boolean {
  const token = linkTokenFromPath(window.location.pathname)
  if (!token) return false
  storage.setItem(LINK_TOKEN_KEY, token)
  window.history.replaceState(null, '', '/#/governance/approvals')
  return true
}

export type LinkView = {
  state: 'READY' | 'DECIDED' | 'ALREADY_DECIDED' | 'REFUSED'
  action: 'APPROVE' | 'DECLINE' | null
  code: string | null
  message: string | null
  decidedAction: string | null
  decidedByName: string | null
  decidedAt: string | null
}

/** The panel's title and tone for an answer. */
export function linkWords(v: Pick<LinkView, 'state' | 'action' | 'decidedAction' | 'decidedByName'>): { title: string; tone: 'ok' | 'bad' | 'warn' | '' } {
  const verb = v.action === 'DECLINE' ? 'Decline' : 'Approve'
  switch (v.state) {
    case 'READY': return { title: `From your e-mail: ${verb.toLowerCase()} this request?`, tone: '' }
    case 'DECIDED': return { title: v.decidedAction === 'DECLINED' ? 'Declined.' : 'Approved.', tone: 'ok' }
    case 'ALREADY_DECIDED': {
      const done = v.decidedAction === 'DECLINE' ? 'declined' : v.decidedAction === 'APPROVE' ? 'approved' : 'decided'
      return { title: v.decidedByName ? `Already ${done} by ${v.decidedByName}.` : `Already ${done}.`, tone: 'warn' }
    }
    default: return { title: 'This link can’t be used.', tone: 'bad' }
  }
}
