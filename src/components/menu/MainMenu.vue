<template>
  <IonMenu :content-id="contentId" :disabled="disabled" type="overlay">
    <IonHeader class="ion-no-border">
      <div class="menu-hero">
        <div class="hero-badge">JQ</div>
        <div class="hero-copy">
          <div class="hero-title">JQ Viewer</div>
          <div class="hero-subtitle">浏览、搜索与管理入口</div>
        </div>
      </div>
    </IonHeader>
    <IonContent class="menu-content">
      <IonList lines="none" class="menu-list">
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/home" router-direction="forward"
                   class="menu-item" :class="{ selected: isActive('/home') }">
            <IonIcon slot="start" class="menu-icon" :icon="homeSharp"/>
            <IonLabel>
              <div class="item-title">首页</div>
              <div class="item-subtitle">关键词搜索入口</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/category" router-direction="root"
                   class="menu-item" :class="{ selected: isActive('/category') }">
            <IonIcon slot="start" class="menu-icon" :icon="searchSharp"/>
            <IonLabel>
              <div class="item-title">分类</div>
              <div class="item-subtitle">分类筛选与检索</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/favorite" router-direction="root"
                   class="menu-item" :class="{ selected: isActive('/favorite') }">
            <IonIcon slot="start" class="menu-icon" :icon="heart"/>
            <IonLabel>
              <div class="item-title">收藏夹</div>
              <div class="item-subtitle">保存喜欢的内容</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/download" router-direction="root"
                   class="menu-item" :class="{ selected: isActive('/download') }">
            <IonIcon slot="start" class="menu-icon" :icon="downloadSharp"/>
            <IonLabel>
              <div class="item-title">下载</div>
              <div class="item-subtitle">离线任务与管理</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/setting" router-direction="forward"
                   class="menu-item" :class="{ selected: isActive('/setting') }">
            <IonIcon slot="start" class="menu-icon" :icon="settingsSharp"/>
            <IonLabel>
              <div class="item-title">设置</div>
              <div class="item-subtitle">偏好与通用配置</div>
            </IonLabel>
          </IonItem>
        </IonMenuToggle>
      </IonList>
      <div class="menu-footer">
        <div class="footer-title">菜单方案</div>
        <div class="footer-copy">暖色卡片式导航，统一顶部按钮样式，与搜索页配色保持一致。</div>
      </div>
    </IonContent>
  </IonMenu>
</template>

<script setup lang="ts">
import {IonContent, IonHeader, IonIcon, IonItem, IonLabel, IonList, IonMenu, IonMenuToggle} from "@ionic/vue"
import {downloadSharp, heart, homeSharp, searchSharp, settingsSharp} from 'ionicons/icons'
import {useRoute} from "vue-router"

defineProps({
  contentId: String,
  disabled: Boolean,
})

const route = useRoute()

function isActive(path: string) {
  return getTopPath(route.path) === getTopPath(path)
}

function getTopPath(path: string) {
  if (!path || path === "/") return "/"

  const first = path.split("/").filter(Boolean)[0]
  return first ? first : "/"
}
</script>

<style scoped>
.menu-hero {
  padding: calc(18px + var(--ion-safe-area-top)) 18px 14px;
  background:
      radial-gradient(circle at top right, rgb(255 218 190 / 0.95), transparent 46%),
      linear-gradient(160deg, #fff5ee 0%, #ffeade 100%);
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 16px;
  background: linear-gradient(145deg, #fa9c69, #f07e49);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.hero-copy {
  margin-top: 14px;
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
  color: rgb(255,255,255);
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
