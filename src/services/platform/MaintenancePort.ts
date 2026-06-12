export type DesktopDiagnosticDirectoryKey =
  | 'data'
  | 'cache'
  | 'logs'
  | 'download'
  | 'pdfRoot'
  | 'pdfExport'

export interface DesktopDiagnosticsStatus {
  bindAddress: string
  port: number
  serverVersion: string
  dataDir: string
  cacheDir: string
  logDir: string
  downloadDir: string
  pdfRootDir: string
  pdfExportDir: string
}

export interface MaintenancePort {
  getDiagnosticsStatus(): Promise<DesktopDiagnosticsStatus | null>
  openDirectory(key: DesktopDiagnosticDirectoryKey): Promise<{success: boolean; path?: string}>
}
