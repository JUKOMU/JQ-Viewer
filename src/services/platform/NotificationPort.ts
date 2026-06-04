export interface NotificationPermissionResult {
  granted: boolean
}

export interface NotificationPort {
  checkPermission(): Promise<NotificationPermissionResult>
  requestPermission(): Promise<NotificationPermissionResult>
  ensureDownloadPermission(): Promise<void>
}
