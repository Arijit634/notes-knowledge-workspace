import { expect, type Page } from '@playwright/test'
/** Exact-source lifecycle regressions intentionally exercise advanced source mode. */
export async function sourceEditor(page: Page) {
  if (await page.getByRole('dialog').count() === 0) await openTools(page)
  const source = page.getByLabel('Markdown', { exact: true })
  if (await source.count() === 0) await page.getByRole('button', { name: 'Markdown source', exact: true }).click()
  return source
}
export async function openTools(page: Page) {
  if (new URL(page.url()).pathname === '/notes/new') return
  const tools = page.getByRole('button', { name: 'Note tools', exact: true })
  await expect(tools).toBeVisible()
  if (await tools.getAttribute('aria-expanded') === 'false') await tools.click()
  await expect(tools).toHaveAttribute('aria-expanded', 'true')
}
