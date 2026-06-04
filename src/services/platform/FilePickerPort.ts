export interface FolderPickResult {
  path: string
  treeUri?: string
  cancelled: boolean
}

export interface StoragePermissionResult {
  granted: boolean
  permissionType: string
  apiLevel: number
}

export interface FilePickerPort {
  pickFolder(): Promise<FolderPickResult>
  requestManageStorage(): Promise<StoragePermissionResult>
  getExternalStoragePath(): Promise<{ path: string }>
  checkFilesExist(paths: string[]): Promise<{ existing: string[] }>
}
