<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <IonTitle>设置</IonTitle>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {IonContent, IonHeader, IonPage, IonTitle, IonToolbar} from "@ionic/vue";
import MenuToggleButton from "@/components/common/MenuToggleButton.vue";
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

:deep(ion-header),
:deep(ion-toolbar) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}
</style>
