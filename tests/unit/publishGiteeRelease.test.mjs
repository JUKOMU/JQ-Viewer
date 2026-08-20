// @vitest-environment node

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { execFileSyncMock } = vi.hoisted(() => ({
  execFileSyncMock: vi.fn(),
}))

vi.mock('node:child_process', () => ({
  execFileSync: execFileSyncMock,
}))

import { replaceAsset } from '../../scripts/publish-gitee-release.mjs'

let tempDirectory

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(async () => {
  execFileSyncMock.mockReset()
  tempDirectory = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'jq-gitee-release-'))
})

afterEach(async () => {
  vi.unstubAllGlobals()
  await fs.promises.rm(tempDirectory, { recursive: true, force: true })
})

describe('Gitee release asset replacement', () => {
  it('uploads asset bytes through curl with bounded timeouts', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    const content = Buffer.from('{"version":"1.4.0"}')
    await fs.promises.writeFile(filePath, content)
    execFileSyncMock.mockReturnValue(
      JSON.stringify({
        id: 11,
        name: 'latest.json',
        size: content.length,
        browser_download_url: 'https://download.example/latest.json',
      }),
    )
    const fetchMock = vi.fn(async () => jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(replaceAsset('token', 1, filePath)).resolves.toMatchObject({
      name: 'latest.json',
      size: content.length,
    })

    expect(execFileSyncMock).toHaveBeenCalledOnce()
    const [command, args, options] = execFileSyncMock.mock.calls[0]
    expect(command).toBe('curl')
    expect(args).toEqual(
      expect.arrayContaining([
        '--connect-timeout',
        '30',
        '--max-time',
        '1800',
        '--speed-limit',
        '1024',
        '--speed-time',
        '180',
        '--progress-bar',
        'file=@-;filename=latest.json',
      ]),
    )
    expect(options.input).toEqual(content)
    expect(options.stdio).toEqual(['pipe', 'pipe', 'inherit'])
  })

  it('keeps an existing byte-identical asset without deleting it', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    const content = Buffer.from('{"version":"1.4.0"}')
    await fs.promises.writeFile(filePath, content)
    const fetchMock = vi.fn(async (input) => {
      const url = String(input)
      if (url === 'https://download.example/latest.json') {
        return new Response(content)
      }
      return jsonResponse([
        {
          id: 10,
          name: 'latest.json',
          browser_download_url: 'https://download.example/latest.json',
        },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)

    const asset = await replaceAsset('token', 1, filePath)

    expect(asset).toMatchObject({ name: 'latest.json', size: content.length })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(execFileSyncMock).not.toHaveBeenCalled()
    expect(
      fetchMock.mock.calls.every(([, options]) => !options || options.method !== 'DELETE'),
    ).toBe(true)
  })

  it('restores the previous asset when replacement upload fails', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    const previous = Buffer.from('{"version":"1.3.0"}')
    const replacement = Buffer.from('{"version":"1.4.0"}')
    await fs.promises.writeFile(filePath, replacement)
    execFileSyncMock
      .mockImplementationOnce(() => {
        throw Object.assign(new Error('curl failed'), {
          status: 22,
          stdout: Buffer.from('{"message":"upload failed"}'),
        })
      })
      .mockReturnValueOnce(
        JSON.stringify({
          id: 11,
          name: 'latest.json',
          size: previous.length,
          browser_download_url: 'https://download.example/restored-latest.json',
        }),
      )
    const fetchMock = vi.fn(async (input, options = {}) => {
      const url = String(input)
      if (url === 'https://download.example/latest.json') {
        return new Response(previous)
      }
      if (options.method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      return jsonResponse([
        {
          id: 10,
          name: 'latest.json',
          browser_download_url: 'https://download.example/latest.json',
        },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(replaceAsset('token', 1, filePath)).rejects.toThrow('upload failed')

    expect(execFileSyncMock).toHaveBeenCalledTimes(2)
    expect(execFileSyncMock.mock.calls[0][2].input).toEqual(replacement)
    expect(execFileSyncMock.mock.calls[1][2].input).toEqual(previous)
  })

  it('reports curl failures without exposing the token', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    await fs.promises.writeFile(filePath, Buffer.from('{"version":"1.4.1"}'))
    execFileSyncMock.mockImplementation(() => {
      throw Object.assign(new Error('curl failed'), {
        status: 28,
        stdout: Buffer.from(''),
      })
    })
    const fetchMock = vi.fn(async () => jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    const error = await replaceAsset('dummy-secret-token', 1, filePath).catch((reason) => reason)

    expect(error).toBeInstanceOf(Error)
    expect(error.message).toBe('Gitee upload release asset latest.json failed: curl exit 28')
    expect(error.message).not.toContain('dummy-secret-token')
  })
})
