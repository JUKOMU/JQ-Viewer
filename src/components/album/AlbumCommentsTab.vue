<!-- 评论 Tab -->
<template>
  <section class="comments-section">
    <template v-if="loading">
      <div class="comments-skeleton">
        <div v-for="n in 4" :key="n" class="sk-card">
          <div class="sk-header">
            <div class="sk-avatar" />
            <div class="sk-line sk-line--short" />
          </div>
          <div class="sk-line" />
          <div class="sk-line sk-line--short" />
        </div>
      </div>
    </template>
    <template v-else-if="comments.length">
      <div v-for="comment in comments" :key="comment.commentId" class="comment-card">
        <div class="comment-header">
          <img
            v-if="comment.photo"
            :src="comment.photo"
            class="comment-avatar"
            alt=""
          />
          <div class="comment-meta">
            <span class="comment-user">{{ comment.username }}</span>
            <span class="comment-time">{{ comment.postDate }}</span>
          </div>
        </div>
        <p class="comment-content">{{ comment.content }}</p>
        <div class="comment-footer">
          <span class="comment-votes">赞 {{ comment.voteUp }}</span>
        </div>

        <!-- 回复 -->
        <div v-if="comment.replys?.length" class="comment-replys">
          <div v-for="reply in comment.replys" :key="reply.commentId" class="reply-card">
            <span class="reply-user">{{ reply.username }}</span>
            <p class="reply-content">{{ reply.content }}</p>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="comments-empty">暂无评论</div>
  </section>
</template>

<script setup lang="ts">
import type { CommentItem } from '@/services/JmcomicTypes'

defineProps<{
  comments: CommentItem[]
  loading: boolean
}>()
</script>

<style scoped>
/* 骨架屏 */
.comments-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sk-card {
  padding: 12px;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 0 4px 12px rgb(76 42 24 / 0.06);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sk-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sk-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(90deg, #f3ded0 25%, #ffece0 50%, #f3ded0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

.sk-line {
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #f3ded0 25%, #ffece0 50%, #f3ded0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}

.sk-line--short {
  width: 40%;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.comments-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  color: #8a6048;
  font-size: 13px;
}

.comment-card {
  padding: 12px;
  margin-bottom: 8px;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 0 4px 12px rgb(76 42 24 / 0.06);
}

.comment-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.comment-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
  background: #f3ded0;
}

.comment-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.comment-user {
  color: #3a261d;
  font-size: 12px;
  font-weight: 700;
}

.comment-time {
  color: #a07858;
  font-size: 10px;
}

.comment-content {
  margin: 0 0 6px;
  color: #3a261d;
  font-size: 12px;
  line-height: 1.5;
}

.comment-footer {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-votes {
  color: #a07858;
  font-size: 10px;
}

.comment-replys {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgb(245 210 188 / 0.4);
}

.reply-card {
  padding: 6px 8px;
  margin-bottom: 4px;
  border-radius: 8px;
  background: #fff5ed;
}

.reply-user {
  color: #9b5a35;
  font-size: 10px;
  font-weight: 700;
}

.reply-content {
  margin: 2px 0 0;
  color: #3a261d;
  font-size: 11px;
  line-height: 1.4;
}
</style>
