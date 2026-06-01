<template>
  <ion-app>
    <MainMenu content-id="main-content" :disabled="route.meta.menu !== true"></MainMenu>
    <div id="main-content" class="ion-page-container">
      <router-view v-slot="{ Component }">
        <transition :name="transitionName" mode="out-in" @after-enter="onAfterEnter">
          <keep-alive :include="keepAliveNames" :exclude="keepAliveExclude">
            <component :is="Component"/>
          </keep-alive>
        </transition>
      </router-view>
    </div>
  </ion-app>
</template>

<script setup lang="ts">
defineOptions({name: 'App'})

import {alertController, IonApp} from '@ionic/vue'
import {computed, nextTick, onBeforeUnmount, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {App} from '@capacitor/app'
import MainMenu from '@/components/menu/MainMenu.vue'
import {useSideMenuState} from '@/composables/useSideMenuState'
import {initSettings} from '@/services/SettingsService'
import {useAuth} from '@/composables/useAuth'
import {initNetworkProbeStore} from '@/composables/networkProbeStore'
import {JmcomicService, showToast} from '@/services/JmcomicService'
import {compareVersion, RELEASES_API, sanitizeReleaseBody} from '@/utils/version'

const {isMenuNavigation, leftMenuOpen, rightMenuOpen} = useSideMenuState()

const route = useRoute()
const router = useRouter()

const routeStack = ref<string[]>([])
const isBack = ref(false)
const keepAliveExclude = ref<string[]>([])
let initialReaderRestorePending = true

const READER_ROUTE_RESTORE_KEY = 'jq_reader_route_restore'
const READER_ROUTE_RESTORE_TTL_MS = 24 * 60 * 60 * 1000

type ReaderRouteSnapshot = {
  fullPath: string
  savedAt: number
}

const isReaderRoutePath = (path: string) =>
  path === '/pdf-reader' || /^\/album\/[^/]+\/read\/[^/]+$/.test(path)

const saveReaderRoute = (fullPath: string) => {
  const snapshot: ReaderRouteSnapshot = {
    fullPath,
    savedAt: Date.now(),
  }
  localStorage.setItem(READER_ROUTE_RESTORE_KEY, JSON.stringify(snapshot))
}

const clearReaderRoute = () => {
  localStorage.removeItem(READER_ROUTE_RESTORE_KEY)
}

const readReaderRoute = () => {
  try {
    const raw = localStorage.getItem(READER_ROUTE_RESTORE_KEY)
    if (!raw) return null
    const snapshot = JSON.parse(raw) as Partial<ReaderRouteSnapshot>
    if (!snapshot.fullPath || !snapshot.savedAt) {
      clearReaderRoute()
      return null
    }
    if (Date.now() - snapshot.savedAt > READER_ROUTE_RESTORE_TTL_MS) {
      clearReaderRoute()
      return null
    }
    return snapshot.fullPath
  } catch {
    clearReaderRoute()
    return null
  }
}

const syncReaderRouteSnapshot = (path: string, fullPath: string) => {
  if (isReaderRoutePath(path)) {
    saveReaderRoute(fullPath)
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
})

router.afterEach((to) => {
  if (initialReaderRestorePending && (to.path === '/' || to.path === '/home')) return
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

const handleMenuDidOpen = () => {
  leftMenuOpen.value = true
  // 关闭右侧菜单
  rightMenuOpen.value = false
}

const handleMenuDidClose = () => {
  leftMenuOpen.value = false
}

let pollTimer: ReturnType<typeof setInterval> | null = null
let activeToast: Awaited<ReturnType<typeof showToast>> | null = null

onMounted(async () => {
  if (route.path === '/' || route.path === '/home') {
    const readerRoute = readReaderRoute()
    if (readerRoute) {
      await router.replace(readerRoute).catch(() => {
        clearReaderRoute()
      })
    }
  }
  initialReaderRestorePending = false
  syncReaderRouteSnapshot(route.path, route.fullPath)

  const {initAuth} = useAuth()

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
        } catch { /* 桥接失败，继续轮询 */ }

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
        const info = await App.getInfo()
        const resp = await fetch(RELEASES_API, {
          headers: { Accept: 'application/vnd.github.v3+json' },
        })
        if (!resp.ok) return
        const data = await resp.json()
        const remote = (data.tag_name || '').replace(/^v/, '')
        if (remote && compareVersion(remote, info.version) > 0) {
          const cleaned = sanitizeReleaseBody(data.body || '')
          const alert1 = await alertController.create({
            header: `发现新版本 v${remote}`,
            message: cleaned || '（无更新说明）',
            cssClass: 'update-alert',
            buttons: [
              { text: '忽略', role: 'cancel' },
              {
                text: '好',
                handler: async () => {
                  const alert2 = await alertController.create({
                    header: '前往下载',
                    message: data.html_url || '',
                    cssClass: 'update-alert',
                    buttons: [
                      { text: '取消', role: 'cancel' },
                      {
                        text: '打开',
                        handler: () => {
                          window.open(data.html_url, '_blank')
                        },
                      },
                    ],
                  })
                  await alert2.present()
                },
              },
            ],
          })
          await alert1.present()
        }
      } catch {
        // 静默忽略网络错误
      }
    })()
  })()

  const menuEl = document.querySelector('ion-menu')
  if (!menuEl) return

  menuEl.addEventListener('ionDidOpen', handleMenuDidOpen)
  menuEl.addEventListener('ionDidClose', handleMenuDidClose)
})

onBeforeUnmount(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  activeToast?.dismiss()
  activeToast = null

  const menuEl = document.querySelector('ion-menu')
  menuEl?.removeEventListener('ionDidOpen', handleMenuDidOpen)
  menuEl?.removeEventListener('ionDidClose', handleMenuDidClose)
})
</script>

<style>
.ion-page-container {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  contain: layout size style;
  z-index: 0;
  overflow: hidden;
}

/* 页面过渡动画 */
.page-slide-forward-enter-active,
.page-slide-forward-leave-active,
.page-slide-back-enter-active,
.page-slide-back-leave-active {
  transition: transform 0.22s cubic-bezier(0.22, 0, 0, 1),
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
