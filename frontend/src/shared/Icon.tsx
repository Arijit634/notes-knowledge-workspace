/** A small, consistent, local icon set. Icons never carry meaning without a label. */
export function Icon({ name }: { name: 'notes' | 'security' | 'logout' }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    {name === 'notes' && <><path d="M6 3h9l4 4v14H6z" /><path d="M14 3v5h5M9 12h7M9 16h5" /></>}
    {name === 'security' && <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z" /><path d="m8 12 3 3 5-6" /></>}
    {name === 'logout' && <><path d="M10 4H4v16h6M9 12h12m-4-4 4 4-4 4" /></>}
  </svg>
}
