// @vitest-environment node

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import {replaceAsset} from '../../scripts/publish-gitee-release.mjs'

let tempDirectory

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {'Content-Type': 'application/json'},
  })
}

beforeEach(async () => {
  tempDirectory = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'jq-gitee-release-'))
})

afterEach(async () => {
  vi.unstubAllGlobals()
  await fs.promises.rm(tempDirectory, {recursive: true, force: true})
})

describe('Gitee release asset replacement', () => {
  it('uses a dedicated dispatcher for asset uploads', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    const content = Buffer.from('{"version":"1.4.0"}')
    await fs.promises.writeFile(filePath, content)
    let uploadOptions
    const fetchMock = vi.fn(async (input, options = {}) => {
      if (options.method === 'POST') {
        uploadOptions = options
        return jsonResponse({
          id: 11,
          name: 'latest.json',
          size: content.length,
          browser_download_url: 'https://download.example/latest.json',
        })
      }
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(replaceAsset('token', 1, filePath)).resolves.toMatchObject({
      name: 'latest.json',
      size: content.length,
    })

    expect(uploadOptions?.dispatcher).toBeDefined()
    expect(typeof uploadOptions?.dispatcher?.dispatch).toBe('function')
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

    expect(asset).toMatchObject({name: 'latest.json', size: content.length})
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([, options]) => !options || options.method !== 'DELETE')).toBe(
      true,
    )
  })

  it('restores the previous asset when replacement upload fails', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    const previous = Buffer.from('{"version":"1.3.0"}')
    const replacement = Buffer.from('{"version":"1.4.0"}')
    await fs.promises.writeFile(filePath, replacement)
    let uploadCount = 0
    let restoredBody
    const fetchMock = vi.fn(async (input, options = {}) => {
      const url = String(input)
      if (url === 'https://download.example/latest.json') {
        return new Response(previous)
      }
      if (options.method === 'DELETE') {
        return new Response(null, {status: 204})
      }
      if (options.method === 'POST') {
        uploadCount += 1
        if (uploadCount === 1) {
          return jsonResponse({message: 'upload failed'}, 500)
        }
        restoredBody = Buffer.from(await options.body.get('file').arrayBuffer())
        return jsonResponse({
          id: 11,
          name: 'latest.json',
          size: previous.length,
          browser_download_url: 'https://download.example/restored-latest.json',
        })
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

    expect(uploadCount).toBe(2)
    expect(restoredBody).toEqual(previous)
  })

  it('reports the upload operation and transport cause', async () => {
    const filePath = path.join(tempDirectory, 'latest.json')
    await fs.promises.writeFile(filePath, Buffer.from('{"version":"1.4.1"}'))
    const transportCause = Object.assign(new Error('Headers Timeout Error'), {
      code: 'UND_ERR_HEADERS_TIMEOUT',
    })
    const fetchError = new TypeError('fetch failed', {cause: transportCause})
    const fetchMock = vi.fn(async (input, options = {}) => {
      if (options.method === 'POST') {
        throw fetchError
      }
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(replaceAsset('token', 1, filePath)).rejects.toThrow(
      'Gitee upload release asset latest.json transport failed: fetch failed ' +
        '(UND_ERR_HEADERS_TIMEOUT: Headers Timeout Error)',
    )
  })
})
