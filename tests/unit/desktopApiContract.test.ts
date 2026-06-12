import {describe, expect, test} from 'vitest'
import {readFileSync} from 'node:fs'
import {resolve} from 'node:path'

const root = resolve('.')

function readWorkspaceFile(path: string) {
  return readFileSync(resolve(root, path), 'utf8')
}

describe('desktop API contract', () => {
  const clientSources = [
    readWorkspaceFile('src/services/jmcomic/JmcomicDesktopClient.ts'),
    readWorkspaceFile('src/platform/desktopCapabilities.ts'),
    readWorkspaceFile('src/services/PdfReaderService.ts'),
    readWorkspaceFile('src/services/jmcomic/imageUrl.ts'),
  ].join('\n')
  const serverSource = readWorkspaceFile(
    'desktop-server/src/main/java/io/github/jukomu/jqviewer/desktop/http/DesktopServer.java',
  )

  const apiRoutes = [
    {client: "'/init-status'", server: 'path.equals("/api/init-status")'},
    {client: "'/search'", server: 'path.equals("/api/search")'},
    {client: "'/categories'", server: 'path.equals("/api/categories")'},
    {client: '`/albums/${encodeURIComponent(id)}`', server: 'path.startsWith("/api/albums/")'},
    {client: '`/photos/${encodeURIComponent(id)}`', server: 'path.startsWith("/api/photos/")'},
    {client: '`/comments?${params}`', server: 'path.equals("/api/comments")'},
    {client: "'/favorites'", server: 'path.equals("/api/favorites")'},
    {client: "'/favorites/toggle-like'", server: 'path.equals("/api/favorites/toggle-like")'},
    {client: "'/favorites/toggle'", server: 'path.equals("/api/favorites/toggle")'},
    {client: "'/favorites/folders/manage'", server: 'path.equals("/api/favorites/folders/manage")'},
    {client: "'/auth/login'", server: 'path.equals("/api/auth/login")'},
    {client: "'/auth/logout'", server: 'path.equals("/api/auth/logout")'},
    {client: "'/auth/state'", server: 'path.equals("/api/auth/state")'},
    {client: '`/users/${encodeURIComponent(uid)}`', server: 'path.startsWith("/api/users/")'},
    {client: "'/history/browse'", server: 'path.equals("/api/history/browse")'},
    {client: '`/history/browse/${id}`', server: 'path.startsWith("/api/history/browse/")'},
    {client: '`/history/parse?${params}`', server: 'path.equals("/api/history/parse")'},
    {client: '`/history/parse/${id}`', server: 'path.startsWith("/api/history/parse/")'},
    {client: "'/settings'", server: 'path.equals("/api/settings")'},
    {client: "'/preload-images'", server: 'path.equals("/api/preload-images")'},
    {client: "'/cache/capacity'", server: 'path.equals("/api/cache/capacity")'},
    {client: "'/cache/clear'", server: 'path.equals("/api/cache/clear")'},
    {client: "'/downloads'", server: 'path.equals("/api/downloads")'},
    {client: '`/downloaded/${encodeURIComponent(albumId)}/${encodeURIComponent(chapterId)}`', server: 'path.startsWith("/api/downloaded/")'},
    {client: "'/events'", server: 'createContext("/events"'},
    {client: "'/files/roots'", server: 'path.equals("/api/files/roots")'},
    {client: "'/files/pick-directory'", server: 'path.equals("/api/files/pick-directory")'},
    {client: "'/files/check'", server: 'path.equals("/api/files/check")'},
    {client: "'/diagnostics/status'", server: 'path.equals("/api/diagnostics/status")'},
    {client: "'/diagnostics/open-directory'", server: 'path.equals("/api/diagnostics/open-directory")'},
    {client: "'/pdf/scan'", server: 'path.equals("/api/pdf/scan")'},
    {client: "'/pdf/import'", server: 'path.equals("/api/pdf/import")'},
    {client: "'/pdf/imports'", server: 'path.equals("/api/pdf/imports")'},
    {client: '`/pdf/imports/${encodeURIComponent(id)}`', server: 'path.startsWith("/api/pdf/imports/")'},
    {client: "'/local-episode-type'", server: 'path.equals("/api/local-episode-type")'},
    {client: '`/pdf/info?${params}`', server: 'path.equals("/api/pdf/info")'},
    {client: '`/api/pdf/file?path=${encodeURIComponent(filePath)}`', server: 'path.equals("/api/pdf/file")'},
    {client: '`/api/pdf/page?${params}`', server: 'path.equals("/api/pdf/page")'},
    {client: "'/pdf/export'", server: 'path.equals("/api/pdf/export")'},
  ]

  test.each(apiRoutes)('keeps client and server route aligned: $client', ({client, server}) => {
    expect(clientSources).toContain(client)
    expect(serverSource).toContain(server)
  })

  test('keeps desktop resource endpoints token protected', () => {
    expect(serverSource).toContain('createContext("/image"')
    expect(serverSource).toContain('createContext("/thumb"')
    expect(serverSource).toContain('hasValidApiToken(exchange)')
    expect(serverSource).toContain('hasValidResourceToken(exchange)')
    expect(serverSource).toContain('canUseResourceTokenForApi(exchange)')
    expect(serverSource).toContain('X-JQ-Desktop-Token')
  })

  test('keeps desktop mutable directory roots aligned', () => {
    expect(clientSources).toContain('downloadDir')
    expect(clientSources).toContain("purpose === 'download'")
    expect(serverSource).toContain('downloadDir')
    expect(serverSource).toContain('"download".equals(raw)')
    expect(serverSource).toContain('updateFileRoots')
  })

  test('keeps desktop diagnostics non-sensitive and directory-key based', () => {
    expect(clientSources).toContain('getDiagnosticsStatus')
    expect(clientSources).toContain('openDirectory(key')
    expect(serverSource).toContain('diagnosticsStatus')
    expect(serverSource).toContain('diagnosticDirectory')
    expect(serverSource).toContain('serverVersion')
    expect(serverSource).not.toContain('addProperty("token"')
    expect(serverSource).not.toContain('addProperty("cookie"')
    expect(serverSource).not.toContain('addProperty("password"')
  })
})
