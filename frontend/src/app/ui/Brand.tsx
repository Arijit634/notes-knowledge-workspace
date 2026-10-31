import { Link } from 'react-router'

export function Brand() {
  return <Link className="brand app-brand" to="/"><span className="brand-gem" aria-hidden="true">n.</span><span>Notes &amp; Knowledge</span></Link>
}

export function GoogleMark() {
  return <svg className="google-mark" width="20" height="20" viewBox="0 0 48 48" aria-hidden="true">
    <path fill="#4285f4" d="M43.6 24.5c0-1.5-.1-2.8-.4-4.2H24v8h11c-.5 2.6-2 4.8-4.2 6.3v5.2h6.8c4-3.7 6-9 6-15.3Z" />
    <path fill="#34a853" d="M24 44c5.5 0 10.1-1.8 13.5-5l-6.8-5.2c-1.8 1.2-4.1 1.9-6.7 1.9-5.2 0-9.6-3.5-11.2-8.2H5.9v5.4A20.4 20.4 0 0 0 24 44Z" />
    <path fill="#fbbc05" d="M12.8 27.5a12 12 0 0 1 0-7.1V15H5.9a20 20 0 0 0 0 18l6.9-5.5Z" />
    <path fill="#ea4335" d="M24 12.3c3 0 5.6 1 7.7 3l5.8-5.8A19.5 19.5 0 0 0 24 4 20.4 20.4 0 0 0 5.9 15l6.9 5.4c1.6-4.7 6-8.1 11.2-8.1Z" />
  </svg>
}
