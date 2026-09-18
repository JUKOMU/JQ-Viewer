import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const tagPattern = /^v([0-9]+\.[0-9]+\.[0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
const digestPattern = /^[0-9a-f]{64}$/i

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
    suffix: 'windows-x64.zip',
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
      suffix: `linux-${architecture}.tar.gz`,
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

export function buildDesktopRelease({
  releaseTag,
  artifacts,
  githubRepository = 'JUKOMU/JQ-Viewer',
  giteeRepository = 'jukomu/jq-viewer',
}) {
  const match = releaseTag.match(tagPattern)
  if (!match) fail('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  if (artifacts.length !== desktopArtifactDefinitions.length) {
    fail('Desktop release requires the complete artifact matrix')
  }

  const desktop = {
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
  validateDesktopReleaseContract({ releaseTag, desktop, githubRepository, giteeRepository })
  return desktop
}

export function buildDesktopReleaseFromPaths({
  releaseTag,
  assetPaths,
  githubRepository = 'JUKOMU/JQ-Viewer',
  giteeRepository = 'jukomu/jq-viewer',
}) {
  const match = releaseTag.match(tagPattern)
  if (!match) fail('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  const artifacts = classifyDesktopAssets(match[1], assetPaths).map((artifact) => ({
    ...artifact,
    sha256: sha256FileSync(artifact.path),
  }))
  return buildDesktopRelease({ releaseTag, artifacts, githubRepository, giteeRepository })
}

export function validateDesktopReleaseContract({
  releaseTag,
  desktop,
  githubRepository = 'JUKOMU/JQ-Viewer',
  giteeRepository = 'jukomu/jq-viewer',
}) {
  const match = releaseTag.match(tagPattern)
  if (!match || !Array.isArray(desktop?.artifacts)) {
    fail('Desktop release metadata is invalid')
  }
  if (desktop.artifacts.length !== desktopArtifactDefinitions.length) {
    fail('Desktop release metadata is invalid')
  }

  for (let index = 0; index < desktopArtifactDefinitions.length; index += 1) {
    const definition = desktopArtifactDefinitions[index]
    const artifact = desktop.artifacts[index]
    const expectedName = expectedDesktopAssetName(match[1], definition)
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
        releaseUrl('https://github.com', githubRepository, releaseTag, expectedName) ||
      artifact.sources?.gitee !==
        releaseUrl('https://gitee.com', giteeRepository, releaseTag, expectedName)
    ) {
      fail('Desktop release artifact contract is invalid: ' + (artifact?.name || index))
    }
  }
}

export function verifyDesktopArtifacts(desktop, assetPaths) {
  const byName = new Map(assetPaths.map((assetPath) => [path.basename(assetPath), assetPath]))
  if (byName.size !== assetPaths.length) fail('Desktop asset names must be unique')
  if (desktop.artifacts?.length !== byName.size) fail('Desktop release asset count mismatch')
  for (const artifact of desktop.artifacts) {
    const assetPath = byName.get(artifact.name)
    if (!assetPath) fail('release asset is missing: ' + artifact.name)
    const stat = fs.statSync(assetPath)
    if (stat.size !== artifact.sizeBytes) fail('release size mismatch: ' + artifact.name)
    if (sha256FileSync(assetPath) !== artifact.sha256) {
      fail('release SHA-256 mismatch: ' + artifact.name)
    }
  }
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

async function main() {
  const { command, options, assets } = parseCli(process.argv.slice(2))
  if (!['check', 'verify'].includes(command) || assets.length === 0) {
    fail(
      'usage: desktop-release-assets.mjs <check|verify> --tag <tag> [--manifest <latest.json>] --asset <path>...',
    )
  }
  const releaseTag = requireOption(options, '--tag')
  const desktop =
    command === 'check'
      ? buildDesktopReleaseFromPaths({
          releaseTag,
          assetPaths: assets,
          githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
          giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
        })
      : JSON.parse(fs.readFileSync(requireOption(options, '--manifest'), 'utf8')).desktop
  validateDesktopReleaseContract({
    releaseTag,
    desktop,
    githubRepository: process.env.GITHUB_REPOSITORY || 'JUKOMU/JQ-Viewer',
    giteeRepository: process.env.GITEE_REPOSITORY || 'jukomu/jq-viewer',
  })
  verifyDesktopArtifacts(desktop, assets)
  console.log('Desktop release contract verified')
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('Desktop release contract failed: ' + error.message)
    process.exitCode = 1
  })
}
