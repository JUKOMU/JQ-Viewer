<template>
  <ion-app>
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
  </ion-app>
</template>

<script setup lang="ts">
defineOptions({ name: 'App' })

import { IonApp } from '@ionic/vue'
import { onBeforeUnmount, onMounted, computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MainMenu from '@/components/menu/MainMenu.vue'
import { useSideMenuState } from '@/composables/useSideMenuState'
import { initSettings } from '@/services/SettingsService'
import { useAuth } from '@/composables/useAuth'
import { initNetworkProbeStore } from '@/composables/networkProbeStore'

const { isMenuNavigation, leftMenuOpen, rightMenuOpen } = useSideMenuState()

const route = useRoute()
const router = useRouter()

const routeStack = ref<string[]>([])
const isBack = ref(false)
const keepAliveExclude = ref<string[]>([])

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

const transitionName = computed(() => (isBack.value ? 'page-slide-back' : 'page-slide-forward'))

const onAfterEnter = (el: Element) => {
  ;(el as HTMLElement).style.transform = ''
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

onMounted(async () => {
  const { initAuth } = useAuth()

  // 加载设置到内存缓存（必须在任何页面渲染前完成）
  await initSettings()

  // 初始化网络探活事件 store（模块级，持续记录启动以来全部事件）
  initNetworkProbeStore()

  // 恢复登录态（在设置加载后，不阻塞页面渲染）
  initAuth()

  const menuEl = document.querySelector('ion-menu')
  if (!menuEl) return

  menuEl.addEventListener('ionDidOpen', handleMenuDidOpen)
  menuEl.addEventListener('ionDidClose', handleMenuDidClose)
})

onBeforeUnmount(() => {
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
