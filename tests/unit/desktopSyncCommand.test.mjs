// @vitest-environment node

import { describe, expect, it } from 'vitest'

import { commandInvocation } from '../../scripts/desktop-sync-command.mjs'

describe('desktop sync command invocation', () => {
  it('uses cmd.exe for Windows command shims', () => {
    const command = 'C:\\project\\node_modules\\.bin\\vite.cmd'

    expect(
      commandInvocation(
        command,
        ['build', '--mode', 'desktop'],
        'win32',
        'C:\\Windows\\System32\\cmd.exe',
      ),
    ).toEqual({
      command: 'C:\\Windows\\System32\\cmd.exe',
      args: ['/d', '/s', '/c', command, 'build', '--mode', 'desktop'],
    })
  })

  it('keeps non-Windows commands direct', () => {
    expect(commandInvocation('/project/node_modules/.bin/vite', ['build'], 'linux')).toEqual({
      command: '/project/node_modules/.bin/vite',
      args: ['build'],
    })
  })

  it('does not route non-cmd Windows executables through the shell', () => {
    expect(commandInvocation('node.exe', ['--version'], 'win32', 'cmd.exe')).toEqual({
      command: 'node.exe',
      args: ['--version'],
    })
  })
})
