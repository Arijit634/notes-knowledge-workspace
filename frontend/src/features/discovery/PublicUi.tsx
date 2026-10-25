import { useState, type ReactNode } from 'react'
import { Link, NavLink } from 'react-router'
import type { AuthRuntime } from '../auth/AuthRuntime'
import type { PublicAuthor, PublicMedia } from '../publishing/PublishingApi'
import type { PublicCard } from './DiscoveryApi'
import { sizeLabel } from './DiscoveryApi'
import { featureError } from '../../app/ui/featureError'
import { ApiProblemError } from '../../app/api/ProblemDetailsDecoder'
import { useRetryAfter } from '../../app/ui/useRetryAfter'
import './PublicExperience.css'

export const dateLabel = (date: string) => { const d = new Date(date); return Number.isNaN(d.valueOf()) ? 'Date unavailable' : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) }
export function PublicShell({ auth, children, modal = false }: { auth: AuthRuntime; children: ReactNode; modal?: boolean }) {
  return <div className="public-shell"><div inert={modal}><a className="notes-skip" href="#public-content">Skip to content</a><header className="public-topbar"><Link className="public-brand" to="/explore"><span className="notes-brand-mark" aria-hidden="true">n.</span><span>Notes &amp; Knowledge</span></Link><nav aria-label="Public navigation"><NavLink to="/explore" end>Explore</NavLink><NavLink to="/explore/search">Search</NavLink>{auth.state === 'authenticated' ? <Link className="button button-secondary" to="/notes">Workspace</Link> : <Link className="button button-secondary" to="/login">Log in</Link>}</nav></header><main id="public-content" tabIndex={-1}>{children}</main><footer className="public-footer">Ideas worth keeping. Knowledge worth sharing.</footer></div></div>
}
export function PublicAuthorView({ author, large = false }: { author: PublicAuthor; large?: boolean }) {
  const [failed, setFailed] = useState(false)
  return <Link className={large ? 'public-author public-author-large' : 'public-author'} to={`/profile/${author.handle}`}>
    {author.avatarUrl && !failed ? <img src={author.avatarUrl} alt="" onError={() => setFailed(true)} /> : <span className="public-avatar" aria-hidden="true">{author.displayName.slice(0, 1).toUpperCase() || 'N'}</span>}
    <span><strong>{author.displayName || author.handle}</strong><span>@{author.handle}</span></span></Link>
}
export function PublicTags({ values }: { values: readonly string[] }) { return <ul className="public-tags" aria-label="Public tags">{values.map(t => <li key={t}><Link to={'/explore/search?' + new URLSearchParams({ tag: t })}>{t}</Link></li>)}</ul> }
export function PublicCards({ items }: { items: readonly PublicCard[] }) {
  return <ul className="public-grid">{items.map((p, i) => <li key={p.id} className={`public-card public-card-tone-${i % 4}`}><div className="public-card-rule" aria-hidden="true" />
    {p.author && <PublicAuthorView author={p.author} />}<h2><Link to={`/publication/${p.id}`}>{p.title}</Link></h2><PublicTags values={p.tags} /><div className="public-card-meta"><time dateTime={p.publishedAt}>{dateLabel(p.publishedAt)}</time>{p.engagement && <span>{p.engagement.likeCount} likes · {p.engagement.approximateViewCount} approximate views</span>}</div></li>)}</ul>
}
export function PublicFailure({ error, retry, unavailable = 'This public content is unavailable.' }: { error: unknown; retry?: () => void; unavailable?: string }) {
  const missing = error instanceof ApiProblemError && error.problem.status === 404
  const cooldown = useRetryAfter(error)
  return <div className="public-state" role="alert"><h2>{missing ? 'Not available' : 'Could not load this view'}</h2><p>{missing ? unavailable : featureError(error)}</p>{cooldown > 0 && <p>Try again in {cooldown} seconds.</p>}{retry && !missing && <button className="button-secondary" disabled={!!cooldown} onClick={retry}>Retry</button>}<Link to="/explore">Explore public notes</Link></div>
}
export function PublicMediaView({ media }: { media: PublicMedia }) {
  const [failed, setFailed] = useState(false)
  return <figure className="public-media"><figcaption><strong>{media.displayName}</strong><span>{media.mediaKind.toUpperCase()} · {sizeLabel(media.sizeBytes)}</span></figcaption>
    {failed ? <p role="status">This media is unavailable or this browser cannot decode it. It may have been removed.</p> : media.mediaKind === 'image' ? <img src={media.contentUrl} alt={media.displayName} loading="lazy" onError={() => setFailed(true)} />
      : media.mediaKind === 'audio' ? <audio controls preload="none" src={media.contentUrl} onError={() => setFailed(true)} />
        : media.mediaKind === 'video' ? <video controls preload="metadata" src={media.contentUrl} onError={() => setFailed(true)} />
          : <p>PDF document. <a href={media.contentUrl} target="_blank" rel="noopener noreferrer">Open {media.displayName}</a></p>}
    {(media.mediaKind === 'audio' || media.mediaKind === 'video') && <p className="notes-help">Playback depends on your browser’s supported codecs.</p>}</figure>
}
export function PublicMediaList({ media }: { media: readonly PublicMedia[] }) { return <div className="public-media-list">{media.map(m => <PublicMediaView key={m.id} media={m} />)}</div> }
