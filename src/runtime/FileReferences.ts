/**
 * 平台持有的文件标识。branded string 类型用于在编译期阻止公共代码
 * 把「文件引用」误当作「文件系统路径」直接拼接或读取。
 * 它只是类型层面的防误用约束，不是保密边界：真正的安全校验由底层平台完成。
 */
export type FileRef = string & { readonly __fileRef: unique symbol }
export type FolderRef = string & { readonly __folderRef: unique symbol }

/** 文件的平台引用与展示信息；displayPath 仅用于展示和用户确认，不作为授权凭据回传。 */
export interface FileDescriptor {
  ref: FileRef
  fileName: string
  displayPath: string
}

/** 文件夹的平台引用与展示信息。 */
export interface FolderDescriptor {
  ref: FolderRef
  displayPath: string
}

/** 导出目标：目录引用加上目录内的相对路径，路径拼接与越界校验由底层平台负责。 */
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
