<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonButton fill="clear" aria-label="返回首页" @click="goHome">返回</IonButton>
        </IonButtons>
        <IonTitle>PicaComic 试验入口</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <main class="pica-page pica-login-page">
        <section class="pica-card login-card" aria-labelledby="pica-login-title">
          <p class="eyebrow">DEBUG ONLY · FAKE CONTRACT</p>
          <h1 id="pica-login-title">登录 PicaComic</h1>
          <p class="page-copy">
            这是仅供验证的只读入口。登录状态只保留在当前进程，关闭应用后需要重新登录。
          </p>
          <form data-testid="picacomic-login-form" @submit.prevent="submit">
            <IonInput
              v-model="usernameOrEmail"
              label="用户名或邮箱"
              label-placement="stacked"
              placeholder="fixture-user"
              autocomplete="username"
              :disabled="loading"
              data-testid="picacomic-username"
            />
            <IonInput
              v-model="password"
              type="password"
              label="密码"
              label-placement="stacked"
              placeholder="fixture-password"
              autocomplete="current-password"
              :disabled="loading"
              data-testid="picacomic-password"
            />
            <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
            <IonButton
              type="submit"
              expand="block"
              :disabled="!canSubmit || loading"
              data-testid="picacomic-login-submit"
            >
              <IonSpinner v-if="loading" slot="start" name="crescent" />
              {{ loading ? '登录中…' : '登录并开始浏览' }}
            </IonButton>
          </form>
          <p class="privacy-note">不会保存原始密码或 token，也不会发起真实 PicaComic 网络请求。</p>
        </section>
      </main>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PicacomicLoginPage' })

import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonInput,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { usePicacomicAuth } from '../auth'
import { picacomicErrorMessage } from '../service'

const router = useRouter()
const auth = usePicacomicAuth()

const usernameOrEmail = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')
const canSubmit = computed(
  () => usernameOrEmail.value.trim().length > 0 && password.value.length > 0 && !loading.value,
)

onMounted(async () => {
  try {
    const state = await auth.refresh()
    if (state.state === 'signed_in') await router.replace({ name: 'PicacomicBrowsePage' })
  } catch {
    // The form remains usable when the bridge is unavailable.
  }
})

async function submit() {
  if (!canSubmit.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    await auth.login(usernameOrEmail.value.trim(), password.value)
    const redirect = auth.consumeRedirect()
    password.value = ''
    await router.replace(redirect ?? { name: 'PicacomicBrowsePage' })
  } catch (error) {
    errorMessage.value = picacomicErrorMessage(error, '登录失败，请重试')
    password.value = ''
  } finally {
    loading.value = false
  }
}

function goHome() {
  void router.replace('/home')
}
</script>

<style scoped>
.pica-page {
  width: min(100%, 720px);
  margin: 0 auto;
  box-sizing: border-box;
  padding: 24px 16px 48px;
}

.pica-login-page {
  min-height: 100%;
  display: grid;
  place-items: center;
}

.pica-card {
  width: min(100%, 520px);
  padding: 24px;
  border: 1px solid rgb(245 210 188 / 0.7);
  border-radius: 18px;
  background: #fffaf6;
  box-shadow: 0 12px 32px rgb(76 42 24 / 0.08);
}

.eyebrow {
  margin: 0 0 8px;
  color: #bd652a;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

h1 {
  margin: 0;
  color: #4c2a18;
  font-size: 25px;
}

.page-copy,
.privacy-note {
  color: #806451;
  font-size: 13px;
  line-height: 1.6;
}

.page-copy {
  margin: 10px 0 22px;
}

form {
  display: grid;
  gap: 14px;
}

.error-message {
  margin: 0;
  color: var(--ion-color-danger);
  font-size: 13px;
}

.privacy-note {
  margin: 18px 0 0;
  font-size: 11px;
}
</style>
