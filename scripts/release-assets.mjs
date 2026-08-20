import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const releaseTagPattern = /^v([0-9]+\.[0-9]+\.[0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
const certificateDigestPattern = /^[0-9A-Fa-f]{64}$/
export const releaseAbis = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86']

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
  variants = [],
}) {
  const match = releaseTag.match(releaseTagPattern)
  if (!match) {
    throw new Error('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  }
  if (!certificateDigestPattern.test(signingCertificateSha256)) {
    throw new Error('signing certificate SHA-256 must be a 64-character hexadecimal digest')
  }

  const encode = (value) => encodeURIComponent(value)
  const downloadUrl = (host, repository, assetName) =>
    host + '/' + repository + '/releases/download/' + encode(releaseTag) + '/' + encode(assetName)

  if (variants.length > 0) {
    const variantAbis = variants.map((variant) => variant.abi)
    if (
      variantAbis.length !== releaseAbis.length ||
      new Set(variantAbis).size !== releaseAbis.length ||
      !releaseAbis.every((abi) => variantAbis.includes(abi))
    ) {
      throw new Error('APK variants must contain each supported ABI exactly once')
    }
  }

  const buildArtifact = (artifact) => {
    if (!Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes <= 0) {
      throw new Error('APK size must be a positive integer')
    }
    if (!/^[0-9a-f]{64}$/i.test(artifact.sha256)) {
      throw new Error('APK SHA-256 must be a 64-character hexadecimal digest')
    }
    return {
      ...(artifact.abi ? { abi: artifact.abi } : {}),
      apkName: artifact.apkName,
      sizeBytes: artifact.sizeBytes,
      sha256: artifact.sha256.toLowerCase(),
      sources: {
        github: downloadUrl('https://github.com', githubRepository, artifact.apkName),
        gitee: downloadUrl('https://gitee.com', giteeRepository, artifact.apkName),
      },
    }
  }

  const universal = buildArtifact({ apkName, sizeBytes, sha256 })

  return {
    schemaVersion: 1,
    tag: releaseTag,
    versionName,
    versionCode,
    packageName,
    apkName: universal.apkName,
    sizeBytes: universal.sizeBytes,
    sha256: universal.sha256,
    signingCertificateSha256: signingCertificateSha256.toUpperCase(),
    releaseNotes,
    sources: universal.sources,
    ...(variants.length > 0 ? { variants: variants.map(buildArtifact) } : {}),
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
  const [releaseTag, outputPath, ...apkPaths] = process.argv.slice(2)
  if (!releaseTag || !outputPath || apkPaths.length !== releaseAbis.length + 1) {
    throw new Error(
      'usage: node scripts/release-assets.mjs <tag> <output> <universal-apk> <four-abi-apks>',
    )
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
    throw new Error('release tag version must match package.json version ' + packageJson.version)
  }

  const releaseNotes = validateReleaseNotes(
    fs.readFileSync(new URL('../.github/release-notes.md', import.meta.url), 'utf8'),
    releaseTag,
  )
  const expectedCertificate = process.env.EXPECTED_RELEASE_CERT_SHA256
  if (!expectedCertificate) {
    throw new Error('EXPECTED_RELEASE_CERT_SHA256 is required')
  }

  const artifactFor = async (apkPath, abi) => {
    const apkStat = fs.statSync(apkPath)
    return {
      ...(abi ? { abi } : {}),
      apkName: path.basename(apkPath),
      sizeBytes: apkStat.size,
      sha256: await sha256File(apkPath),
    }
  }
  const universalPath = apkPaths.find((apkPath) => /-universal\.apk$/i.test(apkPath))
  if (!universalPath) {
    throw new Error('universal APK is required')
  }
  const variantPaths = new Map()
  for (const abi of releaseAbis) {
    const apkPath = apkPaths.find((candidate) => candidate.endsWith('-' + abi + '.apk'))
    if (!apkPath) {
      throw new Error('missing APK for ABI ' + abi)
    }
    variantPaths.set(abi, apkPath)
  }
  const universal = await artifactFor(universalPath)
  const variants = await Promise.all(
    releaseAbis.map((abi) => artifactFor(variantPaths.get(abi), abi)),
  )
  const manifest = buildReleaseManifest({
    releaseTag,
    ...universal,
    signingCertificateSha256: expectedCertificate,
    releaseNotes,
    githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
    giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
    packageName: metadata.applicationId,
    versionCode: metadata.versionCode,
    versionName: metadata.versionName,
    variants,
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
