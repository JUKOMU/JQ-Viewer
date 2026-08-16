import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Agent } from 'undici'

const apiBase = (process.env.GITEE_API_BASE || 'https://gitee.com/api/v5').replace(/\/$/, '')
const repository = process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer'
const assetUploadTimeoutMs = 10 * 60 * 1000

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
  let response
  try {
    response = await fetch(apiUrl(apiPath, options.query), {
      method: options.method || 'GET',
      headers: options.headers,
      body: options.body,
      dispatcher: options.dispatcher,
    })
  } catch (error) {
    const cause = error && typeof error === 'object' ? error.cause : null
    const causeDetails = [cause?.code, cause?.message].filter(Boolean).join(': ')
    const detail = causeDetails ? ' (' + causeDetails + ')' : ''
    throw new Error(
      'Gitee ' + options.label + ' transport failed: ' + error.message + detail,
      {cause: error},
    )
  }
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

async function downloadAsset(asset) {
  if (typeof asset.browser_download_url !== 'string' || !asset.browser_download_url) {
    throw new Error('Gitee existing asset has no public download URL for ' + asset.name)
  }
  const response = await fetch(asset.browser_download_url)
  if (!response.ok) {
    throw new Error(
      'Gitee existing asset download failed for ' + asset.name + ': HTTP ' + response.status,
    )
  }
  return Buffer.from(await response.arrayBuffer())
}

async function uploadAssetBytes(token, releaseId, name, bytes) {
  const apiPath =
    '/repos/' +
    repositoryPath() +
    '/releases/' +
    releaseId +
    '/attach_files'
  const form = new FormData()
  form.set('access_token', token)
  form.set('file', new Blob([bytes]), name)
  const dispatcher = new Agent({
    headersTimeout: assetUploadTimeoutMs,
    bodyTimeout: assetUploadTimeoutMs,
  })
  try {
    return await request(apiPath, {
      method: 'POST',
      body: form,
      dispatcher,
      label: 'upload release asset ' + name,
    })
  } finally {
    await dispatcher.close()
  }
}

function validateUploadedAsset(uploaded, name, expectedSize) {
  if (!uploaded || uploaded.name !== name) {
    throw new Error('Gitee uploaded asset name mismatch for ' + name)
  }
  if (typeof uploaded.browser_download_url !== 'string' || !uploaded.browser_download_url) {
    throw new Error('Gitee did not return a public download URL for ' + name)
  }
  if (Number(uploaded.size) !== expectedSize) {
    throw new Error(
      'Gitee asset size mismatch for ' +
        name +
        ': expected ' +
        expectedSize +
        ', got ' +
        uploaded.size,
    )
  }
  return uploaded
}

export async function replaceAsset(token, releaseId, filePath) {
  const name = path.basename(filePath)
  const localBytes = await fs.promises.readFile(filePath)
  const assets = await listAssets(releaseId)
  const oldAsset = assets.find((asset) => asset.name === name)
  let oldBytes = null
  if (oldAsset) {
    oldBytes = await downloadAsset(oldAsset)
    if (oldBytes.equals(localBytes)) {
      return {...oldAsset, size: oldBytes.length}
    }
    await deleteAsset(token, releaseId, oldAsset.id)
  }

  let uploaded = null
  try {
    uploaded = await uploadAssetBytes(token, releaseId, name, localBytes)
    return validateUploadedAsset(uploaded, name, localBytes.length)
  } catch (error) {
    if (!oldAsset || !oldBytes) {
      throw error
    }
    try {
      if (uploaded && uploaded.id) {
        await deleteAsset(token, releaseId, uploaded.id)
      }
      const restored = await uploadAssetBytes(token, releaseId, name, oldBytes)
      validateUploadedAsset(restored, name, oldBytes.length)
    } catch (restoreError) {
      const failure = new Error(
        'Gitee replacement failed for ' + name +
          '; restoring the previous asset also failed: ' + restoreError.message,
      )
      failure.cause = error
      throw failure
    }
    throw error
  }
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
