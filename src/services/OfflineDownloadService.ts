import type { DownloadTask } from './JmcomicTypes'

const STORAGE_KEY = 'jq-download-tasks'

const readTasks = (): DownloadTask[] => {
    try {
        const raw = localStorage.getItem(STORAGE_KEY)
        return raw ? JSON.parse(raw) : []
    } catch {
        return []
    }
}

const writeTasks = (tasks: DownloadTask[]) => {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks))
    } catch {
        // localStorage 满或不可用，静默丢弃
    }
}

export const OfflineDownloadService = {
    /** 全量覆盖（onMounted 时从 Android 同步） */
    setAll(tasks: DownloadTask[]) {
        writeTasks(tasks)
    },

    /** 获取全部任务 */
    getAll(): DownloadTask[] {
        return readTasks()
    },

    /** 添加任务（乐观写入，状态 queued） */
    addTask(task: DownloadTask) {
        const tasks = readTasks()
        const idx = tasks.findIndex(t => t.taskId === task.taskId)
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
        const task = tasks.find(t => t.taskId === taskId)
        if (task) {
            task.downloadedPages = downloadedPages
            task.totalPages = totalPages
            task.status = 'downloading'
            writeTasks(tasks)
        }
    },

    /** 更新最终状态 */
    updateStatus(taskId: string, status: DownloadTask['status'],
                 downloadedPages?: number, totalPages?: number, error?: string) {
        const tasks = readTasks()
        const task = tasks.find(t => t.taskId === taskId)
        if (task) {
            task.status = status
            if (downloadedPages !== undefined) task.downloadedPages = downloadedPages
            if (totalPages !== undefined) task.totalPages = totalPages
            if (error !== undefined) task.error = error
            if (status === 'completed') task.completedAt = Date.now()
            writeTasks(tasks)
        }
    },

    /** 移除任务 */
    removeTask(taskId: string) {
        const tasks = readTasks().filter(t => t.taskId !== taskId)
        writeTasks(tasks)
    },
}
