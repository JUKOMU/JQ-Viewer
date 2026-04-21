<template>
  <IonMenu :content-id="contentId"
  :disabled="disabled">
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonTitle>头像区</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <IonList>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/home" router-direction="forward"
                   :class="{ selected: isActive('/home') }">
            <IonIcon slot="start" class="menu-icon" :icon="homeSharp"
                     :class="{ selected: isActive('/home') }"/>
            <IonLabel>首页</IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/search" router-direction="root"
                   :class="{ selected: isActive('/search') }">
            <IonIcon slot="start" class="menu-icon" :icon="searchSharp"
                     :class="{ selected: isActive('/search') }"/>
            <IonLabel>搜索</IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/favorite" router-direction="root"
                   :class="{ selected: isActive('/favorite') }">
            <IonIcon slot="start" class="menu-icon" :icon="heart"
                     :class="{ selected: isActive('/favorite') }"/>
            <IonLabel>收藏夹</IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/download" router-direction="root"
                   :class="{ selected: isActive('/download') }">
            <IonIcon slot="start" class="menu-icon" :icon="downloadSharp"
                     :class="{ selected: isActive('/download') }"/>
            <IonLabel>下载</IonLabel>
          </IonItem>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonItem button expand="block" router-link="/setting" router-direction="forward"
                   :class="{ selected: isActive('/setting') }">
            <IonIcon slot="start" class="menu-icon" :icon="settingsSharp"
                     :class="{ selected: isActive('/setting') }"/>
            <IonLabel>设置</IonLabel>
          </IonItem>
        </IonMenuToggle>
      </IonList>
    </IonContent>
  </IonMenu>
</template>

<script setup lang="ts">
import {IonIcon} from "@ionic/vue"
import {downloadSharp, heart, homeSharp, searchSharp, settingsSharp} from 'ionicons/icons';
import {useRoute} from "vue-router"

defineProps({
  contentId: String,
  disabled: Boolean,
});

const route = useRoute();

function isActive(path: string) {
  return getTopPath(route.path) === getTopPath(path);
}

function getTopPath(path: string) {
  if (!path || path === "/") return "/";

  const first = path.split("/").filter(Boolean)[0];
  return first ? first : "/";
}
</script>

<style scoped>
.selected {
  --background: rgb(250,156,105);
  color: rgb(255,255,255);
}

.menu-icon {
  font-size: 18px;
  margin-right: 12px;
  flex-shrink: 0;
}

</style>

