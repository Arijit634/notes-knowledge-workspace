import { createContext, lazy, Suspense, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { createBrowserRouter, Link, Navigate, Route, RouterProvider, Routes, useLocation, useNavigate } from 'react-router'
import { useForm } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import { ApiProblemError } from './api/ProblemDetailsDecoder'
import { CsrfUnavailableError } from './security/CsrfManager'
import { canEnterRoute, type RouteAccess } from './security/RouteGate'
import { AuthRuntime } from '../features/auth/AuthRuntime'
import { navigateToGoogle } from '../features/auth/OidcNavigationCoordinator'
import { securityKeys, securitySummary } from '../features/security/SecurityApi'

export const AUTH_ROUTES = ['/', '/signup', '/verify-email', '/login', '/mfa',
  '/forgot-password', '/reset-password', '/auth/complete', '/reauth'] as const
export const SECURITY_ROUTES = ['/settings/security', '/settings/security/mfa',
  '/settings/security/sessions'] as const
export const NOTES_ROUTES = ['/notes', '/notes/new', '/notes/:id', '/notes/:noteId/attachments/:attachmentId'] as const
export const PRODUCT_ROUTES = [...AUTH_ROUTES, ...SECURITY_ROUTES, ...NOTES_ROUTES] as const
const SecuritySettingsPage = lazy(() => import('../features/security/SecuritySettingsPages')
  .then(module => ({ default: module.SecuritySettingsPage })))
const MfaSettingsPage = lazy(() => import('../features/security/SecuritySettingsPages')
  .then(module => ({ default: module.MfaSettingsPage })))
const SessionsSettingsPage = lazy(() => import('../features/security/SecuritySettingsPages')
  .then(module => ({ default: module.SessionsSettingsPage })))
const NotesListPage = lazy(() => import('../features/notes/NotesPages')
  .then(module => ({ default: module.NotesListPage })))
const NoteEditorPage = lazy(() => import('../features/notes/NotesPages')
  .then(module => ({ default: module.NoteEditorPage })))
const AttachmentViewerPage = lazy(() => import('../features/notes/AttachmentViewerPage')
  .then(module => ({ default: module.AttachmentViewerPage })))
const Context = createContext<{ auth: AuthRuntime; version: number } | null>(null)
const useAuth = () => {
  const value = useContext(Context)
  if (!value) throw new Error('Auth runtime unavailable')
  return value.auth
}

function message(error: unknown): string {
  if (error instanceof CsrfUnavailableError) return 'Request protection is unavailable. Refresh and try again.'
  if (error instanceof ApiProblemError) {
    if (error.problem.status === 429) {
      const raw = error.metadata.retryAfter
      const seconds = raw !== null && /^(0|[1-9][0-9]{0,4})$/.test(raw) ? Number(raw) : null
      return seconds === null ? 'Too many requests. Try again later.' : `Too many requests. Try again in ${seconds} seconds.`
    }
    if (error.problem.status === 503) return 'The service is temporarily unavailable. Try again later.'
    if (error.problem.status === 401) return 'The credentials could not be verified.'
    if (error.problem.status === 400 || error.problem.status === 422) {
      const fields = error.problem.errors?.map(field => `${field.field}: ${field.message}`).join(' ')
      return fields || 'Review your information and try again.'
    }
    if (error.problem.status === 409) return 'This action cannot be completed in the current state.'
  }
  return 'Something went wrong. Try again.'
}
const Alert = ({ children }: { children: ReactNode }) => <p className="alert" role="alert">{children}</p>
const BlindAcceptedPanel = () => <div className="success" role="status"><h2>Check your email</h2><p>If the details can be used, check your email for the next step.</p></div>
function Shell({ title, eyebrow, children }: { title: string; eyebrow?: string; children: ReactNode }) {
  return <main className="auth-layout"><section className="auth-card" aria-labelledby="page-title">
    <Link className="brand" to="/">Notes <span>&amp;</span> Knowledge</Link>
    {eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1 id="page-title">{title}</h1>{children}
  </section></main>
}
function Gate({ access, children }: { access: RouteAccess; children: ReactNode }) {
  const auth = useAuth()
  if (auth.state === 'unknown') return <Shell title="Checking your session"><p role="status">Please wait…</p></Shell>
  if (auth.state === 'mfaRequired' && !auth.continuation.challengeId && access !== 'PUBLIC') return <RestartLogin />
  if (canEnterRoute(auth.state, access)) {
    return <>{children}</>
  }
  if (auth.state === 'mfaRequired' && auth.continuation.challengeId) return <Navigate to="/mfa" replace />
  return <Navigate to={auth.state === 'authenticated' ? '/' : '/login'} replace />
}
function RestartLogin() {
  const auth = useAuth(), navigate = useNavigate()
  const [error, setError] = useState('')
  async function restart() { try { await auth.logout(); navigate('/login', { replace: true }) } catch (e) { setError(message(e)) } }
  return <Shell title="Restart sign in"><p>Your verification session is no longer available.</p>{error && <Alert>{error}</Alert>}<button onClick={restart}>Restart sign in</button></Shell>
}
function Landing() {
  const auth = useAuth(), navigate = useNavigate()
  const [error, setError] = useState('')
  async function logout() { try { await auth.logout(); navigate('/') } catch (e) { setError(message(e)) } }
  return <Shell title="A quieter place for what you need to remember" eyebrow="Your workspace starts here">
    <p className="lead">Keep your notes and knowledge together. Sign in to continue when your workspace is ready.</p>
    {error && <Alert>{error}</Alert>}
    {auth.state === 'authenticated' ? <><p role="status">You are signed in.</p>
      <Link className="button" to="/notes">Your notes</Link>
      <Link className="button button-secondary" to="/settings/security">Security settings</Link>
      <button onClick={logout}>Log out</button></>
      : auth.state === 'mfaRequired' ? <Link className="button" to={auth.continuation.challengeId ? '/mfa' : '/login'}>{auth.continuation.challengeId ? 'Continue verification' : 'Restart sign in'}</Link>
        : <nav className="actions" aria-label="Get started"><Link className="button" to="/signup">Create an account</Link><Link className="button button-secondary" to="/login">Log in</Link></nav>}
  </Shell>
}
type Credentials = { email: string; password: string }
function CredentialsFields({ register, errors, creating = false }: { register: ReturnType<typeof useForm<Credentials>>['register']; errors: ReturnType<typeof useForm<Credentials>>['formState']['errors']; creating?: boolean }) {
  return <><label htmlFor="email">Email</label><input id="email" type="email" autoComplete="email" aria-invalid={!!errors.email} aria-describedby={errors.email ? 'email-error' : undefined}
    {...register('email', { required: 'Enter your email.', maxLength: { value: 254, message: 'Email is too long.' } })} />
    {errors.email && <span id="email-error" className="field-error">{errors.email.message}</span>}
    <label htmlFor="password">{creating ? 'Create password' : 'Password'}</label><input id="password" type="password" autoComplete={creating ? 'new-password' : 'current-password'} aria-invalid={!!errors.password} aria-describedby={errors.password ? 'password-error' : undefined}
      {...register('password', { required: 'Enter a password.', maxLength: { value: 1024, message: 'Password is too long.' } })} />
    {errors.password && <span id="password-error" className="field-error">{errors.password.message}</span>}</>
}
function Signup() {
  const auth = useAuth(), { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<Credentials>()
  const [accepted, setAccepted] = useState(false), [error, setError] = useState('')
  const submit = handleSubmit(async values => { setError(''); try { await auth.api.request('POST', '/api/auth/registrations', { json: values }); setAccepted(true) } catch (e) { setError(message(e)) } })
  return <Shell title="Create your account" eyebrow="Get started">
    {accepted ? <BlindAcceptedPanel /> : <form onSubmit={submit} noValidate><CredentialsFields register={register} errors={errors} creating />{error && <Alert>{error}</Alert>}<button disabled={isSubmitting}>Create account</button></form>}
    <p className="footnote">Already have an account? <Link to="/login">Log in</Link></p>
  </Shell>
}
function BlindRequest({ kind }: { kind: 'verification' | 'reset' }) {
  const auth = useAuth()
  const [email, setEmail] = useState(''), [accepted, setAccepted] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const path = kind === 'verification' ? '/api/auth/email-verification/requests' : '/api/auth/password-reset/requests'
  async function submit(event: FormEvent) { event.preventDefault(); setBusy(true); setError(''); try { await auth.api.request('POST', path, { json: { email } }); setAccepted(true) } catch (e) { setError(message(e)) } finally { setBusy(false) } }
  return accepted ? <BlindAcceptedPanel /> : <form onSubmit={submit}><label htmlFor="email">Email</label><input id="email" type="email" autoComplete="email" required maxLength={254} value={email} onChange={e => setEmail(e.target.value)} />
    {error && <Alert>{error}</Alert>}<button disabled={busy}>{kind === 'verification' ? 'Request a new link' : 'Request reset link'}</button></form>
}
function VerifyEmail() {
  const auth = useAuth()
  const [state, setState] = useState<'ready' | 'success' | 'invalid'>(auth.continuation.verificationToken ? 'ready' : 'invalid')
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function confirm() {
    const token = auth.continuation.verificationToken; if (!token) return
    setBusy(true); setError('')
    try { await auth.api.request('POST', '/api/auth/email-verification/confirmations', { json: { token } }); auth.continuation.verificationToken = null; setState('success') }
    catch (e) { if (e instanceof ApiProblemError && [400, 404, 409, 410].includes(e.problem.status)) { auth.continuation.verificationToken = null; setState('invalid') } else setError(message(e)) }
    finally { setBusy(false) }
  }
  return <Shell title="Verify your email" eyebrow="Account verification">
    {state === 'ready' && <><p>Your link is ready to confirm.</p><button onClick={confirm} disabled={busy}>Confirm email</button></>}
    {state === 'success' && <><p role="status">Email confirmed. You can now log in.</p><Link to="/login">Log in</Link></>}
    {state === 'invalid' && <><p role="status">This link is missing, expired, or unavailable. You can request another.</p><BlindRequest kind="verification" /></>}
    {error && <Alert>{error}</Alert>}
  </Shell>
}
function Login() {
  const auth = useAuth(), navigate = useNavigate()
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<Credentials>()
  const [error, setError] = useState(''), [googleBusy, setGoogleBusy] = useState(false)
  const submit = handleSubmit(async values => {
    setError('')
    try {
      const result = await auth.api.request<{ state?: unknown; challengeId?: unknown }>('POST', '/api/auth/login/password', { json: values })
      if (result.metadata.status === 202 && result.body?.state === 'mfaRequired') { await auth.establish('mfaRequired', result.body.challengeId); navigate('/mfa', { replace: true }) }
      else if (result.metadata.status === 200 && result.body?.state === 'authenticated') { await auth.establish('authenticated'); navigate('/', { replace: true }) }
      else setError('Sign in could not be completed safely.')
    } catch (e) { setError(message(e)) }
  })
  async function google() { setGoogleBusy(true); setError(''); try { const r = await auth.api.request<{ authorizationUrl?: unknown }>('POST', '/api/auth/oidc/google/authorizations'); navigateToGoogle(r.body?.authorizationUrl) } catch (e) { setError(message(e)) } finally { setGoogleBusy(false) } }
  return <Shell title="Welcome back" eyebrow="Log in"><form onSubmit={submit} noValidate><CredentialsFields register={register} errors={errors} />{error && <Alert>{error}</Alert>}<button disabled={isSubmitting || googleBusy}>Log in</button></form>
    <div className="divider" aria-hidden="true">or</div><button className="button-secondary" onClick={google} disabled={isSubmitting || googleBusy}>Continue with Google</button>
    <p className="footnote"><Link to="/forgot-password">Forgot password?</Link></p><p className="footnote">New here? <Link to="/signup">Create an account</Link></p></Shell>
}
function Mfa() {
  const auth = useAuth(), navigate = useNavigate()
  const [method, setMethod] = useState<'totp' | 'recovery-code'>('totp'), [code, setCode] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault(); const challenge = auth.continuation.challengeId
    if (!challenge) { navigate('/login', { replace: true }); return }
    setBusy(true); setError('')
    try {
      const result = await auth.api.request<{ state?: unknown }>('POST', `/api/auth/mfa/challenges/${challenge}/${method}`, { json: { code } })
      if (result.body?.state !== 'authenticated') throw new Error('Unexpected response')
      await auth.establish('authenticated'); navigate('/', { replace: true })
    } catch (e) {
      if (e instanceof ApiProblemError && [404, 409, 410].includes(e.problem.status)) {
        auth.continuation.clear()
        try { await auth.logout(); navigate('/login', { replace: true }) }
        catch { setError('Verification is unavailable. Restart sign in to continue.') }
        return
      }
      setError(message(e))
    } finally { setBusy(false) }
  }
  async function restart() { try { await auth.logout(); navigate('/login', { replace: true }) } catch (e) { setError(message(e)) } }
  return <Shell title="Verify it’s you" eyebrow="Additional verification">
    <div className="method-switch" aria-label="Verification method"><button type="button" className={method === 'totp' ? '' : 'button-secondary'} onClick={() => { setMethod('totp'); setCode(''); setError('') }}>Authenticator code</button><button type="button" className={method === 'recovery-code' ? '' : 'button-secondary'} onClick={() => { setMethod('recovery-code'); setCode(''); setError('') }}>Recovery code</button></div>
    <form onSubmit={submit}><label htmlFor="mfa-code">{method === 'totp' ? 'Authenticator code' : 'Recovery code'}</label><input id="mfa-code" required maxLength={method === 'totp' ? 6 : 128} inputMode={method === 'totp' ? 'numeric' : 'text'} autoComplete={method === 'totp' ? 'one-time-code' : 'off'} value={code} onChange={e => setCode(e.target.value)} />
      {error && <Alert>{error}</Alert>}<button disabled={busy}>Verify and continue</button></form><button className="text-button" onClick={restart}>Restart sign in</button>
  </Shell>
}
function ForgotPassword() { return <Shell title="Reset your password" eyebrow="Account recovery"><p>Enter your email to request a reset link.</p><BlindRequest kind="reset" /><p className="footnote"><Link to="/login">Back to log in</Link></p></Shell> }
function ResetPassword() {
  const auth = useAuth()
  const [state, setState] = useState<'ready' | 'success' | 'invalid'>(auth.continuation.resetToken ? 'ready' : 'invalid')
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<{ password: string; confirmation: string }>()
  const [error, setError] = useState('')
  const submit = handleSubmit(async values => {
    if (values.password !== values.confirmation) { setError('Passwords do not match.'); return }
    const token = auth.continuation.resetToken; if (!token) return
    setError('')
    try { await auth.api.request('POST', '/api/auth/password-reset/confirmations', { json: { token, newPassword: values.password } }); reset(); auth.continuation.clear(); await auth.establish('anonymous'); setState('success') }
    catch (e) { if (e instanceof ApiProblemError && [404, 409, 410].includes(e.problem.status)) { reset(); auth.continuation.clear(); setState('invalid') } else setError(message(e)) }
  })
  return <Shell title="Choose a new password" eyebrow="Account recovery">
    {state === 'ready' && <form onSubmit={submit} noValidate><label htmlFor="new-password">New password</label><input id="new-password" type="password" autoComplete="new-password" aria-invalid={!!errors.password} aria-describedby={errors.password ? 'new-password-error' : undefined}
      {...register('password', { required: 'Enter a new password.', maxLength: { value: 1024, message: 'Password is too long.' } })} />
      {errors.password && <span id="new-password-error" className="field-error">{errors.password.message}</span>}
      <label htmlFor="confirm-password">Confirm new password</label><input id="confirm-password" type="password" autoComplete="new-password" aria-invalid={!!errors.confirmation} aria-describedby={errors.confirmation ? 'confirm-password-error' : undefined}
        {...register('confirmation', { required: 'Confirm the new password.' })} />
      {errors.confirmation && <span id="confirm-password-error" className="field-error">{errors.confirmation.message}</span>}
      {error && <Alert>{error}</Alert>}<button disabled={isSubmitting}>Set new password</button></form>}
    {state === 'success' && <><p role="status">Your password was reset. Sign in with your new password.</p><Link to="/login">Log in</Link></>}
    {state === 'invalid' && <><p role="status">This link is missing, expired, or unavailable.</p><Link to="/forgot-password">Request another link</Link></>}
  </Shell>
}
function AuthComplete() {
  const auth = useAuth(), navigate = useNavigate()
  const [result, setResult] = useState<'checking' | 'signedIn' | 'unsuccessful'>('checking')
  useEffect(() => { let active = true; auth.refreshSession().then(() => {
    if (!active) return
    if (auth.state === 'mfaRequired') { if (auth.continuation.challengeId) navigate('/mfa', { replace: true }); else setResult('unsuccessful') }
    else setResult(auth.state === 'authenticated' ? 'signedIn' : 'unsuccessful')
  }).catch(() => { if (active) setResult('unsuccessful') }); return () => { active = false } }, [auth, navigate])
  return <Shell title="Completing sign in" eyebrow="Authentication">{result === 'checking' && <p role="status">Checking your session…</p>}{result === 'signedIn' && <><p role="status">You are signed in.</p><Link to="/">Continue</Link></>}{result === 'unsuccessful' && <><p role="status">This sign-in could not be completed. Try again.</p><Link to="/login">Log in</Link></>}</Shell>
}
function RecentAuth() {
  const auth = useAuth(), navigate = useNavigate()
  const [password, setPassword] = useState(''), [busy, setBusy] = useState(false), [complete, setComplete] = useState(false), [error, setError] = useState('')
  const scope = auth.session.viewerScope
  const summary = useQuery({ queryKey: securityKeys.summary(scope.kind === 'authenticated' ? scope.viewerEpoch : 'anonymous'),
    queryFn: () => securitySummary(auth), retry: false, staleTime: 0 }, auth.queries)
  async function submit(event: FormEvent) { event.preventDefault(); setBusy(true); setError(''); try { await auth.api.request('POST', '/api/auth/reauth/password', { json: { email: null, password } }); setPassword(''); await auth.csrf.refresh(); setComplete(true); const destination = auth.continuation.returnIntent; auth.continuation.returnIntent = null; if (destination) navigate(destination, { replace: true }) } catch (e) { setError(message(e)) } finally { setBusy(false) } }
  return <Shell title="Confirm your identity" eyebrow="Recent authentication">
    {complete ? <p role="status">Identity confirmed for this session.</p>
      : summary.isPending ? <p role="status">Checking available confirmation method…</p>
        : summary.isError ? <Alert>{message(summary.error)}</Alert>
          : summary.data.passwordConfigured ? <form onSubmit={submit}><label htmlFor="reauth-password">Password</label><input id="reauth-password" type="password" autoComplete="current-password" required maxLength={1024} value={password} onChange={e => setPassword(e.target.value)} />{error && <Alert>{error}</Alert>}<button disabled={busy}>Confirm with password</button></form>
            : <><p>To confirm sensitive changes, set an application password first.</p><Link to="/forgot-password">Set an application password</Link></>}
    <p className="footnote"><Link to="/">Back to home</Link></p></Shell>
}
function AuthRoutes() {
  const auth = useAuth(), location = useLocation(), priorPath = useRef<string | null>(null)
  useEffect(() => {
    if (priorPath.current === '/verify-email' && location.pathname !== '/verify-email') auth.continuation.verificationToken = null
    if (priorPath.current === '/reset-password' && location.pathname !== '/reset-password') auth.continuation.resetToken = null
    if (priorPath.current === '/settings/security' && location.pathname !== '/settings/security') auth.continuation.emailChangeToken = null
    priorPath.current = location.pathname
  }, [auth, location.pathname])
  return <Routes>
    <Route path={AUTH_ROUTES[0]} element={<Gate access="PUBLIC"><Landing /></Gate>} />
    <Route path={AUTH_ROUTES[1]} element={<Gate access="ANONYMOUS_ONLY"><Signup /></Gate>} />
    <Route path={AUTH_ROUTES[2]} element={<Gate access="PUBLIC"><VerifyEmail /></Gate>} />
    <Route path={AUTH_ROUTES[3]} element={<Gate access="ANONYMOUS_ONLY"><Login /></Gate>} />
    <Route path={AUTH_ROUTES[4]} element={<Gate access="MFA_CONTINUATION"><Mfa /></Gate>} />
    <Route path={AUTH_ROUTES[5]} element={<Gate access="PUBLIC"><ForgotPassword /></Gate>} />
    <Route path={AUTH_ROUTES[6]} element={<Gate access="PUBLIC"><ResetPassword /></Gate>} />
    <Route path={AUTH_ROUTES[7]} element={<Gate access="PUBLIC"><AuthComplete /></Gate>} />
    <Route path={AUTH_ROUTES[8]} element={<Gate access="FULL_AUTHENTICATED"><RecentAuth /></Gate>} />
    <Route path={SECURITY_ROUTES[0]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading security settings"><p role="status">Please wait…</p></Shell>}><SecuritySettingsPage auth={auth} /></Suspense></Gate>} />
    <Route path={SECURITY_ROUTES[1]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading MFA settings"><p role="status">Please wait…</p></Shell>}><MfaSettingsPage auth={auth} /></Suspense></Gate>} />
    <Route path={SECURITY_ROUTES[2]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading sessions"><p role="status">Please wait…</p></Shell>}><SessionsSettingsPage auth={auth} /></Suspense></Gate>} />
    <Route path={NOTES_ROUTES[0]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading notes"><p role="status">Please wait…</p></Shell>}><NotesListPage auth={auth} /></Suspense></Gate>} />
    <Route path={NOTES_ROUTES[1]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading editor"><p role="status">Please wait…</p></Shell>}><NoteEditorPage auth={auth} creating /></Suspense></Gate>} />
    <Route path={NOTES_ROUTES[2]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading editor"><p role="status">Please wait…</p></Shell>}><NoteEditorPage key={location.pathname} auth={auth} /></Suspense></Gate>} />
    <Route path={NOTES_ROUTES[3]} element={<Gate access="FULL_AUTHENTICATED"><Suspense fallback={<Shell title="Loading attachment"><p role="status">Please wait…</p></Shell>}><AttachmentViewerPage key={location.pathname} auth={auth} /></Suspense></Gate>} />
    <Route path="*" element={<Shell title="Page not found"><p>That page is unavailable.</p><Link to="/">Go home</Link></Shell>} />
  </Routes>
}
const defaultRuntime = new AuthRuntime()
export function App({ auth = defaultRuntime }: { auth?: AuthRuntime }) {
  const [version, rerender] = useState(0), [startupError, setStartupError] = useState(false)
  const [router] = useState(() => createBrowserRouter([{ path: '*', element: <AuthRoutes /> }]))
  useEffect(() => auth.subscribe(() => rerender(value => value + 1)), [auth])
  useEffect(() => { auth.bootstrap().catch(() => setStartupError(true)) }, [auth])
  return <Context.Provider value={{ auth, version }}>{startupError ? <Shell title="Session unavailable"><Alert>We could not check your session safely. Refresh to try again.</Alert></Shell> : <RouterProvider router={router} />}</Context.Provider>
}
