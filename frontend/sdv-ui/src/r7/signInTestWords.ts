// Task 0057 (LV-16 item 2): the words of the IT Admin's read-only "Test sign-in" on the Sign-in tab.
// The checks are platform-core's (identity module); the codes it answers are shown as they are, never reworded.

/** The server's answer (POST /api/v1/admin/login-connection/test). code: platform-core's code, or null. */
export type SignInTestAnswer = {
  outcome: 'PASSED' | 'FAILED' | 'NO_CONNECTION'
  code: string | null
  message: string
  reason: string | null
  tokenChecked: boolean
  connectionId: string | null
  configurationVersion: number
  testedAt: string
}

/** What the test looked at. The audience needs a token, which only a company sign-in has. */
export function checkedWords(tokenChecked: boolean): string {
  return tokenChecked
    ? 'Checked: the login system’s discovery document, its signing keys and issuer, and your own sign-in (signature, issuer, Vero’s API audience, expiry and groups).'
    : 'Checked: the login system’s discovery document, its signing keys and issuer. The API audience is checked only with a company sign-in, so it wasn’t checked here.'
}

/** The banner's lead and tone for an answer. Anything but PASSED counts as not passed (fail closed). */
export function outcomeWords(a: Pick<SignInTestAnswer, 'outcome'>): { lead: string; tone: 'ok' | 'bad' | 'info' } {
  if (a.outcome === 'PASSED') return { lead: 'Test sign-in passed.', tone: 'ok' }
  if (a.outcome === 'NO_CONNECTION') return { lead: 'Nothing to test.', tone: 'info' }
  return { lead: 'Test sign-in failed.', tone: 'bad' }
}

/** The codes line: platform-core's code, and the outbound reason when it gave one (for example OUT_TLS_FAILED). */
export function codeWords(a: Pick<SignInTestAnswer, 'code' | 'reason'>): string | null {
  if (!a.code) return null
  return a.reason ? `${a.code} (${a.reason})` : a.code
}

/**
 * Task 0099 (platform-core 1.2.0, LV-23): what to do about a code, in plain words, when there is more to say than the
 * message. Only the new refusal of a replacement for now; null for every other code.
 */
export function codeHint(code: string | null): string | null {
  if (code === 'IDN_TEST_SIGN_IN_REQUIRED') {
    return 'A new sign-in replaces the one in use only after a test sign-in with a token of the new one. Your installer runs it with the setup command; people go on signing in as before meanwhile. To go back to the previous sign-in, the installer runs “setup identity rollback-login-connection”.'
  }
  return null
}
