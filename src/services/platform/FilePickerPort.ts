export interface FolderPickResult {
  path: string
  treeUri?: string
  cancelled: boolean
}

export type FolderPickPurpose = 'pdfRoot' | 'pdfExport' | 'download'

export interface StoragePermissionResult {
  granted: boolean
  permissionType: string
  apiLevel: number
}

export interface FilePickerPort {
  pickFolder(purpose?: FolderPickPurpose): Promise<FolderPickResult>
  requestManageStorage(): Promise<StoragePermissionResult>
  getExternalStoragePath(): Promise<{ path: string }>
  checkFilesExist(paths: string[]): Promise<{ existing: string[] }>
}
