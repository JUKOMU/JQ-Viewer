import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const apiBase = (process.env.GITEE_API_BASE || 'https://gitee.com/api/v5').replace(/\/$/, '')
const repository = process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer'

function repositoryPath() {
  const parts = repository.split('/')
  if (parts.length !== 2 || parts.some((part) => !part)) {
    throw new Error('GITEE_REPOSITORY must use owner/repository format')
  }
  return parts.map(encodeURIComponent).join('/')
}

function apiUrl(apiPath, query = {}) {
  const url = new URL(apiBase + apiPath)
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(key, String(value))
    }
  }
  return url
}

async function request(apiPath, options = {}) {
  const response = await fetch(apiUrl(apiPath, options.query), {
    method: options.method || 'GET',
    headers: options.headers,
    body: options.body,
  })
  const raw = await response.text()
  let payload = null
  if (raw) {
    try {
      payload = JSON.parse(raw)
    } catch {
      payload = raw
    }
  }

  if (!response.ok) {
    const message =
      payload && typeof payload === 'object' && payload.message
        ? payload.message
        : 'request failed'
    const error = new Error('Gitee API ' + response.status + ' ' + options.label + ': ' + message)
    error.status = response.status
    throw error
  }

  return payload
}

function formBody(token, fields) {
  return new URLSearchParams({
    access_token: token,
    ...fields,
  })
}

async function getRelease(tag) {
  const apiPath =
    '/repos/' +
    repositoryPath() +
    '/releases/tags/' +
    encodeURIComponent(tag)
  try {
    return await request(apiPath, { label: 'get release by tag' })
  } catch (error) {
    if (error.status === 404) return null
    throw error
  }
}

async function upsertRelease({ token, tag, notes, targetCommit, prerelease }) {
  const existing = await getRelease(tag)
  const fields = {
    tag_name: tag,
    name: tag,
    body: notes,
    prerelease: String(prerelease),
  }

  if (existing) {
    const apiPath = '/repos/' + repositoryPath() + '/releases/' + existing.id
    return request(apiPath, {
      method: 'PATCH',
      body: formBody(token, fields),
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      label: 'update release',
    })
  }

  const apiPath = '/repos/' + repositoryPath() + '/releases'
  return request(apiPath, {
    method: 'POST',
    body: formBody(token, {
      ...fields,
      target_commitish: targetCommit,
    }),
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    label: 'create release',
  })
}

async function listAssets(releaseId) {
  const apiPath =
    '/repos/' +
    repositoryPath() +
    '/releases/' +
    releaseId +
    '/attach_files'
  const assets = await request(apiPath, {
    query: {per_page: 100},
    label: 'list release assets',
  })
  return Array.isArray(assets) ? assets : []
}

async function deleteAsset(token, releaseId, assetId) {
  const apiPath =
    '/repos/' +
    repositoryPath() +
    '/releases/' +
    releaseId +
    '/attach_files/' +
    assetId
  await request(apiPath, {
    method: 'DELETE',
    query: {access_token: token},
    label: 'delete release asset',
  })
}

async function uploadAsset(token, releaseId, filePath) {
  const apiPath =
    '/repos/' +
    repositoryPath() +
    '/releases/' +
    releaseId +
    '/attach_files'
  const file = await fs.promises.readFile(filePath)
  const form = new FormData()
  form.set('access_token', token)
  form.set('file', new Blob([file]), path.basename(filePath))
  return request(apiPath, {
    method: 'POST',
    body: form,
    label: 'upload release asset ' + path.basename(filePath),
  })
}

async function replaceAsset(token, releaseId, filePath) {
  const name = path.basename(filePath)
  const assets = await listAssets(releaseId)
  const oldAsset = assets.find((asset) => asset.name === name)
  if (oldAsset) {
    await deleteAsset(token, releaseId, oldAsset.id)
  }

  const uploaded = await uploadAsset(token, releaseId, filePath)
  if (!uploaded || uploaded.name !== name) {
    throw new Error('Gitee uploaded asset name mismatch for ' + name)
  }
  if (typeof uploaded.browser_download_url !== 'string' || !uploaded.browser_download_url) {
    throw new Error('Gitee did not return a public download URL for ' + name)
  }

  const localSize = fs.statSync(filePath).size
  if (Number(uploaded.size) !== localSize) {
    throw new Error(
      'Gitee asset size mismatch for ' +
        name +
        ': expected ' +
        localSize +
        ', got ' +
        uploaded.size,
    )
  }

  return uploaded
}

async function main() {
  const [tag, apkPath, notesPath, targetCommit, latestJsonPath] = process.argv.slice(2)
  const token = process.env.GITEE_TOKEN
  if (!token) {
    throw new Error('GITEE_TOKEN is required')
  }
  if (!tag || !apkPath || !notesPath || !targetCommit) {
    throw new Error(
      'usage: node scripts/publish-gitee-release.mjs <tag> <apk> <notes> <target-commit> [latest-json]',
    )
  }

  const {validateReleaseNotes} = await import('./release-assets.mjs')
  const notes = validateReleaseNotes(fs.readFileSync(notesPath, 'utf8'), tag)
  const prerelease = process.env.RELEASE_PRERELEASE === 'true'
  const release = await upsertRelease({
    token,
    tag,
    notes,
    targetCommit,
    prerelease,
  })
  if (!release || !release.id) {
    throw new Error('Gitee release response did not contain an id')
  }

  const apk = await replaceAsset(token, release.id, apkPath)
  let latest = null
  if (latestJsonPath) {
    latest = await replaceAsset(token, release.id, latestJsonPath)
  }

  console.log(
    JSON.stringify(
      {
        releaseId: release.id,
        tag,
        prerelease,
        apk: {
          name: apk.name,
          size: apk.size,
          browserDownloadUrl: apk.browser_download_url,
        },
        latestJson: latest
          ? {
              name: latest.name,
              size: latest.size,
              browserDownloadUrl: latest.browser_download_url,
            }
          : null,
      },
      null,
      2,
    ),
  )
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('Gitee release failed: ' + error.message)
    process.exitCode = 1
  })
}
