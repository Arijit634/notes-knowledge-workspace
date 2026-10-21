import type { CSSProperties } from 'react'

const paths = {
  notes: 'M6 3h9l4 4v14H6z M14 3v5h5 M9 12h7 M9 16h6',
  ask: 'M4 4h16v12H9l-5 4z M8 8h8 M8 12h5',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 3v2 M12 19v2 M3 12h2 M19 12h2 M5.6 5.6 7 7 M17 17l1.4 1.4 M5.6 18.4 7 17 M17 7l1.4-1.4',
  plus: 'M12 5v14 M5 12h14',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15 M16 16l5 5',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  shield: 'M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6z M8 12l3 3 5-6',
  profile: 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M4 21v-3c0-6 16-6 16 0v3',
  close: 'M6 6l12 12 M6 18 18 6',
} as const
export function Icon({ name, style }: { name: keyof typeof paths; style?: CSSProperties }) {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}><path d={paths[name]} /></svg>
}
