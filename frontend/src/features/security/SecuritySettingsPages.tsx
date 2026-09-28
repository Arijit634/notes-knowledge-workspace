import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useForm } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import { QRCodeCanvas } from 'qrcode.react'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { navigateToGoogle } from '../auth/OidcNavigationCoordinator'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { CsrfUnavailableError } from '../../app/security/CsrfManager'
import { listSessions, securityApi, securityKeys, securitySummary,
  type Enrollment, type SessionView } from './SecurityApi'

type Props = { auth: AuthRuntime }
type SecurityRoute = '/settings/security' | '/settings/security/mfa' | '/settings/security/sessions'

function viewer(auth: AuthRuntime): string {
  const scope = auth.session.viewerScope
  return scope.kind === 'authenticated' ? scope.viewerEpoch : 'anonymous'
}

function useSummary(auth: AuthRuntime) {
  return useQuery({ queryKey: securityKeys.summary(viewer(auth)), queryFn: () => securitySummary(auth),
    retry: false, staleTime: 0 }, auth.queries)
}

function safeError(error: unknown): string {
  if (error instanceof CsrfUnavailableError) return 'Request protection is unavailable. Refresh and try again.'
  if (error instanceof ApiProblemError) {
    if (error.problem.code === 'csrf_invalid') return 'Request protection changed. Try the action again.'
    if (error.problem.status === 429) return 'Too many requests. Try again later.'
    if (error.problem.status === 503) return 'The service is temporarily unavailable. Try again later.'
    if (error.problem.status === 401) return 'Your session is no longer available. Sign in again.'
    if (error.problem.status === 404) return 'That item is no longer available.'
    if (error.problem.status === 409) return 'This action conflicts with the current account state.'
    if (error.problem.status === 400 || error.problem.status === 422) return 'Review your information and try again.'
  }
  return 'The action could not be completed. Try again.'
}

function useAction(auth: AuthRuntime, route: SecurityRoute) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function run(action: () => Promise<unknown>, onSuccess: () => Promise<void> | void) {
    setBusy(true); setError('')
    try { await action(); await onSuccess() }
    catch (cause) {
      if (cause instanceof ApiProblemError
          && cause.problem.code === 'recent_authentication_required') {
        auth.continuation.returnIntent = route
        navigate('/reauth')
      } else {
        if (cause instanceof ApiProblemError && cause.problem.status === 401) {
          try { await auth.refreshSession() } catch { await auth.establish('anonymous') }
        }
        setError(safeError(cause))
      }
    } finally { setBusy(false) }
  }
  return { busy, error, setError, run }
}

function Page({ title, children }: { title: string; children: ReactNode }) {
  return <main className="settings-layout"><div className="settings-card">
    <nav aria-label="Security settings" className="settings-nav">
      <Link to="/">Home</Link><Link to="/settings/security">Security</Link>
      <Link to="/settings/security/mfa">MFA</Link>
      <Link to="/settings/security/sessions">Sessions</Link>
    </nav>
    <h1>{title}</h1>{children}
  </div></main>
}

function Status({ children }: { children: ReactNode }) { return <p role="status" className="success">{children}</p> }
function ErrorText({ children }: { children: ReactNode }) { return <p role="alert" className="alert">{children}</p> }

function ConfirmDialog({ title, description, confirmText, onCancel, onConfirm, busy = false }: {
  title: string; description: string; confirmText: string; onCancel: () => void
  onConfirm: () => void; busy?: boolean
}) {
  const first = useRef<HTMLButtonElement>(null), last = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    first.current?.focus()
    return () => previous?.focus()
  }, [])
  function keys(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); onCancel() }
    if (event.key === 'Tab' && !event.shiftKey && document.activeElement === last.current) {
      event.preventDefault(); first.current?.focus()
    } else if (event.key === 'Tab' && event.shiftKey && document.activeElement === first.current) {
      event.preventDefault(); last.current?.focus()
    }
  }
  return <div className="dialog-backdrop"><div role="alertdialog" aria-modal="true"
    aria-labelledby="confirm-title" aria-describedby="confirm-description" className="confirm-dialog"
    onKeyDown={keys}>
    <h2 id="confirm-title">{title}</h2><p id="confirm-description">{description}</p>
    <div className="actions"><button ref={first} type="button" className="button-secondary"
      onClick={onCancel} disabled={busy}>Cancel</button>
      <button ref={last} type="button" onClick={onConfirm} disabled={busy}>{confirmText}</button></div>
  </div></div>
}

async function refreshSecurity(auth: AuthRuntime, includeSessions = false) {
  await auth.queries.invalidateQueries({ queryKey: securityKeys.summary(viewer(auth)) })
  if (includeSessions) await auth.queries.invalidateQueries({ queryKey: securityKeys.sessions(viewer(auth)) })
  auth.csrf.clear()
  await auth.refreshSession()
}

export function SecuritySettingsPage({ auth }: Props) {
  const navigate = useNavigate(), summary = useSummary(auth)
  const action = useAction(auth, '/settings/security')
  const passwordForm = useForm<{ newPassword: string; confirmation: string }>()
  const emailForm = useForm<{ newEmail: string }>()
  const [notice, setNotice] = useState('')
  const [confirmation, setConfirmation] = useState<{ kind: 'unlink'; id: string } | { kind: 'delete' } | null>(null)
  const [tokenReady, setTokenReady] = useState(() => auth.continuation.emailChangeToken !== null)
  useEffect(() => auth.sensitive.register(() => {
    passwordForm.reset(); emailForm.reset(); setConfirmation(null); setTokenReady(false)
  }), [auth, passwordForm, emailForm])

  const changePassword = passwordForm.handleSubmit(async values => {
    if (values.newPassword !== values.confirmation) {
      passwordForm.setError('confirmation', { message: 'Passwords do not match.' }); return
    }
    await action.run(() => securityApi.changePassword(auth, values.newPassword), async () => {
      passwordForm.reset(); setNotice('Password updated.'); await refreshSecurity(auth)
    })
  })
  const requestEmail = emailForm.handleSubmit(async values => {
    await action.run(() => securityApi.requestEmailChange(auth, values.newEmail), () => {
      emailForm.reset(); setNotice('If the details can be used, check your email for the next step.')
    })
  })
  async function confirmEmail() {
    const token = auth.continuation.emailChangeToken
    if (!token) { setTokenReady(false); return }
    await action.run(() => securityApi.confirmEmailChange(auth, token), async () => {
      auth.continuation.emailChangeToken = null; setTokenReady(false)
      setNotice('Email address updated.'); await refreshSecurity(auth)
    })
  }
  async function beginGoogleLink() {
    await action.run(async () => navigateToGoogle(await securityApi.beginGoogleLink(auth)), () => undefined)
  }
  async function completeConfirmation() {
    if (!confirmation) return
    if (confirmation.kind === 'unlink') {
      const id = confirmation.id
      await action.run(() => securityApi.unlinkGoogle(auth, id), async () => {
        setConfirmation(null); setNotice('Google sign-in removed.'); await refreshSecurity(auth)
      })
    } else {
      await action.run(() => securityApi.deleteAccount(auth), async () => {
        setConfirmation(null); await auth.establish('anonymous'); navigate('/', { replace: true })
      })
    }
  }
  return <Page title="Account security">
    {summary.isPending && <p role="status">Loading security settings…</p>}
    {summary.isError && <ErrorText>{safeError(summary.error)}</ErrorText>}
    {notice && <Status>{notice}</Status>}{action.error && <ErrorText>{action.error}</ErrorText>}
    {summary.data && <>
      <section aria-labelledby="email-heading"><h2 id="email-heading">Email</h2>
        <p>Current email: {summary.data.email}</p>
        {tokenReady && <div className="settings-panel"><h3>Confirm email change</h3>
          <p>Confirm the address from the link you opened.</p>
          <button type="button" onClick={confirmEmail} disabled={action.busy}>Confirm email change</button></div>}
        <p>If a confirmation link was lost while confirming your identity, reopen the original email link.</p>
        <form onSubmit={requestEmail} noValidate><label htmlFor="new-email">New email</label>
          <input id="new-email" type="email" autoComplete="email" maxLength={254}
            aria-invalid={!!emailForm.formState.errors.newEmail}
            {...emailForm.register('newEmail', { required: 'Enter a new email.' })} />
          {emailForm.formState.errors.newEmail && <span className="field-error" role="alert">{emailForm.formState.errors.newEmail.message}</span>}
          <button disabled={action.busy || emailForm.formState.isSubmitting}>Request email change</button></form>
      </section>
      <section aria-labelledby="password-heading"><h2 id="password-heading">Password</h2>
        <p>{summary.data.passwordConfigured ? 'A password is configured.' : 'No password is configured.'}</p>
        <form onSubmit={changePassword} noValidate>
          <label htmlFor="security-password">New password</label><input id="security-password"
            type="password" autoComplete="new-password" maxLength={1024}
            aria-invalid={!!passwordForm.formState.errors.newPassword}
            {...passwordForm.register('newPassword', { required: 'Enter a new password.' })} />
          {passwordForm.formState.errors.newPassword && <span className="field-error" role="alert">{passwordForm.formState.errors.newPassword.message}</span>}
          <label htmlFor="security-password-confirm">Confirm new password</label><input id="security-password-confirm"
            type="password" autoComplete="new-password" maxLength={1024}
            aria-invalid={!!passwordForm.formState.errors.confirmation}
            {...passwordForm.register('confirmation', { required: 'Confirm the new password.' })} />
          {passwordForm.formState.errors.confirmation && <span className="field-error" role="alert">{passwordForm.formState.errors.confirmation.message}</span>}
          <button disabled={action.busy || passwordForm.formState.isSubmitting}>Change password</button></form>
      </section>
      <section aria-labelledby="google-heading"><h2 id="google-heading">Google sign-in</h2>
        {summary.data.oidcLinks.length === 0 ? <p>No Google sign-in is linked.</p>
          : <ul>{summary.data.oidcLinks.map(link => <li key={link.linkId}>
            Google sign-in linked <time dateTime={link.linkedAt}>{new Date(link.linkedAt).toLocaleDateString()}</time>{' '}
            <button type="button" className="button-secondary" disabled={action.busy}
              onClick={() => setConfirmation({ kind: 'unlink', id: link.linkId })}>Remove Google sign-in</button>
          </li>)}</ul>}
        <button type="button" onClick={beginGoogleLink} disabled={action.busy}>Link Google sign-in</button>
      </section>
      <section aria-labelledby="factors-heading"><h2 id="factors-heading">More security controls</h2>
        <nav className="actions" aria-label="More security controls"><Link className="button button-secondary" to="/settings/security/mfa">Manage MFA</Link>
          <Link className="button button-secondary" to="/settings/security/sessions">Manage sessions</Link></nav></section>
      <section aria-labelledby="deletion-heading"><h2 id="deletion-heading">Delete account</h2>
        <p>This ends account access. You cannot undo this action here.</p>
        <button type="button" onClick={() => setConfirmation({ kind: 'delete' })}>Delete account</button></section>
    </>}
    {confirmation && <ConfirmDialog title={confirmation.kind === 'delete' ? 'Delete account?' : 'Remove Google sign-in?'}
      description={confirmation.kind === 'delete' ? 'This will end access to this account.' : 'This sign-in method will no longer be available.'}
      confirmText={confirmation.kind === 'delete' ? 'Delete my account' : 'Remove sign-in'}
      onCancel={() => setConfirmation(null)} onConfirm={completeConfirmation} busy={action.busy} />}
  </Page>
}

export function MfaSettingsPage({ auth }: Props) {
  const summary = useSummary(auth), action = useAction(auth, '/settings/security/mfa')
  const [setup, setSetup] = useState<Enrollment | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)
  const [code, setCode] = useState(''), [notice, setNotice] = useState('')
  const [confirm, setConfirm] = useState<'disable' | 'regenerate' | null>(null)
  useEffect(() => auth.sensitive.register(() => {
    setSetup(null); setCodes(null); setCode(''); setConfirm(null)
  }), [auth])
  async function begin() {
    await action.run(async () => { setSetup(await securityApi.beginEnrollment(auth)); setCodes(null) },
      () => auth.queries.invalidateQueries({ queryKey: securityKeys.summary(viewer(auth)) }))
  }
  async function completeEnrollment(event: FormEvent) {
    event.preventDefault()
    if (!setup) return
    await action.run(async () => {
      const returned = await securityApi.confirmEnrollment(auth, setup.enrollmentId, code)
      setSetup(null); setCode(''); setCodes(returned)
    }, async () => { await refreshSecurity(auth) })
  }
  async function copyKey() {
    if (!setup) return
    try { await navigator.clipboard.writeText(setup.manualSecret); setNotice('Setup key copied.') }
    catch { setNotice('Copy is unavailable. Enter the setup key manually.') }
  }
  async function completeConfirmation() {
    if (!confirm) return
    if (confirm === 'disable') {
      await action.run(() => securityApi.disableMfa(auth), async () => {
        setConfirm(null); setSetup(null); setCodes(null); setCode(''); setNotice('MFA disabled.')
        await refreshSecurity(auth)
      })
    } else {
      await action.run(async () => {
        const returned = await securityApi.regenerateCodes(auth)
        setCodes(returned); setConfirm(null); setSetup(null); setCode('')
      }, async () => { await refreshSecurity(auth) })
    }
  }
  return <Page title="Multi-factor authentication">
    {summary.isPending && <p role="status">Loading MFA settings…</p>}
    {summary.isError && <ErrorText>{safeError(summary.error)}</ErrorText>}
    {action.error && <ErrorText>{action.error}</ErrorText>}{notice && <Status>{notice}</Status>}
    {summary.data && <p role="status">MFA is {summary.data.mfaState === 'active' ? 'active' : summary.data.mfaState === 'enrollmentPending' ? 'pending enrollment' : 'disabled'}.</p>}
    {codes ? <section aria-labelledby="codes-heading"><h2 id="codes-heading">Save your recovery codes now</h2>
      <p>These codes are shown only once. Store them securely before leaving this screen.</p>
      <ol>{codes.map((value, index) => <li key={index}><code>{value}</code></li>)}</ol>
      <button type="button" onClick={() => { setCodes(null); setNotice('Recovery codes dismissed.') }}>Done</button>
    </section> : setup ? <section aria-labelledby="setup-heading"><h2 id="setup-heading">Set up an authenticator</h2>
      <div className="qr-frame"><QRCodeCanvas value={setup.otpauthUri} size={240} level="M" marginSize={4}
        role="img" aria-label="Authenticator setup QR code" /></div>
      <p>If you can't scan the QR code, choose manual setup in your authenticator app and enter this setup key.</p>
      <p><strong>Setup key:</strong> <code>{setup.manualSecret}</code></p>
      <button type="button" className="button-secondary" onClick={copyKey}>Copy setup key</button>
      <form onSubmit={completeEnrollment}><label htmlFor="enrollment-code">Current authenticator code</label>
        <input id="enrollment-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
          value={code} onChange={event => setCode(event.target.value)} required />
        <button disabled={action.busy}>Verify and enable MFA</button></form>
      <button type="button" className="text-button" onClick={() => { setSetup(null); setCode(''); setNotice('Setup cancelled.') }}>Cancel setup</button>
    </section> : <section aria-labelledby="mfa-actions-heading"><h2 id="mfa-actions-heading">Manage MFA</h2>
      {summary.data?.mfaState !== 'active' && <button type="button" onClick={begin} disabled={action.busy}>Set up MFA</button>}
      {summary.data?.mfaState === 'active' && <div className="actions"><button type="button" onClick={() => setConfirm('regenerate')}>Regenerate recovery codes</button>
        <button type="button" onClick={() => setConfirm('disable')}>Disable MFA</button></div>}
    </section>}
    {confirm && <ConfirmDialog title={confirm === 'disable' ? 'Disable MFA?' : 'Replace recovery codes?'}
      description={confirm === 'disable' ? 'Your authenticator will no longer be required after this change.' : 'Old recovery codes will stop working.'}
      confirmText={confirm === 'disable' ? 'Disable MFA' : 'Replace codes'}
      onCancel={() => setConfirm(null)} onConfirm={completeConfirmation} busy={action.busy} />}
  </Page>
}

export function SessionsSettingsPage({ auth }: Props) {
  const navigate = useNavigate(), action = useAction(auth, '/settings/security/sessions')
  const sessions = useQuery({ queryKey: securityKeys.sessions(viewer(auth)), queryFn: () => listSessions(auth),
    retry: false, staleTime: 0 }, auth.queries)
  const [confirm, setConfirm] = useState<SessionView | 'others' | 'all' | null>(null)
  const [notice, setNotice] = useState('')
  async function complete() {
    if (!confirm) return
    const logout = confirm === 'all' || (typeof confirm === 'object' && confirm.current)
    await action.run(() => confirm === 'all' ? securityApi.revokeAll(auth)
      : confirm === 'others' ? securityApi.revokeOthers(auth)
        : securityApi.revokeOne(auth, confirm.sessionHandle), async () => {
      setConfirm(null)
      if (logout) { await auth.establish('anonymous'); navigate('/', { replace: true }) }
      else {
        await auth.queries.invalidateQueries({ queryKey: securityKeys.sessions(viewer(auth)) })
        auth.csrf.clear(); await auth.refreshSession(); setNotice('Sessions updated.')
      }
    })
  }
  return <Page title="Your sessions">
    {sessions.isPending && <p role="status">Loading sessions…</p>}
    {sessions.isError && <ErrorText>{safeError(sessions.error)}</ErrorText>}
    {action.error && <ErrorText>{action.error}</ErrorText>}{notice && <Status>{notice}</Status>}
    {sessions.data?.length === 0 && <p>No sessions are available.</p>}
    {sessions.data && <ul className="session-list">{sessions.data.map(item => <li key={item.sessionHandle}>
      <h2>{item.client}</h2>{item.current && <strong className="current-session">Current session</strong>}
      <p>Created <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString()}</time><br />
        Last seen <time dateTime={item.lastSeenAt}>{new Date(item.lastSeenAt).toLocaleString()}</time><br />
        Expires <time dateTime={item.expiresAt}>{new Date(item.expiresAt).toLocaleString()}</time></p>
      <button type="button" className="button-secondary" onClick={() => setConfirm(item)}>
        {item.current ? 'Revoke this session' : 'Revoke session'}</button>
    </li>)}</ul>}
    <div className="actions"><button type="button" className="button-secondary" onClick={() => setConfirm('others')}>Revoke other sessions</button>
      <button type="button" onClick={() => setConfirm('all')}>Revoke all sessions</button></div>
    {confirm && <ConfirmDialog title={confirm === 'all' ? 'Revoke all sessions?' : confirm === 'others' ? 'Revoke other sessions?' : 'Revoke this session?'}
      description={confirm === 'all' || (typeof confirm === 'object' && confirm.current)
        ? 'This browser will be signed out.' : 'Selected sessions will lose access.'}
      confirmText={confirm === 'all' ? 'Revoke all sessions' : 'Revoke sessions'}
      onCancel={() => setConfirm(null)} onConfirm={complete} busy={action.busy} />}
  </Page>
}
