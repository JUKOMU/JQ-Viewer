/**
 * Platform-owned file identifiers. The branded types keep common code from
 * accidentally treating a file reference as a filesystem path.
 */
export type FileRef = string & { readonly __fileRef: unique symbol }
export type FolderRef = string & { readonly __folderRef: unique symbol }

export interface FileDescriptor {
  ref: FileRef
  fileName: string
  displayPath: string
}

export interface FolderDescriptor {
  ref: FolderRef
  displayPath: string
}

export interface ExportTarget {
  folder: FolderRef
  relativePath: string
}

export function asFileRef(value: string): FileRef {
  return value as FileRef
}

export function asFolderRef(value: string): FolderRef {
  return value as FolderRef
}
