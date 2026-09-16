// @vitest-environment node

import { generateKeyPairSync } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import {
  buildDesktopManifest,
  classifyDesktopAssets,
  desktopArtifactDefinitions,
  encodeManifest,
  expectedDesktopAssetName,
  signDesktopManifest,
  validateDesktopManifestContract,
  verifyDesktopManifestSignature,
} from '../../scripts/desktop-release-assets.mjs'

const temporaryDirectories = []

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

function createArtifacts(version = '1.4.6') {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'jq-desktop-release-'))
  temporaryDirectories.push(directory)
  return desktopArtifactDefinitions.map((definition, index) => {
    const assetPath = path.join(directory, expectedDesktopAssetName(version, definition))
    fs.writeFileSync(assetPath, Buffer.from('asset-' + index))
    return assetPath
  })
}

function manifestArtifacts(classified) {
  return classified.map((artifact, index) => ({
    ...artifact,
    sha256: String(index + 1).repeat(64),
  }))
}

describe('Desktop release assets', () => {
  it('requires the complete package matrix and records Windows on ARM compatibility', () => {
    const classified = classifyDesktopAssets('1.4.6', createArtifacts())
    const manifest = buildDesktopManifest({
      releaseTag: 'v1.4.6',
      publishedAt: '2026-09-16T00:00:00Z',
      artifacts: manifestArtifacts(classified),
    })

    expect(manifest.artifacts).toHaveLength(8)
    expect(
      manifest.artifacts
        .filter((artifact) => artifact.platform === 'windows')
        .every((artifact) => artifact.compatibleArchitectures.join(',') === 'x64,arm64'),
    ).toBe(true)
    expect(manifest.artifacts.filter((artifact) => artifact.platform === 'linux')).toHaveLength(6)
  })

  it('rejects missing or unexpected release assets', () => {
    const assets = createArtifacts()
    expect(() => classifyDesktopAssets('1.4.6', assets.slice(1))).toThrow('missing Desktop')

    const unexpected = path.join(path.dirname(assets[0]), 'unexpected.bin')
    fs.writeFileSync(unexpected, 'unexpected')
    expect(() => classifyDesktopAssets('1.4.6', [...assets, unexpected])).toThrow(
      'unexpected Desktop',
    )
  })

  it('rejects a signed-manifest shape that does not match the release contract', () => {
    const classified = classifyDesktopAssets('1.4.6', createArtifacts())
    const manifest = buildDesktopManifest({
      releaseTag: 'v1.4.6',
      publishedAt: '2026-09-16T00:00:00Z',
      artifacts: manifestArtifacts(classified),
    })

    manifest.artifacts[0].compatibleArchitectures = ['x64']
    expect(() => validateDesktopManifestContract({ manifest })).toThrow(
      'artifact contract is invalid',
    )
  })

  it('rejects release URLs that do not point to the configured repositories', () => {
    const classified = classifyDesktopAssets('1.4.6', createArtifacts())
    const manifest = buildDesktopManifest({
      releaseTag: 'v1.4.6',
      publishedAt: '2026-09-16T00:00:00Z',
      artifacts: manifestArtifacts(classified),
    })

    manifest.artifacts[0].sources.github = 'https://example.invalid/package.exe'
    expect(() => validateDesktopManifestContract({ manifest })).toThrow(
      'artifact contract is invalid',
    )
  })

  it('signs exact manifest bytes and rejects tampering', () => {
    const classified = classifyDesktopAssets('1.4.6', createArtifacts())
    const manifestBytes = encodeManifest(
      buildDesktopManifest({
        releaseTag: 'v1.4.6',
        publishedAt: '2026-09-16T00:00:00Z',
        artifacts: manifestArtifacts(classified),
      }),
    )
    const { privateKey } = generateKeyPairSync('ed25519')
    const signed = signDesktopManifest({
      manifestBytes,
      privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
      keyId: 'jq-viewer-test-2026',
    })

    expect(() => verifyDesktopManifestSignature({ manifestBytes, ...signed })).not.toThrow()
    expect(() =>
      verifyDesktopManifestSignature({
        manifestBytes: Buffer.concat([manifestBytes, Buffer.from(' ')]),
        ...signed,
      }),
    ).toThrow('signature verification failed')
  })

  it('rejects a non-Ed25519 signing key', () => {
    const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
    expect(() =>
      signDesktopManifest({
        manifestBytes: Buffer.from('{}\n'),
        privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
        keyId: 'test',
      }),
    ).toThrow('must be Ed25519')
  })
})
