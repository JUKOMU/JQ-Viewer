export function commandInvocation(
  command,
  args,
  platform = process.platform,
  comSpec = process.env.ComSpec || 'cmd.exe',
) {
  if (platform === 'win32' && command.toLowerCase().endsWith('.cmd')) {
    return {
      command: comSpec,
      args: ['/d', '/s', '/c', command, ...args],
    }
  }

  return { command, args: [...args] }
}

export function parseTargetPlatform(args) {
  const index = args.indexOf('--platform')
  const value = index === -1 ? undefined : args[index + 1]
  if (index === -1 || args.indexOf('--platform', index + 1) !== -1) {
    throw new Error('Desktop sync requires exactly one --platform argument')
  }
  if (value !== 'windows' && value !== 'macos' && value !== 'linux') {
    throw new Error('Desktop platform must be windows, macos, or linux')
  }
  return value
}
