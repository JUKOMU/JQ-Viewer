<template>
  <div class="main-menu" :class="{ interactive: isInteractive }" :aria-hidden="!isInteractive">
    <button
      type="button"
      class="main-menu-backdrop"
      aria-label="关闭侧边栏"
      :style="backdropStyle"
      tabindex="-1"
      @click="closeMenu"
    />

    <aside
      ref="panelRef"
      class="main-menu-panel"
      role="dialog"
      aria-modal="true"
      aria-label="主菜单"
      tabindex="-1"
      :style="panelStyle"
    >
      <IonHeader class="ion-no-border menu-header">
        <button type="button" class="menu-hero" @click="goUser">
          <img
            v-if="isLoggedIn && userInfo"
            :src="userInfo.avatarUrl"
            class="user-avatar"
            alt="头像"
          />
          <IonIcon v-else :icon="personCircleOutline" class="user-avatar-placeholder" />
          <div class="hero-copy">
            <div class="hero-title">
              {{ isLoggedIn && userInfo ? userInfo.username : '未登录' }}
            </div>
            <div class="hero-subtitle">
              {{
                isLoggedIn && userInfo
                  ? 'Lv.' + userInfo.level + ' ' + userInfo.levelName
                  : '点击查看账号信息'
              }}
            </div>
          </div>
        </button>
      </IonHeader>
      <IonContent class="menu-content">
        <IonList lines="none" class="menu-list">
          <IonItem
            button
            expand="block"
            router-link="/home"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/home') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="homeSharp" />
            <IonLabel>
              <div class="item-title">首页</div>
              <div class="item-subtitle">关键词搜索入口</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/category"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/category') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="searchSharp" />
            <IonLabel>
              <div class="item-title">分类</div>
              <div class="item-subtitle">分类筛选与检索</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/favorite"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/favorite') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="heart" />
            <IonLabel>
              <div class="item-title">收藏夹</div>
              <div class="item-subtitle">保存喜欢的内容</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/download"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/download') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="downloadSharp" />
            <IonLabel>
              <div class="item-title">下载</div>
              <div class="item-subtitle">离线任务与管理</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/history"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/history') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="timeOutline" />
            <IonLabel>
              <div class="item-title">历史</div>
              <div class="item-subtitle">浏览与解析记录</div>
            </IonLabel>
          </IonItem>
          <IonItem
            button
            expand="block"
            router-link="/setting"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/setting') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="settingsSharp" />
            <IonLabel>
              <div class="item-title">设置</div>
              <div class="item-subtitle">偏好与通用配置</div>
            </IonLabel>
          </IonItem>
        </IonList>
      </IonContent>
    </aside>
  </div>
</template>

<script setup lang="ts">
import {
  createGesture,
  type Gesture,
  IonContent,
  IonHeader,
  IonIcon,
  IonItem,
  IonLabel,
  IonList,
} from '@ionic/vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  downloadSharp,
  heart,
  homeSharp,
  personCircleOutline,
  searchSharp,
  settingsSharp,
  timeOutline,
} from 'ionicons/icons'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import {
  closeLeftMenu,
  leftMenuGestureEnabled,
  leftMenuOpen,
  isMenuNavigation,
  openLeftMenu,
  rightMenuOpen,
} from '@/composables/useSideMenuState'

defineOptions({ name: 'MainMenu' })

const props = defineProps<{
  contentId: string
  disabled?: boolean
}>()

const route = useRoute()
const router = useRouter()
const { userInfo, isLoggedIn } = useAuth()
const panelRef = ref<HTMLElement | null>(null)
const dragProgress = ref(0)
const isDragging = ref(false)
let gesture: Gesture | undefined
let previouslyFocused: HTMLElement | null = null

const isInteractive = computed(() => leftMenuOpen.value || isDragging.value)
const currentProgress = computed(() =>
  isDragging.value ? dragProgress.value : leftMenuOpen.value ? 1 : 0,
)
const panelStyle = computed(() => ({
  transform: `translate3d(${(currentProgress.value - 1) * 100}%, 0, 0)`,
  transition: isDragging.value ? 'none' : 'transform 0.22s cubic-bezier(0.22, 0.61, 0.36, 1)',
}))
const backdropStyle = computed(() => ({
  opacity: currentProgress.value * 0.32,
  transition: isDragging.value ? 'none' : 'opacity 0.22s ease',
}))

const getPanelWidth = () => panelRef.value?.offsetWidth || (window.innerWidth <= 340 ? 264 : 304)

const isBlockedTarget = (target: EventTarget | null) => {
  if (!(target instanceof Element)) return false
  return Boolean(
    target.closest(
      'input, textarea, select, ion-range, [contenteditable="true"], [data-menu-gesture-block], .sheet-backdrop, .panel-overlay, .picker-backdrop',
    ),
  )
}

const canStartGesture = (detail: { event: UIEvent }) => {
  const menuIsOpen = leftMenuOpen.value
  if (!menuIsOpen && (props.disabled || !leftMenuGestureEnabled.value || rightMenuOpen.value)) {
    return false
  }
  if (
    !menuIsOpen &&
    document.querySelector(
      'ion-modal.show-modal, ion-alert.show-alert, ion-action-sheet.show-action-sheet',
    )
  ) {
    return false
  }
  return !isBlockedTarget(detail.event?.target)
}

const settleGesture = (wasOpen: boolean, velocityX: number) => {
  const shouldOpen =
    Math.abs(velocityX) >= 0.12 ? velocityX > 0 : dragProgress.value >= (wasOpen ? 0.82 : 0.18)

  if (shouldOpen) openLeftMenu()
  else closeLeftMenu()

  void nextTick(() => {
    isDragging.value = false
    dragProgress.value = shouldOpen ? 1 : 0
  })
}

const setupGesture = () => {
  gesture?.destroy()
  gesture = createGesture({
    el: document,
    gestureName: 'main-left-menu-swipe',
    gesturePriority: 30,
    threshold: 6,
    maxAngle: 30,
    direction: 'x',
    passive: true,
    disableScroll: true,
    canStart: canStartGesture,
    onStart: () => {
      isDragging.value = true
      dragProgress.value = leftMenuOpen.value ? 1 : 0
    },
    onMove: (detail) => {
      const startProgress = leftMenuOpen.value ? 1 : 0
      const progress = startProgress + detail.deltaX / getPanelWidth()
      dragProgress.value = Math.max(0, Math.min(1, progress))
    },
    onEnd: (detail) => {
      settleGesture(leftMenuOpen.value, detail.velocityX)
    },
  })
  gesture.enable(true)
}

const closeMenu = () => {
  closeLeftMenu()
}

const updateContentAccessibility = (open: boolean) => {
  const content = document.getElementById(props.contentId)
  if (open) {
    previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    content?.setAttribute('aria-hidden', 'true')
    content?.setAttribute('inert', '')
    void nextTick(() => panelRef.value?.focus({ preventScroll: true }))
    return
  }

  content?.removeAttribute('aria-hidden')
  content?.removeAttribute('inert')
  if (previouslyFocused?.isConnected) previouslyFocused.focus({ preventScroll: true })
  previouslyFocused = null
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && leftMenuOpen.value) {
    event.preventDefault()
    closeMenu()
  }
}

function goUser() {
  isMenuNavigation.value = true
  closeMenu()
  void router.push('/user')
}

function isActive(path: string) {
  return getTopPath(route.path) === getTopPath(path)
}

function getTopPath(path: string) {
  if (!path || path === '/') return '/'

  const first = path.split('/').filter(Boolean)[0]
  return first ? first : '/'
}

function handleMenuClick() {
  isMenuNavigation.value = true
  closeMenu()
}

watch(leftMenuOpen, updateContentAccessibility)
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) closeMenu()
  },
)

onMounted(() => {
  setupGesture()
  document.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  gesture?.destroy()
  document.removeEventListener('keydown', handleKeyDown)
  updateContentAccessibility(false)
})
</script>

<style scoped>
.main-menu {
  position: fixed;
  inset: 0;
  z-index: 1000;
  pointer-events: none;
}

.main-menu.interactive {
  pointer-events: auto;
}

.main-menu-backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: rgb(16 12 10 / 1);
}

.main-menu-panel {
  position: absolute;
  inset: 0 auto 0 0;
  width: 304px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #fffaf4;
  background-image:
    radial-gradient(ellipse at 10% 8%, rgb(255 255 255 / 0.72), transparent 36%),
    radial-gradient(ellipse at 92% 42%, rgb(248 199 175 / 0.2), transparent 43%),
    radial-gradient(ellipse at 18% 92%, rgb(255 255 255 / 0.36), transparent 38%),
    linear-gradient(148deg, #fffaf4 0%, #ffece1 48%, #fffaf4 100%);
  background-blend-mode: soft-light, normal, normal, normal;
  background-size: 100% 100%;
  box-shadow: 4px 0 16px rgb(76 42 24 / 0.18);
  touch-action: pan-y;
}

@media (max-width: 340px) {
  .main-menu-panel {
    width: 264px;
  }
}

.menu-hero {
  width: 100%;
  padding: calc(18px + var(--ion-safe-area-top)) 18px 14px;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
  text-align: left;
  font: inherit;
}

.user-avatar {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid #f5d0b8;
  flex-shrink: 0;
}

.user-avatar-placeholder {
  font-size: 48px;
  color: #e0b694;
  flex-shrink: 0;
}

.hero-copy {
  min-width: 0;
}

.hero-title {
  color: #3a261d;
  font-size: 22px;
  font-weight: 700;
}

.hero-subtitle {
  margin-top: 5px;
  color: #8d634a;
  font-size: 12px;
}

.menu-header {
  --background: transparent;
  background: transparent;
}

.menu-content {
  --background: transparent;
  flex: 1;
  min-height: 0;
}

.menu-list {
  --ion-item-background: transparent;
  padding: 14px 14px 0;
  background: transparent !important;
}

.menu-item {
  --background: rgb(255 255 255 / 0.5);
  --border-radius: 20px;
  --padding-start: 14px;
  --inner-padding-end: 14px;
  --min-height: 64px;
  margin-bottom: 10px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 20px;
  box-shadow: -6px -4px 12px rgb(115 67 38 / 0.1);
}

.menu-item.selected {
  --background: linear-gradient(145deg, #faa271, #f28752);
  border-color: transparent;
  box-shadow: -6px -4px 16px rgb(240 126 73 / 0.35);
  color: rgb(255, 255, 255);
}

.menu-icon {
  font-size: 18px;
  margin-right: 12px;
  flex-shrink: 0;
  color: #c96d3a;
}

.menu-item.selected .menu-icon {
  color: #fff;
}

.item-title {
  font-size: 14px;
  font-weight: 700;
}

.item-subtitle {
  margin-top: 3px;
  color: #9a725b;
  font-size: 11px;
}

.menu-item.selected .item-subtitle {
  color: rgb(255 244 237 / 0.9);
}
</style>
