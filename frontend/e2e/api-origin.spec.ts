import { expect, test } from '@playwright/test'

test('Google start keeps its origin and CSRF proof without sending page details', async ({ page, baseURL }) => {
  const origin = new URL(baseURL!).origin
  let admitted = false
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.origin === 'https://accounts.google.com') {
      expect(admitted).toBe(true)
      return route.fulfill({ contentType: 'text/html', body: '<h1>Synthetic Google boundary</h1>' })
    }
    if (url.origin !== origin) throw new Error('Unexpected external request')
    if (url.pathname.startsWith('/api/auth/')) {
      if (url.pathname === '/api/auth/session') return route.fulfill({ json: { state: 'anonymous' } })
      if (url.pathname === '/api/auth/csrf') return route.fulfill({ json: { csrfToken: 'synthetic-origin-proof' } })
      if (url.pathname === '/api/auth/oidc/google/authorizations') {
        const headers = await request.allHeaders()
        admitted = headers.origin === origin && headers.referer === origin + '/'
          && headers['x-csrf-token'] === 'synthetic-origin-proof'
        expect(request.method()).toBe('POST')
        expect(request.postDataJSON()).toEqual({ intendedDestination: '/notes' })
        expect(headers.authorization).toBeUndefined()
        if (!admitted) return route.fulfill({ status: 403, body: 'Origin rejected' })
        return route.fulfill({ json: { authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?client_id=synthetic' } })
      }
      throw new Error('Unexpected authentication request')
    }
    if (request.isNavigationRequest() && request.resourceType() === 'document') {
      const response = await route.fetch()
      const body = (await response.text()).replace('content="unconfigured"', 'content="google=enabled;email=ordinary"')
      return route.fulfill({ response, body, headers: { ...response.headers(), 'Referrer-Policy': 'no-referrer' } })
    }
    return route.continue()
  })
  await page.goto('/login?syntheticMarker=must-not-be-referred#synthetic-fragment')
  await page.getByRole('button', { name: 'Continue with Google', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Synthetic Google boundary', exact: true })).toBeVisible()
  expect(admitted).toBe(true)
})
