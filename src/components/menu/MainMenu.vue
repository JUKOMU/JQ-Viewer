<template>
  <IonMenu
    :content-id="contentId"
    :disabled="disabled"
    :max-edge-start="menuEdgeStart"
    :swipe-gesture="true"
    type="overlay"
  >
    <IonHeader class="ion-no-border">
      <div class="menu-hero" @click="goUser">
        <img
          v-if="isLoggedIn && userInfo"
          :src="userInfo.avatarUrl"
          class="user-avatar"
          alt="头像"
        />
        <IonIcon v-else :icon="personCircleOutline" class="user-avatar-placeholder"/>
        <div class="hero-copy">
          <div class="hero-title">{{ isLoggedIn && userInfo ? userInfo.username : '未登录' }}</div>
          <div class="hero-subtitle">
            {{
              isLoggedIn && userInfo
                ? 'Lv.' + userInfo.level + ' ' + userInfo.levelName
                : '点击查看账号信息'
            }}
          </div>
        </div>
      </div>
    </IonHeader>
    <IonContent class="menu-content">
      <IonList lines="none" class="menu-list">
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/home"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/home') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="homeSharp"/>
            <IonLabel>
              <div class="item-title">首页</div>
              <div class="item-subtitle">关键词搜索入口</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/category"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/category') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="searchSharp"/>
            <IonLabel>
              <div class="item-title">分类</div>
              <div class="item-subtitle">分类筛选与检索</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/favorite"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/favorite') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="heart"/>
            <IonLabel>
              <div class="item-title">收藏夹</div>
              <div class="item-subtitle">保存喜欢的内容</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/download"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/download') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="downloadSharp"/>
            <IonLabel>
              <div class="item-title">下载</div>
              <div class="item-subtitle">离线任务与管理</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/history"
            router-direction="root"
            class="menu-item"
            :class="{ selected: isActive('/history') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="timeOutline"/>
            <IonLabel>
              <div class="item-title">历史</div>
              <div class="item-subtitle">浏览与解析记录</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem
            button
            expand="block"
            router-link="/setting"
            router-direction="forward"
            class="menu-item"
            :class="{ selected: isActive('/setting') }"
            @click="handleMenuClick"
          >
            <IonIcon slot="start" class="menu-icon" :icon="settingsSharp"/>
            <IonLabel>
              <div class="item-title">设置</div>
              <div class="item-subtitle">偏好与通用配置</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
      </IonList>
    </IonContent>
  </IonMenu>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {IonContent, IonHeader, IonIcon, IonItem, IonLabel, IonList, IonMenu, IonMenuToggle,} from '@ionic/vue'
import {
  downloadSharp,
  heart,
  homeSharp,
  personCircleOutline,
  searchSharp,
  settingsSharp,
  timeOutline,
} from 'ionicons/icons'
import {useRoute, useRouter} from 'vue-router'
import {useAuth} from '@/composables/useAuth'
import {isMenuNavigation} from '@/composables/useSideMenuState'

defineOptions({name: 'MainMenu'})

defineProps<{
  contentId: string
  disabled?: boolean
}>()

const route = useRoute()
const router = useRouter()
const {userInfo, isLoggedIn} = useAuth()
const windowWidth = ref(window.innerWidth)
const onResize = () => {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

const menuEdgeStart = computed(() => {
  return windowWidth.value
})

function goUser() {
  router.push('/user')
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
}
</script>

<style scoped>
.menu-hero {
  padding: calc(18px + var(--ion-safe-area-top)) 18px 14px;
  background: radial-gradient(circle at top right, rgb(255 218 190 / 0.95), transparent 46%),
  linear-gradient(160deg, #fff5ee 0%, #ffeade 100%);
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
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

.menu-content {
  --background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
}

.menu-list {
  padding: 14px 14px 0;
  background: transparent;
}

.menu-item {
  --background: #fff;
  --border-radius: 20px;
  --padding-start: 14px;
  --inner-padding-end: 14px;
  --min-height: 64px;
  margin-bottom: 10px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 20px;
  box-shadow: 0 12px 28px rgb(115 67 38 / 0.08);
}

.menu-item.selected {
  --background: linear-gradient(145deg, #fa9c69, #f28752);
  border-color: transparent;
  box-shadow: 0 16px 34px rgb(240 126 73 / 0.26);
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

.menu-footer {
  padding: 16px 18px calc(18px + var(--ion-safe-area-bottom));
}

.footer-title {
  color: #7a5743;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.footer-copy {
  margin-top: 6px;
  color: #9e7d6a;
  font-size: 12px;
  line-height: 1.5;
}
</style>
