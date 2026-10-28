import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { useForm } from 'react-hook-form'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { discoveryApi } from './DiscoveryApi'
import { publishingApi, validHandle, validId } from '../publishing/PublishingApi'
import { publicationKeys } from '../publishing/PublicationCache'
import { publicationChanged } from '../publishing/PublicationCache'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { MarkdownView } from '../notes/MarkdownView'
import { PublicAuthorView, PublicCards, PublicFailure, PublicMediaList, PublicShell, PublicTags, dateLabel } from './PublicUi'
import { FormDialog } from '../../app/ui/FormDialog'
import { featureError } from '../../app/ui/featureError'
import { usePrivateScope } from '../../app/security/usePrivateScope'
import { useRetryAfter } from '../../app/ui/useRetryAfter'

export function ExplorePage({ auth, searching = false }: { auth: AuthRuntime; searching?: boolean }) {
  const [params, setParams] = useSearchParams(), q = params.get('q') ?? '', tag = params.get('tag') ?? ''
  const sort = params.get('sort') === 'trending' ? 'trending' : 'latest'
  const { register, handleSubmit, reset } = useForm<{ q: string; tag: string }>({ defaultValues: { q, tag } })
  useEffect(() => { reset({ q, tag }) }, [q, tag, reset])
  const enabled = !searching || (!!(q.trim() || tag.trim()) && q.length <= 400 && tag.length <= 64 && !/[\u0000-\u001f\u007f]/.test(q + tag))
  const result = useInfiniteQuery({ queryKey: [...publicationKeys.collections, searching ? 'search' : 'explore', searching ? { q, tag } : sort],
    queryFn: ({ pageParam, signal }) => searching ? discoveryApi.search(auth, q, tag, pageParam, signal) : discoveryApi.explore(auth, sort, pageParam, signal), enabled,
    initialPageParam: null as string | null, getNextPageParam: p => p.nextCursor ?? undefined, retry: false, staleTime: 0 }, auth.queries)
  const items = [...new Map(result.data?.pages.flatMap(p => p.items).map(p => [p.id, p] as const) ?? []).values()]
  return <PublicShell auth={auth}><div className="public-container"><header className="public-heading"><div><h1>{searching ? 'Search public notes' : 'Explore'}</h1></div>{!searching && <Link className="button button-secondary" to="/explore/search">Search public notes</Link>}</header>
    {searching ? <form className="public-search-form" onSubmit={handleSubmit(v => setParams(new URLSearchParams({ ...(v.q.trim() ? { q: v.q.trim() } : {}), ...(v.tag.trim() ? { tag: v.tag.trim() } : {}) })))}><div><label htmlFor="public-q">Keywords</label><input id="public-q" maxLength={400} {...register('q')} /></div><div><label htmlFor="public-tag">Tag</label><input id="public-tag" maxLength={64} {...register('tag')} /></div><button>Search</button></form>
      : <nav className="public-tabs" aria-label="Explore order"><Link to="/explore" aria-current={sort === 'latest' ? 'page' : undefined}>Latest</Link><Link to="/explore?sort=trending" aria-current={sort === 'trending' ? 'page' : undefined}>Trending</Link></nav>}
    {!searching && sort === 'trending' && <p className="public-trending-help">Ranked by likes and approximate views, with newer notes weighted more heavily.</p>}
    {!enabled ? <div className="public-state"><h2>Start with a word or a tag.</h2><p>Search is public-only and uses keyword and fuzzy matching, not AI.</p></div>
      : result.isPending ? <p className="public-state" role="status">Opening the library…</p>
        : result.isError ? <PublicFailure error={result.error} retry={() => void result.refetch()} />
          : items.length ? <PublicCards items={items} /> : <div className="public-state"><h2>{searching ? 'No matches' : 'No public notes yet'}</h2><p>{searching ? 'Try another keyword or remove the tag filter.' : 'Active published notes will appear here.'}</p></div>}
    {result.hasNextPage && !result.isError && <button className="button-secondary" disabled={result.isFetchingNextPage} onClick={() => void result.fetchNextPage()}>More public notes</button>}
  </div></PublicShell>
}
export function AuthorPage({ auth }: { auth: AuthRuntime }) {
  const { handle: rawHandle = '' } = useParams(), handle = rawHandle.toLowerCase(), valid = validHandle(handle)
  const author = useQuery({ queryKey: publicationKeys.author(handle), queryFn: ({ signal }) => discoveryApi.author(auth, handle, signal), enabled: valid, retry: false }, auth.queries)
  const publications = useInfiniteQuery({ queryKey: [...publicationKeys.collections, 'author', handle], queryFn: ({ pageParam, signal }) => discoveryApi.authorPublications(auth, handle, pageParam, signal), enabled: valid && !!author.data && !author.isError,
    initialPageParam: null as string | null, getNextPageParam: p => p.nextCursor ?? undefined, retry: false }, auth.queries)
  const items = [...new Map(publications.data?.pages.flatMap(p => p.items).map(p => [p.id, p] as const) ?? []).values()]
  return <PublicShell auth={auth}><div className="public-container">{!valid ? <div className="public-state"><h1>Author unavailable</h1></div> : author.isPending ? <p role="status">Loading author…</p> : author.isError ? <PublicFailure error={author.error} retry={() => void author.refetch()} /> : author.data && <><header className="public-author-header"><PublicAuthorView key={author.data.avatarUrl} author={author.data} large /><h1>{author.data.displayName || author.data.handle}</h1><p>{author.data.biography}</p></header><h2>Published notes</h2>
    {publications.isError ? <PublicFailure error={publications.error} retry={() => void publications.refetch()} /> : publications.isPending ? <p role="status">Loading public notes…</p> : items.length ? <PublicCards items={items} /> : <p className="public-state">No active public notes.</p>}
    {publications.hasNextPage && !publications.isError && <button className="button-secondary" disabled={publications.isFetchingNextPage} onClick={() => void publications.fetchNextPage()}>More by this author</button>}</>}</div></PublicShell>
}
export function PublicationAlias() { const { publicationId } = useParams(); return validId(publicationId) ? <Navigate to={`/publication/${publicationId}`} replace /> : <Navigate to="/publication/unavailable" replace /> }

function ReportForm({ auth, id, onClose, onSuccess, opener }: { auth: AuthRuntime; id: string; onClose: () => void; onSuccess: () => void; opener: HTMLElement | null }) {
  const current = usePrivateScope(auth), busyRef = useRef(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [failure, setFailure] = useState<unknown>(null)
  const cooldown = useRetryAfter(failure)
  const { register, handleSubmit, formState: { errors } } = useForm<{ category: string; description: string }>({ defaultValues: { category: 'harmfulContent', description: '' } })
  const submit = handleSubmit(async v => {
    if (busyRef.current || cooldown) return
    busyRef.current = true; setBusy(true); setError('')
    try { await discoveryApi.report(auth, id, v.category, v.description.trim()); if (current()) onSuccess() }
    catch (e) { if (current()) { if (e instanceof ApiProblemError && e.problem.status === 404) publicationChanged(auth, id); setFailure(e); setError(featureError(e)) } } finally { busyRef.current = false; if (current()) setBusy(false) }
  })
  return <FormDialog title="Report this public note" onClose={onClose} busy={busy} opener={opener}><p>Tell the safety team what needs review. Reporting never shares a private Note.</p><form onSubmit={submit}><label htmlFor="report-category">Reason</label><select id="report-category" disabled={busy} {...register('category')}><option value="harmfulContent">Harmful content</option><option value="spam">Spam</option><option value="harassment">Harassment</option><option value="other">Other</option></select>
    <label htmlFor="report-description">Description</label><textarea id="report-description" rows={5} maxLength={2000} disabled={busy} aria-invalid={!!errors.description} aria-describedby="report-help" {...register('description', { validate: v => v.trim().length > 0 && !/[\u0000-\u001f\u007f]/.test(v) || 'Enter a description on one line, up to 2,000 characters.' })} /><p id="report-help">Up to 2,000 characters, without line breaks.</p>{errors.description && <p role="alert">{errors.description.message}</p>}{error && <p role="alert">{error}</p>}{cooldown > 0 && <p role="status">Try again in {cooldown} seconds.</p>}<button disabled={busy || !!cooldown}>Submit report</button></form></FormDialog>
}
export function PublicReaderPage({ auth }: { auth: AuthRuntime }) {
  const { publicationId: id = '' } = useParams(), navigate = useNavigate(), location = useLocation(), current = usePrivateScope(auth)
  const article = useQuery({ queryKey: publicationKeys.article(auth, id), queryFn: ({ signal }) => publishingApi.public(auth, id, signal), enabled: validId(id) && auth.state !== 'unknown', retry: false, staleTime: 0 }, auth.queries)
  const [report, setReport] = useState(false), [reported, setReported] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [copied, setCopied] = useState(false)
  const pending = useRef(false), reportOpener = useRef<HTMLElement | null>(null), scope = auth.session.viewerScope
  useEffect(() => { pending.current = false; setReport(false); setReported(false); setError(''); setBusy(false) }, [id, scope])
  function signIn() { auth.signInDestination.remember(location.pathname); navigate('/login', { state: { from: location.pathname } }) }
  async function like() {
    if (auth.state !== 'authenticated') { signIn(); return }
    if (pending.current || !article.data || article.data.viewerLiked === undefined) return
    const key = publicationKeys.article(auth, id), desired = !article.data.viewerLiked
    pending.current = true; setBusy(true); setError('')
    try { await discoveryApi.like(auth, id, desired); if (!current()) return
      // No optimistic count: read the backend's effective state and aggregate.
      await auth.queries.invalidateQueries({ queryKey: key, exact: true })
      void auth.queries.invalidateQueries({ queryKey: publicationKeys.collections })
    } catch (e) { if (current()) { if (e instanceof ApiProblemError && e.problem.status === 404) publicationChanged(auth, id); setError(featureError(e)) } } finally { if (current()) { pending.current = false; setBusy(false) } }
  }
  async function copy() { try { await navigator.clipboard.writeText(`${locationOrigin()}/publication/${id}`); setCopied(true) } catch { setError('Copy is unavailable. Use the address in your browser.') } }
  const p = article.data
  useEffect(() => { if (!p || article.isError) setReport(false) }, [p, article.isError])
  return <><PublicShell auth={auth} modal={report}><div className="public-container public-reader-container">{!validId(id) ? <div className="public-state"><h1>Publication unavailable</h1><Link to="/explore">Explore public notes</Link></div> : article.isPending ? <p role="status">Opening the public note…</p> : article.isError ? <PublicFailure error={article.error} retry={() => void article.refetch()} /> : !p ? <p className="public-state" role="status">This public copy is no longer available. Refreshing availability…</p> : <article className="public-reader"><header><Link to="/explore" className="public-back">← Explore</Link><PublicTags values={p.tags} /><h1>{p.title}</h1><div className="public-reader-byline"><PublicAuthorView key={p.author.avatarUrl} author={p.author} /><time dateTime={p.publishedAt}>{dateLabel(p.publishedAt)}</time></div></header>
    <MarkdownView markdown={p.markdown} /><PublicMediaList media={p.media} />
    <footer className="public-reader-actions"><span>{p.engagement.likeCount} likes · {p.engagement.approximateViewCount} approximate views</span><div className="actions"><button type="button" className="button-secondary" disabled={busy || (auth.state === 'authenticated' && p.viewerLiked === undefined)} aria-pressed={p.viewerLiked ?? false} onClick={() => void like()}>{p.viewerLiked ? 'Unlike' : 'Like'}</button><button type="button" className="button-secondary" onClick={() => void copy()}>Copy link</button><button type="button" className="button-secondary" onClick={e => { reportOpener.current = e.currentTarget; if (auth.state === 'authenticated') setReport(true); else signIn() }}>Report</button></div>{copied && <p role="status">Public link copied.</p>}{reported && <p role="status">Report received. Thank you for helping keep this space safe.</p>}{error && <p role="alert">{error}</p>}</footer></article>}</div></PublicShell>
    {report && auth.state === 'authenticated' && p && <ReportForm key={scope.kind === 'authenticated' ? scope.viewerEpoch : 'anonymous'} auth={auth} id={id} opener={reportOpener.current} onClose={() => setReport(false)} onSuccess={() => { setReport(false); setReported(true) }} />}</>
}
function locationOrigin() { return window.location.origin }
