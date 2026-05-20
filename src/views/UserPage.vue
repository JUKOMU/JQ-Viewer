<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle>用户</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <!-- 已登录 -->
      <div v-if="isLoggedIn && userInfo" class="user-container">
        <div class="profile-card">
          <img :src="userInfo.avatarUrl" class="avatar" alt="头像" />
          <div class="username">{{ userInfo.username }}</div>
          <div class="level">Lv.{{ userInfo.level }} {{ userInfo.levelName }}</div>
          <div class="exp-bar">
            <div class="exp-fill" :style="{ width: Math.min(userInfo.expPercent, 100) + '%' }" />
          </div>
          <div class="exp-text">{{ Math.round(userInfo.expPercent) }}%</div>
        </div>

        <div class="stats-row">
          <div class="stat-item">
            <div class="stat-value">{{ userInfo.albumFavorites }}</div>
            <div class="stat-label">收藏</div>
          </div>
          <div class="stat-divider" />
          <div class="stat-item">
            <div class="stat-value">{{ userInfo.coin }}</div>
            <div class="stat-label">金币</div>
          </div>
        </div>

        <IonButton
          expand="block"
          fill="outline"
          color="danger"
          class="logout-btn"
          @click="confirmLogout"
        >
          登出
        </IonButton>
      </div>

      <!-- 未登录 -->
      <div v-else class="user-container">
        <div class="profile-card">
          <IonIcon :icon="personCircleOutline" class="avatar-placeholder" />
          <div class="username">未登录</div>
          <div class="level hint">登录后同步在线收藏夹等数据</div>
        </div>
        <IonButton
          expand="block"
          class="login-btn"
          router-link="/login"
        >
          去登录
        </IonButton>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  IonBackButton, IonButton, IonButtons, IonContent, IonHeader,
  IonIcon, IonPage, IonTitle, IonToolbar, alertController,
} from '@ionic/vue'
import { personCircleOutline } from 'ionicons/icons'
import { useAuth } from '@/composables/useAuth'

const router = useRouter()
const { userInfo, isLoggedIn, logout } = useAuth()

async function confirmLogout() {
  const alert = await alertController.create({
    header: '确认登出',
    message: '登出后将切换到离线收藏夹模式。',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '登出',
        role: 'destructive',
        cssClass: 'danger-alert',
        handler: async () => {
          await logout()
          router.replace('/home')
        },
      },
    ],
  })
  await alert.present()
}
</script>

<style scoped>
.user-container {
  padding: 24px 20px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.profile-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 28px 20px 20px;
  box-shadow: 0 2px 14px rgba(115, 67, 38, 0.07);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid #f5e0d2;
}

.avatar-placeholder {
  font-size: 72px;
  color: #c4a494;
}

.username {
  margin-top: 10px;
  font-size: 18px;
  font-weight: 600;
  color: #4c2a18;
}

.level {
  margin-top: 4px;
  font-size: 13px;
  color: #b89a84;
}

.hint {
  margin-top: 8px;
  text-align: center;
}

.exp-bar {
  margin-top: 14px;
  width: 100%;
  height: 6px;
  background: #f0e4db;
  border-radius: 3px;
  overflow: hidden;
}

.exp-fill {
  height: 100%;
  background: linear-gradient(135deg, #f0a060, #e8843c);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.exp-text {
  margin-top: 4px;
  font-size: 11px;
  color: #b89a84;
}

.stats-row {
  margin-top: 14px;
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 14px;
  padding: 14px 0;
  box-shadow: 0 2px 14px rgba(115, 67, 38, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #e8843c;
}

.stat-label {
  font-size: 12px;
  color: #b89a84;
}

.stat-divider {
  width: 1px;
  height: 32px;
  background: #f0e4db;
}

.logout-btn {
  margin-top: 20px;
  max-width: 400px;
  width: 100%;
  --border-color: #d44;
  --color: #d44;
}

.login-btn {
  margin-top: 20px;
  max-width: 400px;
  width: 100%;
  --background: linear-gradient(145deg, #fa9c69, #f07e49);
}
</style>
