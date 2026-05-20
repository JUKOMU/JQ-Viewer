<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/user" />
        </IonButtons>
        <IonTitle>登录</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="login-container">
        <div class="login-card">
          <IonInput
            v-model="username"
            label="用户名"
            label-placement="stacked"
            placeholder="请输入用户名"
            :disabled="loading"
            clear-input
            @keyup.enter="focusPassword"
          />
          <IonInput
            ref="passwordRef"
            v-model="password"
            type="password"
            label="密码"
            label-placement="stacked"
            placeholder="请输入密码"
            :disabled="loading"
            @keyup.enter="doLogin"
          />
          <div class="error-msg" v-if="errorMsg">{{ errorMsg }}</div>
          <IonButton
            expand="block"
            :disabled="!canSubmit || loading"
            @click="doLogin"
            class="login-btn"
          >
            <IonSpinner v-if="loading" name="crescent" slot="start" />
            {{ loading ? '登录中...' : '登录' }}
          </IonButton>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonBackButton, IonButton, IonButtons, IonContent, IonHeader,
  IonInput, IonPage, IonSpinner, IonTitle, IonToolbar,
} from '@ionic/vue'
import { useAuth } from '@/composables/useAuth'

const router = useRouter()
const { login } = useAuth()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')
const passwordRef = ref<InstanceType<typeof IonInput> | null>(null)

const canSubmit = computed(() =>
  username.value.trim().length > 0 && password.value.length > 0 && !loading.value
)

function focusPassword() {
  passwordRef.value?.$el.querySelector('input')?.focus()
}

async function doLogin() {
  if (!canSubmit.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    await login(username.value.trim(), password.value)
    router.replace('/user')
  } catch (e: any) {
    errorMsg.value = e?.message ?? '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  padding: 32px 20px 0;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 24px 20px 20px;
  box-shadow: 0 2px 14px rgba(115, 67, 38, 0.07);
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.login-card :deep(ion-input) {
  --background: #fdfaf8;
  --border-color: #e0cfc4;
  --border-radius: 8px;
  --padding-start: 12px;
  --highlight-color-focused: #f0a060;
}

.error-msg {
  color: #d44;
  font-size: 13px;
  padding: 0 4px;
}

.login-btn {
  margin-top: 4px;
  --background: linear-gradient(145deg, #fa9c69, #f07e49);
}
</style>
