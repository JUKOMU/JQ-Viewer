import type { CompletedDownloadGroup, DownloadTask } from './JmcomicTypes'

const STORAGE_KEY = 'jq-download-tasks'
const COMPLETED_GROUPS_KEY = 'jq-download-completed-groups'
const MAX_ITEMS_PER_STATUS = 150

const readTasks = (): DownloadTask[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

const readCompletedGroups = (): CompletedDownloadGroup[] => {
  try {
    const raw = localStorage.getItem(COMPLETED_GROUPS_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

const writeTasks = (tasks: DownloadTask[]) => {
  try {
    const counts = new Map<DownloadTask['status'], number>()
    const bounded = tasks.filter((task) => {
      const count = counts.get(task.status) ?? 0
      if (count >= MAX_ITEMS_PER_STATUS) return false
      counts.set(task.status, count + 1)
      return true
    })
    localStorage.setItem(STORAGE_KEY, JSON.stringify(bounded))
  } catch {
    // localStorage 满或不可用，静默丢弃
  }
}

export const OfflineDownloadService = {
  /** 保存当前有限窗口快照，供后端暂不可用时展示 */
  setAll(tasks: DownloadTask[]) {
    writeTasks(tasks)
  },

  /** 获取有限窗口快照 */
  getAll(): DownloadTask[] {
    return readTasks()
  },

  setCompletedGroups(groups: CompletedDownloadGroup[]) {
    try {
      localStorage.setItem(COMPLETED_GROUPS_KEY, JSON.stringify(groups.slice(0, 150)))
    } catch {
      // localStorage 满或不可用，静默丢弃
    }
  },

  getCompletedGroups(): CompletedDownloadGroup[] {
    return readCompletedGroups()
  },

  /** 添加任务（乐观写入，状态 queued） */
  addTask(task: DownloadTask) {
    const tasks = readTasks()
    const idx = tasks.findIndex((t) => t.taskId === task.taskId)
    if (idx >= 0) {
      tasks[idx] = { ...tasks[idx], ...task }
    } else {
      tasks.unshift(task)
    }
    writeTasks(tasks)
  },

  /** 更新下载进度 */
  updateProgress(taskId: string, downloadedPages: number, totalPages: number) {
    const tasks = readTasks()
    const task = tasks.find((t) => t.taskId === taskId)
    if (task) {
      task.downloadedPages = downloadedPages
      task.totalPages = totalPages
      task.status = 'downloading'
      writeTasks(tasks)
    }
  },

  /** 更新最终状态 */
  updateStatus(
    taskId: string,
    status: DownloadTask['status'],
    downloadedPages?: number,
    totalPages?: number,
    error?: string,
  ) {
    const tasks = readTasks()
    const task = tasks.find((t) => t.taskId === taskId)
    if (task) {
      task.status = status
      if (downloadedPages !== undefined) task.downloadedPages = downloadedPages
      if (totalPages !== undefined) task.totalPages = totalPages
      if (error !== undefined) task.error = error
      if (status === 'verifying') task.error = undefined
      if (status === 'completed') task.completedAt = Date.now()
      writeTasks(tasks)
    }
  },

  /** 移除任务 */
  removeTask(taskId: string) {
    const tasks = readTasks().filter((t) => t.taskId !== taskId)
    writeTasks(tasks)
  },
}
