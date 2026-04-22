<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonMenuButton :auto-hide="false"/>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <IonTitle>设置</IonTitle>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {IonButtons, IonContent, IonHeader, IonMenuButton, IonPage, IonTitle, IonToolbar} from "@ionic/vue";
</script>

<style scoped>
</style>
