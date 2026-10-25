/** Public HTML presentation hints only; never authentication or authorization. */
export function deploymentPresentation(document: Document) {
  const values = document.querySelector('meta[name="nkw-presentation"]')?.getAttribute('content')
  return Object.freeze({
    googleEnabled: values === 'google=enabled;email=ordinary' || values === 'google=enabled;email=synthetic',
    syntheticEmailOnly: values === 'google=disabled;email=synthetic' || values === 'google=enabled;email=synthetic',
  })
}
