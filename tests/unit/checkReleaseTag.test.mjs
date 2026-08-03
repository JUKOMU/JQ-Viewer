// @vitest-environment node

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const projectRoot = fileURLToPath(new URL('../../', import.meta.url))
const scriptPath = fileURLToPath(new URL('../../scripts/check-release-tag.mjs', import.meta.url))
const projectVersion = JSON.parse(
  fs.readFileSync(new URL('../../package.json', import.meta.url), 'utf8'),
).version

function runTagCheck(tag) {
  const args = [scriptPath]
  if (tag !== undefined) {
    args.push(tag)
  }

  return spawnSync(process.execPath, args, {
    cwd: projectRoot,
    encoding: 'utf8',
  })
}

describe('check-release-tag', () => {
  it('accepts the stable tag matching the project version', () => {
    const result = runTagCheck(`v${projectVersion}`)

    expect(result.status).toBe(0)
    expect(result.stdout).toContain(`Release tag check passed: v${projectVersion}`)
  })

  it('accepts a prerelease tag based on the project version', () => {
    const result = runTagCheck(`v${projectVersion}-beta.1`)

    expect(result.status).toBe(0)
    expect(result.stdout).toContain(`Release tag check passed: v${projectVersion}-beta.1`)
  })

  it('rejects a missing tag', () => {
    const result = runTagCheck(undefined)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('release tag is required')
  })

  it('rejects surrounding whitespace', () => {
    const result = runTagCheck(` v${projectVersion} `)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('surrounding whitespace')
  })

  it('rejects a tag without the v prefix', () => {
    const result = runTagCheck(projectVersion)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('release tag must match')
  })

  it('rejects an incomplete version', () => {
    const result = runTagCheck('v1.1')

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('release tag must match')
  })

  it('rejects a different base version', () => {
    const result = runTagCheck('v99.99.99')

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('does not match project version')
  })

  it('rejects an empty prerelease identifier', () => {
    const result = runTagCheck(`v${projectVersion}-beta.`)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('release tag must match')
  })
})
