<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">用户</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <!-- 已登录 -->
      <div v-if="isLoggedIn && userInfo" class="user-container">
        <!-- 头像信息卡 -->
        <div class="profile-card">
          <img :src="userInfo.avatarUrl" class="avatar" alt="头像" />
          <div class="username">{{ userInfo.username }}</div>
          <div class="level">Lv.{{ userInfo.level }} {{ userInfo.levelName }}</div>
          <div v-if="userInfo.message" class="signature">{{ userInfo.message }}</div>
        </div>

        <!-- 经验卡 -->
        <div class="stats-row">
          <div class="stat-item stat-wide">
            <div class="stat-value">
              {{ fmtNum(userInfo.currentExp) }} / {{ fmtNum(userInfo.nextLevelExp) }}
            </div>
            <div class="stat-label">经验值</div>
            <div class="exp-bar">
              <div class="exp-fill" :style="{ width: Math.min(userInfo.expPercent, 100) + '%' }" />
            </div>
            <div class="exp-text">{{ Math.round(userInfo.expPercent) }}%</div>
          </div>
        </div>

        <!-- 数值卡 -->
        <div class="stats-row">
          <div class="stat-item">
            <div class="stat-value">{{ userInfo.albumFavorites }}<span class="stat-capacity"> / {{ userInfo.maxAlbumFavorites }}</span></div>
            <div class="stat-label">收藏</div>
          </div>
          <div class="stat-divider" />
          <div class="stat-item">
            <div class="stat-value">{{ userInfo.coin }}</div>
            <div class="stat-label">金币</div>
          </div>
        </div>

        <!-- 个人资料卡 -->
        <div v-if="profile" class="stats-row profile-detail">
          <div v-if="profile.nickname" class="profile-row">
            <span class="profile-label">昵称</span>
            <span class="profile-value">{{ profile.nickname }}</span>
          </div>
          <div v-if="profile.birthday" class="profile-row">
            <span class="profile-label">生日</span>
            <span class="profile-value">{{ profile.birthday }}</span>
          </div>
          <div v-if="locationText" class="profile-row">
            <span class="profile-label">所在地</span>
            <span class="profile-value">{{ locationText }}</span>
          </div>
          <div v-if="profile.occupation" class="profile-row">
            <span class="profile-label">职业</span>
            <span class="profile-value">{{ profile.occupation }}</span>
          </div>
          <div v-if="profile.aboutMe" class="profile-row">
            <span class="profile-label">关于我</span>
            <span class="profile-value">{{ profile.aboutMe }}</span>
          </div>
          <div v-if="profile.website" class="profile-row">
            <span class="profile-label">网站</span>
            <span class="profile-value">{{ profile.website }}</span>
          </div>
          <div v-if="!hasProfileContent" class="profile-row muted">暂无个人资料</div>
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
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonBackButton, IonButton, IonButtons, IonContent, IonHeader,
  IonIcon, IonPage, IonTitle, IonToolbar, alertController,
} from '@ionic/vue'
import { personCircleOutline } from 'ionicons/icons'
import { useAuth } from '@/composables/useAuth'
import { JmcomicService } from '@/services/JmcomicService'
import type { UserProfile } from '@/services/JmcomicTypes'

const router = useRouter()
const { userInfo, isLoggedIn, logout } = useAuth()
const profile = ref<UserProfile | null>(null)

const locationText = computed(() => {
  if (!profile.value) return ''
  const { city, country } = profile.value
  if (city && country) return `${country} ${city}`
  return city || country || ''
})

const hasProfileContent = computed(() => {
  if (!profile.value) return false
  const { nickname, birthday, city, country, occupation, aboutMe, website } = profile.value
  return !!(nickname || birthday || city || country || occupation || aboutMe || website)
})

function fmtNum(n: number): string {
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  return String(n)
}

onMounted(async () => {
  if (userInfo.value?.uid) {
    try {
      profile.value = await JmcomicService.getUserProfile(userInfo.value.uid)
    } catch {
      // 加载失败忽略
    }
  }
})

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
.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
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

.signature {
  margin-top: 8px;
  font-size: 13px;
  color: #8b6b5c;
  font-style: italic;
  text-align: center;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.exp-bar {
  margin-top: 6px;
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
  margin-top: 2px;
  font-size: 11px;
  color: #b89a84;
}

.stats-row {
  margin-top: 14px;
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 14px;
  padding: 14px 18px;
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

.stat-wide {
  flex: none;
  width: 100%;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #e8843c;
}

.stat-capacity {
  font-size: 13px;
  font-weight: 400;
  color: #c4a494;
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

/* 个人资料卡 */
.profile-detail {
  flex-direction: column;
  gap: 0;
  padding: 14px 18px;
}

.profile-row {
  width: 100%;
  display: flex;
  align-items: flex-start;
  padding: 8px 0;
  border-bottom: 1px solid #f8f0eb;
}

.profile-row:last-child {
  border-bottom: none;
}

.profile-label {
  flex-shrink: 0;
  width: 56px;
  font-size: 13px;
  color: #b89a84;
}

.profile-value {
  flex: 1;
  font-size: 13px;
  color: #4c2a18;
  word-break: break-all;
}

.muted {
  color: #c4a494;
  justify-content: center;
  border-bottom: none;
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
