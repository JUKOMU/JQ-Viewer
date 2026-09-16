import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const tagPattern = /^v([0-9]+\.[0-9]+\.[0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
const digestPattern = /^[0-9a-f]{64}$/i
const base64Pattern = /^[A-Za-z0-9+/]+={0,2}$/

export const desktopArtifactDefinitions = [
  {
    platform: 'windows',
    architecture: 'x64',
    compatibleArchitectures: ['x64', 'arm64'],
    packageType: 'installer',
    packageFormat: 'exe',
    suffix: 'windows-x64-installer.exe',
  },
  {
    platform: 'windows',
    architecture: 'x64',
    compatibleArchitectures: ['x64', 'arm64'],
    packageType: 'portable',
    packageFormat: 'zip',
    suffix: 'windows-x64-portable.zip',
  },
  ...['x64', 'arm64'].flatMap((architecture) => [
    {
      platform: 'linux',
      architecture,
      compatibleArchitectures: [architecture],
      packageType: 'installer',
      packageFormat: 'deb',
      suffix: `linux-${architecture}.deb`,
    },
    {
      platform: 'linux',
      architecture,
      compatibleArchitectures: [architecture],
      packageType: 'installer',
      packageFormat: 'rpm',
      suffix: `linux-${architecture}.rpm`,
    },
    {
      platform: 'linux',
      architecture,
      compatibleArchitectures: [architecture],
      packageType: 'portable',
      packageFormat: 'tar.gz',
      suffix: `linux-${architecture}-portable.tar.gz`,
    },
  ]),
]

function fail(message) {
  throw new Error(message)
}

function releaseUrl(host, repository, tag, assetName) {
  return `${host}/${repository}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(assetName)}`
}

export function expectedDesktopAssetName(version, definition) {
  return `JQ-Viewer-${version}-${definition.suffix}`
}

export function classifyDesktopAssets(version, assetPaths) {
  const byName = new Map(assetPaths.map((assetPath) => [path.basename(assetPath), assetPath]))
  if (byName.size !== assetPaths.length) fail('Desktop asset names must be unique')

  const expected = new Set()
  const classified = desktopArtifactDefinitions.map((definition) => {
    const name = expectedDesktopAssetName(version, definition)
    expected.add(name)
    const assetPath = byName.get(name)
    if (!assetPath) fail('missing Desktop release asset: ' + name)
    const stat = fs.statSync(assetPath)
    if (!stat.isFile() || stat.size <= 0) fail('Desktop release asset is empty: ' + name)
    return { ...definition, name, path: assetPath, sizeBytes: stat.size }
  })

  const unexpected = [...byName.keys()].filter((name) => !expected.has(name))
  if (unexpected.length > 0) fail('unexpected Desktop release assets: ' + unexpected.join(', '))
  return classified
}

export function sha256FileSync(filePath) {
  const hash = createHash('sha256')
  hash.update(fs.readFileSync(filePath))
  return hash.digest('hex')
}

export function buildDesktopManifest({
  releaseTag,
  publishedAt,
  artifacts,
  githubRepository = 'JUKOMU/JQ-Viewer',
  giteeRepository = 'jukomu/jq-viewer',
}) {
  const match = releaseTag.match(tagPattern)
  if (!match) fail('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  if (!Number.isFinite(Date.parse(publishedAt))) fail('publishedAt must be an ISO-8601 timestamp')
  if (artifacts.length !== desktopArtifactDefinitions.length) {
    fail('Desktop manifest requires the complete artifact matrix')
  }

  const manifest = {
    schemaVersion: 1,
    tag: releaseTag,
    version: match[1],
    channel: match[2] ? 'prerelease' : 'stable',
    publishedAt: new Date(publishedAt).toISOString(),
    artifacts: artifacts.map((artifact) => {
      if (!digestPattern.test(artifact.sha256)) fail('invalid SHA-256 for ' + artifact.name)
      if (!Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes <= 0) {
        fail('invalid size for ' + artifact.name)
      }
      return {
        name: artifact.name,
        platform: artifact.platform,
        architecture: artifact.architecture,
        compatibleArchitectures: [...artifact.compatibleArchitectures],
        packageType: artifact.packageType,
        packageFormat: artifact.packageFormat,
        sizeBytes: artifact.sizeBytes,
        sha256: artifact.sha256.toLowerCase(),
        sources: {
          github: releaseUrl('https://github.com', githubRepository, releaseTag, artifact.name),
          gitee: releaseUrl('https://gitee.com', giteeRepository, releaseTag, artifact.name),
        },
      }
    }),
  }
  validateDesktopManifestContract({ manifest, githubRepository, giteeRepository })
  return manifest
}

export function validateDesktopManifestContract({
  manifest,
  githubRepository = 'JUKOMU/JQ-Viewer',
  giteeRepository = 'jukomu/jq-viewer',
}) {
  const match = manifest?.tag?.match(tagPattern)
  if (
    manifest?.schemaVersion !== 1 ||
    !match ||
    manifest.version !== match[1] ||
    manifest.channel !== (match[2] ? 'prerelease' : 'stable') ||
    !Number.isFinite(Date.parse(manifest.publishedAt)) ||
    new Date(manifest.publishedAt).toISOString() !== manifest.publishedAt ||
    !Array.isArray(manifest.artifacts) ||
    manifest.artifacts.length !== desktopArtifactDefinitions.length
  ) {
    fail('Desktop manifest release metadata is invalid')
  }

  for (let index = 0; index < desktopArtifactDefinitions.length; index += 1) {
    const definition = desktopArtifactDefinitions[index]
    const artifact = manifest.artifacts[index]
    const expectedName = expectedDesktopAssetName(manifest.version, definition)
    const compatibleArchitectures = artifact?.compatibleArchitectures
    if (
      artifact?.name !== expectedName ||
      artifact.platform !== definition.platform ||
      artifact.architecture !== definition.architecture ||
      !Array.isArray(compatibleArchitectures) ||
      compatibleArchitectures.length !== definition.compatibleArchitectures.length ||
      compatibleArchitectures.some(
        (architecture, architectureIndex) =>
          architecture !== definition.compatibleArchitectures[architectureIndex],
      ) ||
      artifact.packageType !== definition.packageType ||
      artifact.packageFormat !== definition.packageFormat ||
      !Number.isSafeInteger(artifact.sizeBytes) ||
      artifact.sizeBytes <= 0 ||
      !digestPattern.test(artifact.sha256) ||
      artifact.sources?.github !==
        releaseUrl('https://github.com', githubRepository, manifest.tag, expectedName) ||
      artifact.sources?.gitee !==
        releaseUrl('https://gitee.com', giteeRepository, manifest.tag, expectedName)
    ) {
      fail('Desktop manifest artifact contract is invalid: ' + (artifact?.name || index))
    }
  }
}

export function encodeManifest(manifest) {
  return Buffer.from(JSON.stringify(manifest, null, 2) + '\n')
}

export function signDesktopManifest({ manifestBytes, privateKeyPem, keyId }) {
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(keyId)) fail('invalid Desktop signing key ID')
  const privateKey = createPrivateKey(privateKeyPem)
  if (privateKey.asymmetricKeyType !== 'ed25519') fail('Desktop signing key must be Ed25519')
  const publicKey = createPublicKey(privateKey)
  const publicKeySpki = publicKey.export({ type: 'spki', format: 'der' })
  const signature = sign(null, manifestBytes, privateKey)
  return {
    signatureDocument: {
      schemaVersion: 1,
      algorithm: 'Ed25519',
      keyId,
      manifest: 'desktop-latest.json',
      signature: signature.toString('base64'),
    },
    keyDocument: {
      schemaVersion: 1,
      algorithm: 'Ed25519',
      keyId,
      publicKeySpki: publicKeySpki.toString('base64'),
      publicKeySpkiSha256: createHash('sha256').update(publicKeySpki).digest('hex'),
    },
  }
}

export function verifyDesktopManifestSignature({ manifestBytes, signatureDocument, keyDocument }) {
  if (
    signatureDocument?.schemaVersion !== 1 ||
    signatureDocument?.algorithm !== 'Ed25519' ||
    signatureDocument?.manifest !== 'desktop-latest.json' ||
    signatureDocument?.keyId !== keyDocument?.keyId ||
    keyDocument?.schemaVersion !== 1 ||
    keyDocument?.algorithm !== 'Ed25519'
  ) {
    fail('Desktop signature metadata is invalid')
  }
  if (!base64Pattern.test(signatureDocument.signature || '')) fail('Desktop signature is invalid')
  if (!base64Pattern.test(keyDocument.publicKeySpki || '')) fail('Desktop public key is invalid')
  const publicKeySpki = Buffer.from(keyDocument.publicKeySpki, 'base64')
  const expectedFingerprint = createHash('sha256').update(publicKeySpki).digest('hex')
  if (keyDocument.publicKeySpkiSha256 !== expectedFingerprint) {
    fail('Desktop public key fingerprint is invalid')
  }
  const publicKey = createPublicKey({ key: publicKeySpki, type: 'spki', format: 'der' })
  if (publicKey.asymmetricKeyType !== 'ed25519') fail('Desktop public key must be Ed25519')
  if (!verify(null, manifestBytes, publicKey, Buffer.from(signatureDocument.signature, 'base64'))) {
    fail('Desktop manifest signature verification failed')
  }
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch (error) {
    throw new Error(label + ' is invalid JSON: ' + error.message, { cause: error })
  }
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(path.resolve(filePath)), { recursive: true })
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2) + '\n')
}

function parseCli(args) {
  const command = args[0]
  const options = new Map()
  const assets = []
  for (let index = 1; index < args.length; index += 1) {
    const name = args[index]
    if (name === '--asset') {
      assets.push(args[++index])
    } else if (name?.startsWith('--')) {
      if (options.has(name)) fail('duplicate option: ' + name)
      options.set(name, args[++index])
    } else {
      fail('unexpected argument: ' + name)
    }
  }
  return { command, options, assets }
}

function requireOption(options, name) {
  const value = options.get(name)
  if (!value) fail('missing required option: ' + name)
  return value
}

function verifyArtifacts(manifest, assetPaths) {
  const byName = new Map(assetPaths.map((assetPath) => [path.basename(assetPath), assetPath]))
  if (byName.size !== assetPaths.length) fail('Desktop asset names must be unique')
  if (manifest.artifacts?.length !== byName.size) fail('Desktop manifest asset count mismatch')
  for (const artifact of manifest.artifacts) {
    const assetPath = byName.get(artifact.name)
    if (!assetPath) fail('manifest asset is missing: ' + artifact.name)
    const stat = fs.statSync(assetPath)
    if (stat.size !== artifact.sizeBytes) fail('manifest size mismatch: ' + artifact.name)
    if (sha256FileSync(assetPath) !== artifact.sha256) {
      fail('manifest SHA-256 mismatch: ' + artifact.name)
    }
  }
}

function privateKeyFromEnvironment() {
  const encoded = process.env.DESKTOP_ED25519_PRIVATE_KEY_BASE64
  if (!encoded) fail('DESKTOP_ED25519_PRIVATE_KEY_BASE64 is required')
  return Buffer.from(encoded, 'base64').toString('utf8')
}

function assertExpectedPublicKey(keyDocument) {
  const expected = process.env.DESKTOP_ED25519_PUBLIC_KEY_SPKI_BASE64
  if (!expected) fail('DESKTOP_ED25519_PUBLIC_KEY_SPKI_BASE64 is required')
  if (keyDocument.publicKeySpki !== expected.trim()) {
    fail('Desktop private key does not match the configured public key')
  }
  const expectedKeyId = process.env.DESKTOP_ED25519_KEY_ID
  if (expectedKeyId && keyDocument.keyId !== expectedKeyId) {
    fail('Desktop signing key ID does not match the configured key ID')
  }
}

function buildCommand(options, assetPaths) {
  const releaseTag = requireOption(options, '--tag')
  const manifestPath = requireOption(options, '--manifest')
  const signaturePath = requireOption(options, '--signature')
  const keyPath = requireOption(options, '--public-key')
  const publishedAt = requireOption(options, '--published-at')
  const keyId = process.env.DESKTOP_ED25519_KEY_ID
  if (!keyId) fail('DESKTOP_ED25519_KEY_ID is required')
  const tagMatch = releaseTag.match(tagPattern)
  if (!tagMatch) fail('release tag must match vX.Y.Z or vX.Y.Z-prerelease')

  const classified = classifyDesktopAssets(tagMatch[1], assetPaths).map((artifact) => ({
    ...artifact,
    sha256: sha256FileSync(artifact.path),
  }))
  const manifest = buildDesktopManifest({
    releaseTag,
    publishedAt,
    artifacts: classified,
    githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
    giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
  })
  const manifestBytes = encodeManifest(manifest)
  const signed = signDesktopManifest({
    manifestBytes,
    privateKeyPem: privateKeyFromEnvironment(),
    keyId,
  })
  assertExpectedPublicKey(signed.keyDocument)
  fs.writeFileSync(manifestPath, manifestBytes)
  writeJson(signaturePath, signed.signatureDocument)
  writeJson(keyPath, signed.keyDocument)
  verifyDesktopManifestSignature({
    manifestBytes,
    signatureDocument: signed.signatureDocument,
    keyDocument: signed.keyDocument,
  })
  verifyArtifacts(manifest, assetPaths)
}

function verifyCommand(options, assetPaths) {
  const manifestPath = requireOption(options, '--manifest')
  const signaturePath = requireOption(options, '--signature')
  const keyPath = requireOption(options, '--public-key')
  const manifestBytes = fs.readFileSync(manifestPath)
  const manifest = readJson(manifestPath, 'Desktop manifest')
  const signatureDocument = readJson(signaturePath, 'Desktop signature')
  const keyDocument = readJson(keyPath, 'Desktop public key')
  validateDesktopManifestContract({
    manifest,
    githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
    giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
  })
  verifyDesktopManifestSignature({ manifestBytes, signatureDocument, keyDocument })
  if (process.env.DESKTOP_ED25519_PUBLIC_KEY_SPKI_BASE64) {
    assertExpectedPublicKey(keyDocument)
  }
  verifyArtifacts(manifest, assetPaths)
}

async function main() {
  const { command, options, assets } = parseCli(process.argv.slice(2))
  if (assets.length === 0) fail('at least one --asset is required')
  if (command === 'build') buildCommand(options, assets)
  else if (command === 'verify') verifyCommand(options, assets)
  else fail('usage: desktop-release-assets.mjs <build|verify> [options] --asset <path>...')
  console.log('Desktop release assets ' + command + ' completed')
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('Desktop release assets failed: ' + error.message)
    process.exitCode = 1
  })
}
