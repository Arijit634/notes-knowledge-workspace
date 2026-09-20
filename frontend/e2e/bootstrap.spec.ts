import { expect, test } from '@playwright/test'

test('loads the bootstrap shell without claiming product functionality', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1, name: 'Notes & Knowledge Workspace' })).toBeVisible()
  await expect(page.getByText('Product features are not part of this bootstrap.')).toBeVisible()
  await expect(page.getByRole('link')).toHaveCount(0)
  await expect(page.getByRole('button')).toHaveCount(0)
})
