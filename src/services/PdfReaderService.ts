const VIRTUAL_BASE = 'https://jqviewer.local'

export function getPdfVirtualUrl(filePath: string): string {
  const encoded = btoa(unescape(encodeURIComponent(filePath)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `${VIRTUAL_BASE}/pdf/${encoded}`
}
