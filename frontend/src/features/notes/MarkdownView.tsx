import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

function safeLink(value: string): string {
  try {
    const parsed = new URL(value, 'https://notes.invalid')
    if (!['https:', 'http:', 'mailto:'].includes(parsed.protocol)) return ''
    return value
  } catch { return '' }
}

/** Markdown syntax is rendered as React nodes; arbitrary media is never fetched. */
export function MarkdownView({ markdown }: { markdown: string }) {
  return <div className="notes-preview-content">
    <ReactMarkdown skipHtml remarkPlugins={[remarkGfm]} urlTransform={safeLink}
      components={{
        img: ({ alt }) => <span className="notes-image-placeholder">[Image: {alt || 'No description'}]</span>,
        a: ({ href, children }) => href
          ? <a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{children}</a>
          : <span>{children}</span>,
      }}>{markdown}</ReactMarkdown>
  </div>
}
