// @vitest-environment node

import { createHash } from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import {
  buildDesktopRelease,
  classifyDesktopAssets,
  desktopArtifactDefinitions,
  expectedDesktopAssetName,
  validateDesktopReleaseContract,
  verifyDesktopArtifacts,
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
    const desktop = buildDesktopRelease({
      releaseTag: 'v1.4.6',
      artifacts: manifestArtifacts(classified),
    })

    expect(desktop.artifacts).toHaveLength(8)
    expect(
      desktop.artifacts
        .filter((artifact) => artifact.platform === 'windows')
        .every((artifact) => artifact.compatibleArchitectures.join(',') === 'x64,arm64'),
    ).toBe(true)
    expect(desktop.artifacts.filter((artifact) => artifact.platform === 'linux')).toHaveLength(6)
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
    const desktop = buildDesktopRelease({
      releaseTag: 'v1.4.6',
      artifacts: manifestArtifacts(classified),
    })

    desktop.artifacts[0].compatibleArchitectures = ['x64']
    expect(() => validateDesktopReleaseContract({ releaseTag: 'v1.4.6', desktop })).toThrow(
      'artifact contract is invalid',
    )
  })

  it('rejects release URLs that do not point to the configured repositories', () => {
    const classified = classifyDesktopAssets('1.4.6', createArtifacts())
    const desktop = buildDesktopRelease({
      releaseTag: 'v1.4.6',
      artifacts: manifestArtifacts(classified),
    })

    desktop.artifacts[0].sources.github = 'https://example.invalid/package.exe'
    expect(() => validateDesktopReleaseContract({ releaseTag: 'v1.4.6', desktop })).toThrow(
      'artifact contract is invalid',
    )
  })

  it('verifies package bytes against the shared release section', () => {
    const assetPaths = createArtifacts()
    const classified = classifyDesktopAssets('1.4.6', assetPaths)
    const desktop = buildDesktopRelease({
      releaseTag: 'v1.4.6',
      artifacts: classified.map((artifact) => ({
        ...artifact,
        sha256: createHash('sha256').update(fs.readFileSync(artifact.path)).digest('hex'),
      })),
    })

    expect(() => verifyDesktopArtifacts(desktop, assetPaths)).not.toThrow()
  })
})
