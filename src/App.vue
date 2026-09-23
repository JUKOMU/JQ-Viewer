<template>
  <ion-app>
    <div class="app-shell">
      <MainMenu content-id="main-content" :disabled="mainMenuDisabled"></MainMenu>
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
import { disposeNetworkProbeStore, initNetworkProbeStore } from '@/composables/networkProbeStore'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { UpdateService } from '@/services/UpdateService'
import { presentUpdatePrompt } from '@/services/UpdatePromptService'
import type { ClientStateSnapshot, UpdateManifest } from '@/services/JmcomicTypes'

const { isMenuNavigation, isWideMenu } = useSideMenuState()

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

const mainMenuDisabled = computed(
  () => isReaderRoutePath(route.path) || (!isWideMenu.value && route.meta.menu !== true),
)

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

let activeToast: Awaited<ReturnType<typeof showToast>> | null = null
let clientStateHandle: ListenerHandle | null = null
let clientStatePollTimer: ReturnType<typeof setTimeout> | null = null
let clientStateObservationGeneration = 0
let launchRouteHandle: ListenerHandle | null = null
let launchRouteDrain: Promise<void> = Promise.resolve()
let launchRouteNavigationVersion = 0

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

const queueLaunchRouteDrain = (replaceFirst = false) => {
  const next = launchRouteDrain
    .catch(() => undefined)
    .then(async () => {
      let replace = replaceFirst
      let launch = await JmcomicService.consumeLaunchRoute()
      while (launch.route) {
        if (isSafeLaunchRoute(launch.route)) {
          if (await navigateToLaunchRoute(launch.route, replace)) {
            launchRouteNavigationVersion += 1
          }
          replace = false
        }
        launch = await JmcomicService.consumeLaunchRoute()
      }
    })
  launchRouteDrain = next
  return next
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
  const launchRouteVersion = launchRouteNavigationVersion
  try {
    await queueLaunchRouteDrain(true)
  } catch {
    /* Web 调试时忽略 */
  }

  try {
    launchRouteHandle = await JmcomicService.addLaunchRouteListener(() => {
      void queueLaunchRouteDrain().catch(() => undefined)
    })
    await queueLaunchRouteDrain()
  } catch {
    /* Web 调试时忽略 */
  }

  const launchRouteHandled = launchRouteNavigationVersion > launchRouteVersion
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

  const observationGeneration = ++clientStateObservationGeneration
  let latestClientStateTimestamp = -Infinity
  let authGeneration = 0
  let authState: 'idle' | 'running' | 'complete' = 'idle'
  const handleClientState = async (state: ClientStateSnapshot) => {
    if (state.timestamp < latestClientStateTimestamp) return
    latestClientStateTimestamp = state.timestamp

    if (state.state === 'initializing') {
      authGeneration++
      authState = 'idle'
      if (!activeToast) activeToast = await showToast('客户端初始化', 'medium', 0)
      return
    }

    if (activeToast) {
      await activeToast.dismiss()
      activeToast = null
    }
    if (state.state !== 'ready') {
      authGeneration++
      authState = 'idle'
      return
    }
    if (authState !== 'idle') return

    authState = 'running'
    const currentAuthGeneration = ++authGeneration
    const authTimestamp = state.timestamp
    const canCommitAuth = () =>
      observationGeneration === clientStateObservationGeneration &&
      currentAuthGeneration === authGeneration &&
      latestClientStateTimestamp === authTimestamp
    showToast('初始化完成', 'success')
    const loginToast = await showToast('正在自动登录...', 'medium', 0)
    if (!canCommitAuth()) {
      await loginToast.dismiss()
      return
    }
    activeToast = loginToast
    const result = await initAuth(canCommitAuth)
    await loginToast.dismiss()
    if (activeToast === loginToast) activeToast = null
    if (!canCommitAuth()) return
    authState = result === 'retryable-error' ? 'idle' : 'complete'
    if (result === 'authenticated') showToast('登录成功', 'success')
  }

  let subscribed = false
  try {
    clientStateHandle = await JmcomicService.addClientStateListener((state) => {
      void handleClientState(state)
    })
    subscribed = true
  } catch {
    // 监听注册失败时由下方有限轮询继续观察初始化恢复。
  }
  let initialClientState: ClientStateSnapshot | null = null
  try {
    initialClientState = await JmcomicService.getClientState()
    await handleClientState(initialClientState)
  } catch {
    // 桥接失败不阻塞本地能力和页面挂载。
  }
  if (!subscribed && initialClientState?.state !== 'ready') {
    const delays = [250, 500, 1_000, 2_000, 4_000, 8_000]
    const poll = (index: number) => {
      if (index >= delays.length || observationGeneration !== clientStateObservationGeneration) {
        return
      }
      clientStatePollTimer = setTimeout(async () => {
        clientStatePollTimer = null
        if (observationGeneration !== clientStateObservationGeneration) return
        try {
          const state = await JmcomicService.getClientState()
          await handleClientState(state)
          if (state.state === 'ready') return
        } catch {
          // 下一次退避继续读取。
        }
        poll(index + 1)
      }, delays[index])
    }
    poll(0)
  }

  // 应用更新与 JMComic 客户端初始化相互独立。
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
})

onBeforeUnmount(() => {
  clientStateObservationGeneration++
  if (clientStatePollTimer) clearTimeout(clientStatePollTimer)
  clientStatePollTimer = null
  clearInterval(heartbeatTimer)
  activeToast?.dismiss()
  activeToast = null
  clientStateHandle?.remove()
  clientStateHandle = null
  launchRouteHandle?.remove()
  launchRouteHandle = null
  void disposeNetworkProbeStore()
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
  container-type: inline-size;
  z-index: 0;
  overflow: hidden;
}

@media (min-width: 1264px) {
  .desktop-page-content {
    box-sizing: border-box;
    width: min(100%, calc(100% - clamp(150px, 15cqw, 300px) - 24px));
    max-width: 960px;
    margin-left: clamp(150px, 15cqw, 300px);
    margin-right: auto;
  }
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
