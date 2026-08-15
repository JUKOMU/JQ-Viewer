import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const releaseTagPattern =
  /^v([0-9]+\.[0-9]+\.[0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
const certificateDigestPattern = /^[0-9A-Fa-f]{64}$/

export function validateReleaseNotes(notes, releaseTag) {
  const normalized = String(notes).replace(/\r\n?/g, '\n').trim()
  if (!normalized) {
    throw new Error('release notes must not be empty')
  }

  const lines = normalized.split('\n')
  if (lines[0].trim() !== '# ' + releaseTag) {
    throw new Error('release notes first heading must match release tag ' + releaseTag)
  }
  if (!lines.slice(1).join('\n').trim()) {
    throw new Error('release notes must contain content after the version heading')
  }

  return normalized
}

export function parseAndroidMetadata(buildGradle) {
  const applicationId = buildGradle.match(/applicationId\s+["']([^"']+)["']/)?.[1]
  const versionCode = buildGradle.match(/versionCode\s+([0-9]+)/)?.[1]
  const versionName = buildGradle.match(/versionName\s+["']([^"']+)["']/)?.[1]

  if (!applicationId || !versionCode || !versionName) {
    throw new Error('Android release metadata could not be read from android/app/build.gradle')
  }

  return {
    applicationId,
    versionCode: Number(versionCode),
    versionName,
  }
}

export function buildReleaseManifest({
  releaseTag,
  apkName,
  sizeBytes,
  sha256,
  signingCertificateSha256,
  releaseNotes,
  githubRepository,
  giteeRepository,
  packageName,
  versionCode,
  versionName,
}) {
  const match = releaseTag.match(releaseTagPattern)
  if (!match) {
    throw new Error('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  }
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) {
    throw new Error('APK size must be a positive integer')
  }
  if (!/^[0-9a-f]{64}$/i.test(sha256)) {
    throw new Error('APK SHA-256 must be a 64-character hexadecimal digest')
  }
  if (!certificateDigestPattern.test(signingCertificateSha256)) {
    throw new Error('signing certificate SHA-256 must be a 64-character hexadecimal digest')
  }

  const encode = (value) => encodeURIComponent(value)
  const downloadUrl = (host, repository) =>
    host +
    '/' +
    repository +
    '/releases/download/' +
    encode(releaseTag) +
    '/' +
    encode(apkName)

  return {
    schemaVersion: 1,
    tag: releaseTag,
    versionName,
    versionCode,
    packageName,
    apkName,
    sizeBytes,
    sha256: sha256.toLowerCase(),
    signingCertificateSha256: signingCertificateSha256.toUpperCase(),
    releaseNotes,
    sources: {
      github: downloadUrl('https://github.com', githubRepository),
      gitee: downloadUrl('https://gitee.com', giteeRepository),
    },
  }
}

export function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    const stream = fs.createReadStream(filePath)
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('error', reject)
    stream.on('end', () => resolve(hash.digest('hex')))
  })
}

async function main() {
  const [releaseTag, apkPath, outputPath = 'latest.json'] = process.argv.slice(2)
  if (!releaseTag || !apkPath) {
    throw new Error('usage: node scripts/release-assets.mjs <tag> <apk> [output]')
  }

  const packageJson = JSON.parse(
    fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'),
  )
  const buildGradle = fs.readFileSync(
    new URL('../android/app/build.gradle', import.meta.url),
    'utf8',
  )
  const metadata = parseAndroidMetadata(buildGradle)
  const tagMatch = releaseTag.match(releaseTagPattern)

  if (!tagMatch || tagMatch[1] !== packageJson.version) {
    throw new Error(
      'release tag version must match package.json version ' + packageJson.version,
    )
  }

  const releaseNotes = validateReleaseNotes(
    fs.readFileSync(new URL('../.github/release-notes.md', import.meta.url), 'utf8'),
    releaseTag,
  )
  const expectedCertificate = process.env.EXPECTED_RELEASE_CERT_SHA256
  if (!expectedCertificate) {
    throw new Error('EXPECTED_RELEASE_CERT_SHA256 is required')
  }

  const apkStat = fs.statSync(apkPath)
  const manifest = buildReleaseManifest({
    releaseTag,
    apkName: path.basename(apkPath),
    sizeBytes: apkStat.size,
    sha256: await sha256File(apkPath),
    signingCertificateSha256: expectedCertificate,
    releaseNotes,
    githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
    giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
    packageName: metadata.applicationId,
    versionCode: metadata.versionCode,
    versionName: metadata.versionName,
  })

  fs.writeFileSync(outputPath, JSON.stringify(manifest, null, 2) + '\n')
  console.log('Release metadata prepared: ' + outputPath)
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('Release metadata failed: ' + error.message)
    process.exitCode = 1
  })
}
