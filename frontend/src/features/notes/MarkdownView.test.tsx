import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownView } from './MarkdownView'

describe('safe Markdown preview', () => {
  it('renders GFM while dropping raw HTML and preventing external image fetch', () => {
    const { container } = render(<MarkdownView markdown={'**Bold** ~~old~~\n\n<img src="https://evil.invalid/raw">\n\n![alt](https://evil.invalid/image)\n\n[bad](javascript:alert(1))'} />)
    expect(screen.getByText('Bold').tagName).toBe('STRONG')
    expect(screen.getByText('old').tagName).toBe('DEL')
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('a[href^="javascript:"]')).toBeNull()
    expect(screen.getByText('[Image: alt]')).toBeTruthy()
  })
})
