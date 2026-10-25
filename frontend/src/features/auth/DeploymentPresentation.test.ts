import { afterEach, expect, it } from 'vitest'
import { deploymentPresentation } from './DeploymentPresentation'
afterEach(() => document.querySelectorAll('meta[name="nkw-presentation"]').forEach(node => node.remove()))
it('fails closed for missing, placeholder, unknown or injected flags', () => {
  expect(deploymentPresentation(document).googleEnabled).toBe(false)
  const meta = document.createElement('meta'); meta.name = 'nkw-presentation'; document.head.append(meta)
  for (const value of ['unconfigured', 'true', 'google=enabled;email=ordinary;admin=true', '<script>unsafe</script>']) {
    meta.content = value; expect(deploymentPresentation(document)).toEqual({ googleEnabled: false, syntheticEmailOnly: false })
  }
  meta.content = 'google=enabled;email=ordinary'
  expect(deploymentPresentation(document)).toEqual({ googleEnabled: true, syntheticEmailOnly: false })
  meta.content = 'google=disabled;email=synthetic'
  expect(deploymentPresentation(document)).toEqual({ googleEnabled: false, syntheticEmailOnly: true })
})
