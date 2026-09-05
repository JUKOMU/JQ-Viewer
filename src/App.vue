<template>
  <ion-app>
    <div class="app-shell">
      <MainMenu content-id="main-content" :disabled="route.meta.menu !== true"></MainMenu>
      <div id="main-content" class="ion-page-container">
        <router-view v-slot="{ Component }">
          <transition :name="transitionName" mode="out-in" @after-enter="onAfterEnter">
            <keep-alive :include="keepAliveNames" :exclude="keepAliveExclude">
              <component :is="Component" />
            </keep-alive>
          </transition>
        </router-view>
      </div>
    </div>
  </ion-app>
</template>

<script setup lang="ts">
defineOptions({ name: 'App' })

import { IonApp } from '@ionic/vue'
import type { ListenerHandle } from '@/runtime/BackendEvents'
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MainMenu from '@/components/menu/MainMenu.vue'
import { useSideMenuState } from '@/composables/useSideMenuState'
import { initSettings } from '@/services/SettingsService'
import { useAuth } from '@/composables/useAuth'
import { initNetworkProbeStore } from '@/composables/networkProbeStore'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { UpdateService } from '@/services/UpdateService'
import { presentUpdatePrompt } from '@/services/UpdatePromptService'
import type { UpdateManifest } from '@/services/JmcomicTypes'

const { isMenuNavigation } = useSideMenuState()

const route = useRoute()
const router = useRouter()

const routeStack = ref<string[]>([])
const isBack = ref(false)
const keepAliveExclude = ref<string[]>([])
let initialReaderRestorePending = true

const READER_ROUTE_RESTORE_KEY = 'jq_reader_route_restore'
const READER_ROUTE_RESTORE_TTL_MS = 2 * 60 * 1000

type ReaderRouteSnapshot = {
  fullPath: string
  fromPath: string
  currentPage: number
  savedAt: number
}

let pendingReaderFromPath = ''

const isReaderRoutePath = (path: string) =>
  path === '/pdf-reader' || /^\/album\/[^/]+\/read\/[^/]+$/.test(path)

const clearReaderRoute = () => {
  localStorage.removeItem(READER_ROUTE_RESTORE_KEY)
}

const readReaderSnapshotRaw = (): Partial<ReaderRouteSnapshot> | null => {
  try {
    const raw = localStorage.getItem(READER_ROUTE_RESTORE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as Partial<ReaderRouteSnapshot>
  } catch {
    return null
  }
}

const readReaderSnapshot = (): ReaderRouteSnapshot | null => {
  const snapshot = readReaderSnapshotRaw()
  if (!snapshot?.fullPath || !snapshot.savedAt) {
    clearReaderRoute()
    return null
  }
  if (Date.now() - snapshot.savedAt > READER_ROUTE_RESTORE_TTL_MS) {
    clearReaderRoute()
    return null
  }
  return snapshot as ReaderRouteSnapshot
}

const saveReaderRoute = (fullPath: string, fromPath?: string, currentPage?: number) => {
  const existing = readReaderSnapshotRaw()
  const snapshot: ReaderRouteSnapshot = {
    fullPath,
    fromPath: fromPath ?? existing?.fromPath ?? '',
    currentPage: currentPage ?? existing?.currentPage ?? 0,
    savedAt: Date.now(),
  }
  localStorage.setItem(READER_ROUTE_RESTORE_KEY, JSON.stringify(snapshot))
}

const refreshReaderSavedAt = () => {
  try {
    const raw = localStorage.getItem(READER_ROUTE_RESTORE_KEY)
    if (!raw) return
    const snapshot = JSON.parse(raw)
    snapshot.savedAt = Date.now()
    localStorage.setItem(READER_ROUTE_RESTORE_KEY, JSON.stringify(snapshot))
  } catch {
    /* 忽略 */
  }
}

document.addEventListener('pagehide', refreshReaderSavedAt)

const HEARTBEAT_MS = 60_000
const heartbeatTimer = setInterval(refreshReaderSavedAt, HEARTBEAT_MS)

const buildReaderPathWithPage = (fullPath: string, currentPage: number): string => {
  if (currentPage <= 0) return fullPath
  const [path, queryString] = fullPath.split('?')
  const params = new URLSearchParams(queryString || '')
  params.set('page', String(currentPage))
  return `${path}?${params.toString()}`
}

const updateReaderCurrentPage = (page: number) => {
  try {
    const raw = localStorage.getItem(READER_ROUTE_RESTORE_KEY)
    if (!raw) return
    const snapshot = JSON.parse(raw) as ReaderRouteSnapshot
    if (!snapshot.fullPath) return
    snapshot.currentPage = page
    localStorage.setItem(READER_ROUTE_RESTORE_KEY, JSON.stringify(snapshot))
  } catch {
    /* 忽略解析错误 */
  }
}

provide('updateReaderCurrentPage', updateReaderCurrentPage)

const syncReaderRouteSnapshot = (path: string, fullPath: string) => {
  if (isReaderRoutePath(path)) {
    saveReaderRoute(fullPath, pendingReaderFromPath || undefined)
    pendingReaderFromPath = ''
    return
  }
  clearReaderRoute()
}

router.beforeEach((to, from) => {
  const fromMenu = isMenuNavigation.value
  isMenuNavigation.value = false

  const idx = routeStack.value.lastIndexOf(to.fullPath)
  if (idx >= 0 && !fromMenu) {
    isBack.value = true
    routeStack.value = routeStack.value.slice(0, idx)
  } else {
    isBack.value = false
    if (from.fullPath) routeStack.value.push(from.fullPath)

    // 前进导航：从 keepAlive 缓存排除，强制组件重建还原初始状态
    const name = to.name
    if (name && typeof name === 'string') {
      keepAliveExclude.value.push(name)
      nextTick(() => {
        keepAliveExclude.value = keepAliveExclude.value.filter((n) => n !== name)
      })
    }
  }

  // 记录进入阅读器前的来源页面（仅当从非阅读器页面进入时，避免章节间切换覆盖）
  if (isReaderRoutePath(to.path) && from.fullPath && !isReaderRoutePath(from.path)) {
    pendingReaderFromPath = from.fullPath
  } else if (!isReaderRoutePath(to.path)) {
    pendingReaderFromPath = ''
  }
})

router.afterEach((to) => {
  if (initialReaderRestorePending) return
  syncReaderRouteSnapshot(to.path, to.fullPath)
})

const transitionName = computed(() => (isBack.value ? 'page-slide-back' : 'page-slide-forward'))

const onAfterEnter = (el: Element) => {
  const ht = el as HTMLElement
  ht.style.removeProperty('transform')
  ht.style.removeProperty('opacity')
}

const keepAliveNames = computed(() =>
  router
    .getRoutes()
    .filter((r) => r.meta?.keepAlive)
    .map((r) => String(r.name ?? ''))
    .filter(Boolean),
)

let pollTimer: ReturnType<typeof setInterval> | null = null
let activeToast: Awaited<ReturnType<typeof showToast>> | null = null
let launchRouteHandle: ListenerHandle | null = null

const isSafeLaunchRoute = (value?: string): value is string =>
  !!value && value.startsWith('/') && !value.startsWith('//')

const navigateToLaunchRoute = async (target?: string, replace = false) => {
  if (!isSafeLaunchRoute(target)) return false
  if (router.currentRoute.value.fullPath === target) return true
  try {
    if (replace) {
      await router.replace(target)
    } else {
      await router.push(target)
    }
    return true
  } catch {
    return false
  }
}

async function showStartupUpdatePrompt(update: UpdateManifest) {
  const confirmed = await presentUpdatePrompt(update, {
    cancelText: '稍后',
    confirmText: '查看更新',
  })
  if (confirmed) {
    await router.push('/about')
  }
}

onMounted(async () => {
  let launchRouteHandled = false
  try {
    const launch = await JmcomicService.consumeLaunchRoute()
    launchRouteHandled = await navigateToLaunchRoute(launch.route, true)
  } catch {
    /* Web 调试时忽略 */
  }

  try {
    launchRouteHandle = await JmcomicService.addLaunchRouteListener((data) => {
      void (async () => {
        if (await navigateToLaunchRoute(data.route)) {
          try {
            await JmcomicService.consumeLaunchRoute()
          } catch {
            /* 忽略 */
          }
        }
      })()
    })
  } catch {
    /* Web 调试时忽略 */
  }

  if (!launchRouteHandled && (route.path === '/' || route.path === '/home')) {
    const snapshot = readReaderSnapshot()
    if (snapshot) {
      const targetPath = buildReaderPathWithPage(snapshot.fullPath, snapshot.currentPage)
      try {
        if (snapshot.fromPath) {
          await router.replace(snapshot.fromPath)
          await router.push(targetPath)
        } else {
          await router.replace(targetPath)
        }
      } catch {
        clearReaderRoute()
        await router.replace('/home')
      }
    }
  }
  initialReaderRestorePending = false
  syncReaderRouteSnapshot(route.path, route.fullPath)

  const { initAuth } = useAuth()

  // 加载设置到内存缓存（必须在任何页面渲染前完成）
  await initSettings()

  // 初始化网络探活事件 store（模块级，持续记录启动以来全部事件）
  initNetworkProbeStore()

  // 启动初始化 toast 流程（不阻塞 onMounted 后续逻辑）
  void (async () => {
    // ---- Phase 1: 客户端初始化轮询 ----
    const t0 = Date.now()
    let warmupToast = await showToast('客户端初始化', 'medium', 0)
    activeToast = warmupToast

    await new Promise<void>((resolve) => {
      pollTimer = setInterval(async () => {
        let complete = false
        try {
          const status = await JmcomicService.getInitStatus()
          complete = status.complete
        } catch {
          /* 桥接失败，继续轮询 */
        }

        if (complete) {
          clearInterval(pollTimer!)
          pollTimer = null
          await warmupToast.dismiss()
          activeToast = null
          showToast('初始化完成', 'success')
          resolve()
        } else {
          const n = Math.floor((Date.now() - t0) / 1000)
          await warmupToast.dismiss()
          warmupToast = await showToast(`客户端初始化(${n}s)`, 'medium', 0)
          activeToast = warmupToast
        }
      }, 1000)
    })

    // ---- Phase 2: 自动登录 ----
    const loginToast = await showToast('正在自动登录...', 'medium', 0)
    activeToast = loginToast

    const loggedIn = await initAuth()
    await loginToast.dismiss()
    activeToast = null
    if (loggedIn) {
      showToast('登录成功', 'success')
    }

    // ---- Phase 3: 自动检查更新 ----
    void (async () => {
      try {
        const result = await UpdateService.check()
        if (result.updateAvailable) {
          await UpdateService.runPrompt(() => showStartupUpdatePrompt(result.manifest))
        }
      } catch {
        // 静默忽略网络错误
      }
    })()
  })()
})

onBeforeUnmount(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  clearInterval(heartbeatTimer)
  activeToast?.dismiss()
  activeToast = null
  launchRouteHandle?.remove()
  launchRouteHandle = null
})
</script>

<style>
.app-shell {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.ion-page-container {
  position: relative;
  flex: 1 1 auto;
  width: auto;
  height: auto;
  min-width: 0;
  min-height: 0;
  contain: layout size style;
  z-index: 0;
  overflow: hidden;
}

/* 页面过渡动画 */
.page-slide-forward-enter-active,
.page-slide-forward-leave-active,
.page-slide-back-enter-active,
.page-slide-back-leave-active {
  transition:
    transform 0.22s cubic-bezier(0.22, 0, 0, 1),
    opacity 0.22s cubic-bezier(0.22, 0, 0, 1);
}

/* 前进：页面从右滑入，旧页向左退出 */
.page-slide-forward-enter-from {
  transform: translateX(36px);
  opacity: 0;
}

.page-slide-forward-leave-to {
  transform: translateX(-24px);
  opacity: 0;
}

/* 后退：页面从左滑入，旧页向右退出 */
.page-slide-back-enter-from {
  transform: translateX(-24px);
  opacity: 0;
}

.page-slide-back-leave-to {
  transform: translateX(36px);
  opacity: 0;
}
</style>
