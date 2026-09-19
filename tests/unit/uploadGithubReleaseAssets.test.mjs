// @vitest-environment node

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'

const temporaryDirectories = []

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      fs.promises.rm(directory, { recursive: true, force: true }),
    ),
  )
})

describe('GitHub release asset upload', () => {
  it('terminates a stalled upload and retries it', async () => {
    const temporaryDirectory = await fs.promises.mkdtemp(
      path.join(os.tmpdir(), 'jq-github-release-upload-'),
    )
    temporaryDirectories.push(temporaryDirectory)

    const binDirectory = path.join(temporaryDirectory, 'bin')
    const assetPath = path.join(temporaryDirectory, 'asset.bin')
    const attemptPath = path.join(temporaryDirectory, 'attempts')
    const deleteMarkerPath = path.join(temporaryDirectory, 'deleted-asset')
    await fs.promises.mkdir(binDirectory)
    await fs.promises.writeFile(assetPath, 'release asset')

    const ghPath = path.join(binDirectory, 'gh')
    await fs.promises.writeFile(
      ghPath,
      `#!/usr/bin/env bash
set -euo pipefail

if [ "$1" = "api" ]; then
  if [ "$2" = "--method" ] && [ "$3" = "DELETE" ]; then
    echo "$4" > "$DELETE_MARKER_PATH"
    exit 0
  fi
  if [[ "$2" == *"/releases/tags/"* ]]; then
    echo 1
  elif [ -f "$UPLOAD_ATTEMPT_PATH" ] && [ "$(cat "$UPLOAD_ATTEMPT_PATH")" -eq 1 ]; then
    echo '[{"id":99,"name":"asset.bin","state":"starter","digest":null}]'
  else
    echo '[]'
  fi
  exit 0
fi

if [ "$1" = "release" ] && [ "$2" = "upload" ]; then
  attempt=0
  if [ -f "$UPLOAD_ATTEMPT_PATH" ]; then
    attempt="$(cat "$UPLOAD_ATTEMPT_PATH")"
  fi
  attempt=$((attempt + 1))
  echo "$attempt" > "$UPLOAD_ATTEMPT_PATH"
  if [ "$attempt" -eq 1 ]; then
    /bin/sleep 2
  fi
  exit 0
fi

exit 1
`,
      { mode: 0o755 },
    )

    const sleepPath = path.join(binDirectory, 'sleep')
    await fs.promises.writeFile(sleepPath, '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 })

    const scriptPath = fileURLToPath(
      new URL('../../scripts/upload-github-release-assets.sh', import.meta.url),
    )
    const result = spawnSync('bash', [scriptPath, 'v1.0.0-test.1', assetPath], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${binDirectory}:${process.env.PATH}`,
        GITHUB_REPOSITORY: 'JUKOMU/JQ-Viewer',
        GITHUB_RELEASE_UPLOAD_TIMEOUT_SECONDS: '1',
        UPLOAD_ATTEMPT_PATH: attemptPath,
        DELETE_MARKER_PATH: deleteMarkerPath,
      },
    })

    expect(result.status, result.stderr).toBe(0)
    expect(result.stdout).toContain('GitHub Release asset upload timed out')
    expect(result.stdout).toContain('attempt 1/3')
    expect(result.stdout).toContain('Deleting incomplete GitHub Release asset: asset.bin (starter)')
    expect(await fs.promises.readFile(attemptPath, 'utf8')).toBe('2\n')
    expect(await fs.promises.readFile(deleteMarkerPath, 'utf8')).toContain(
      '/releases/assets/99',
    )
  })
})
