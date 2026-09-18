// @vitest-environment node

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import {
  buildDesktopRelease,
  buildDesktopReleaseFromPaths,
  classifyDesktopAssets,
  desktopArtifactDefinitions,
  desktopUpdateArtifactDefinitions,
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

function updateArtifacts(version, classified) {
  const expectedNames = new Set(
    desktopUpdateArtifactDefinitions.map((definition) =>
      expectedDesktopAssetName(version, definition),
    ),
  )
  return classified.filter((artifact) => expectedNames.has(artifact.name))
}

describe('Desktop release assets', () => {
  it('requires the complete package matrix and records Windows on ARM compatibility', () => {
    const assetPaths = createArtifacts()
    const classified = classifyDesktopAssets('1.4.6', assetPaths)
    const desktop = buildDesktopReleaseFromPaths({
      releaseTag: 'v1.4.6',
      assetPaths,
    })

    expect(classified).toHaveLength(10)
    expect(classified.filter((artifact) => artifact.platform === 'macos')).toHaveLength(2)
    expect(desktop.artifacts).toHaveLength(8)
    expect(
      desktop.artifacts
        .filter((artifact) => artifact.platform === 'windows')
        .every((artifact) => artifact.compatibleArchitectures.join(',') === 'x64,arm64'),
    ).toBe(true)
    expect(desktop.artifacts.filter((artifact) => artifact.platform === 'linux')).toHaveLength(6)
    expect(desktop.artifacts.filter((artifact) => artifact.platform === 'macos')).toHaveLength(0)
  })

  it('rejects missing or unexpected release assets', () => {
    const assets = createArtifacts()
    expect(() => classifyDesktopAssets('1.4.6', assets.slice(1))).toThrow('missing Desktop')
    expect(() => classifyDesktopAssets('1.4.6', assets.slice(0, -1))).toThrow(
      'JQ-Viewer-1.4.6-macos-arm64.dmg',
    )

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
      artifacts: manifestArtifacts(updateArtifacts('1.4.6', classified)),
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
      artifacts: manifestArtifacts(updateArtifacts('1.4.6', classified)),
    })

    desktop.artifacts[0].sources.github = 'https://example.invalid/package.exe'
    expect(() => validateDesktopReleaseContract({ releaseTag: 'v1.4.6', desktop })).toThrow(
      'artifact contract is invalid',
    )
  })

  it('verifies package bytes against the shared release section', () => {
    const assetPaths = createArtifacts()
    const desktop = buildDesktopReleaseFromPaths({
      releaseTag: 'v1.4.6',
      assetPaths,
    })

    expect(() => verifyDesktopArtifacts(desktop, assetPaths, 'v1.4.6')).not.toThrow()

    const mutated = fs.readFileSync(assetPaths[0])
    mutated[0] ^= 0xff
    fs.writeFileSync(assetPaths[0], mutated)
    expect(() => verifyDesktopArtifacts(desktop, assetPaths, 'v1.4.6')).toThrow(
      'release SHA-256 mismatch',
    )
  })
})
