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
