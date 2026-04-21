<template>
  <IonPage>
    <IonContent :fullscreen="true">
      <IonHeader collapse="condense">
        <IonToolbar>
          <IonTitle size="large">Tab 1</IonTitle>
        </IonToolbar>
      </IonHeader>
      <ExploreContainer name="Tab 1 page" />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { IonPage } from '@ionic/vue'
import ExploreContainer from '@/components/ExploreContainer.vue';
</script>
