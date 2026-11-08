/** Public HTML presentation hints only; never authentication or authorization. */
export function deploymentPresentation(document: Document) {
  const values = document.querySelector('meta[name="nkw-presentation"]')?.getAttribute('content')
  return Object.freeze({
    googleEnabled: values === 'google=enabled;email=ordinary' || values === 'google=enabled;email=synthetic' || values === 'google=enabled;email=disabled;preview=private',
    syntheticEmailOnly: values === 'google=disabled;email=synthetic' || values === 'google=enabled;email=synthetic',
    privatePreview: values === 'google=enabled;email=disabled;preview=private',
  })
}
