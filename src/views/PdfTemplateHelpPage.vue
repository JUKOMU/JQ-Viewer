<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting"/>
        </IonButtons>
        <IonTitle class="toolbar-title">食用方法</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="help-container">
        <!-- 基本变量 -->
        <div class="section-label">基本变量</div>
        <div class="card">
          <table class="var-table">
            <thead>
              <tr>
                <th>变量</th>
                <th>说明</th>
                <th>示例值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in variables" :key="v.name">
                <td class="var-col"><span class="var-tag">{{ v.name }}</span></td>
                <td class="desc-col">{{ v.desc }}</td>
                <td class="sample-col monospace">{{ v.sample }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 标签条件 -->
        <div class="section-label">标签条件</div>
        <div class="card">
          <p class="help-text">
            使用 <span class="var-tag inline">{tag=标签名}</span> 语法，当本子包含指定标签时才渲染内容。
            支持 <b>|</b>（或）和 <b>&</b>（且）逻辑运算符，不能混合使用。
          </p>
          <div class="rule-list">
            <div class="rule-item">
              <span class="var-tag tag-cond inline">{tag=中文}</span>
              <span class="rule-desc">包含"中文"标签则渲染为<span class="tag-result">中文</span>，否则为空</span>
            </div>
            <div class="rule-item">
              <span class="var-tag tag-cond inline">{tag=中文|單行本}</span>
              <span class="rule-desc">任一匹配（"單行本"不存在，仅<span class="tag-result">中文</span>匹配）</span>
            </div>
            <div class="rule-item">
              <span class="var-tag tag-cond inline">{tag=非H&中文}</span>
              <span class="rule-desc">全部匹配才渲染，如<span class="tag-result">非H、中文</span></span>
            </div>
          </div>
          <p class="help-note">注意：不支持 | 和 & 混合（如 {tag=A|B&C}），标签匹配区分大小写。</p>
        </div>

        <!-- 模板示例 -->
        <div class="section-label">模板示例</div>
        <div class="card">
          <div class="example-list">
            <div v-for="ex in templateExamples" :key="ex.template" class="example-item">
              <div class="example-usage">{{ ex.usage }}</div>
              <div class="example-template">
                <span class="tpl-text">{{ ex.template }}</span>
              </div>
              <div class="example-arrow">→</div>
              <div class="example-result">{{ ex.result }}</div>
            </div>
          </div>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PdfTemplateHelpPage' })

import { IonBackButton, IonButtons, IonContent, IonHeader, IonPage, IonTitle, IonToolbar } from '@ionic/vue'
import { PdfExportService, PDF_SAMPLE_DATA } from '@/services/PdfExportService'

const variables = [
  { name: '{id}', desc: '本子ID', sample: '295852' },
  { name: '{title}', desc: '本子标题', sample: '青梅竹馬絕對不會輸的戀愛喜劇～鄰家四姐妹的溫馨日常～' },
  { name: '{author}', desc: '首位作者', sample: '葵季むつみ' },
  { name: '{authors}', desc: '全部作者，用顿号连接', sample: '葵季むつみ、二丸修一、しぐれうい' },
  { name: '{tags}', desc: '全部标签，用顿号连接', sample: '非H、劇情向、蘿莉、純愛、中文' },
  { name: '{chapterId}', desc: '章节ID', sample: '295852' },
  { name: '{chapterName}', desc: '章节序号（单行本则为标题）', sample: '第1话' },
  { name: '{chapterTitle}', desc: '章节原始标题', sample: '' },
  { name: '{pageCount}', desc: '章节页数', sample: '38' },
]

const render = (tpl: string) => PdfExportService.renderTemplate(tpl, PDF_SAMPLE_DATA)

const templateExamples = [
  { usage: '作者分类', template: '【{author}】{title}', result: render('【{author}】{title}') },
  { usage: '完整命名', template: '{author}《{title}》_{chapterName}', result: render('{author}《{title}》_{chapterName}') },
  { usage: '条件前缀', template: '[{tag=中文}]【{author}】{title}', result: render('[{tag=中文}]【{author}】{title}') },
  { usage: '条件属性', template: '{title} [{tag=非H|純愛}] {chapterName}', result: render('{title} [{tag=非H|純愛}] {chapterName}') },
  { usage: '标签后缀', template: '{author}《{title}》({tags})', result: render('{author}《{title}》({tags})') },
  { usage: '不匹配对比', template: '[{tag=單行本}] {title}', result: render('[{tag=單行本}] {title}') },
]
</script>

<style scoped>
.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.help-container {
  padding: 8px 16px 32px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 20px 0 8px 6px;
}

.card {
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  padding: 16px;
}

/* 变量表格 */
.var-table {
  width: 100%;
  border-collapse: collapse;
}

.var-table th {
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  padding-bottom: 10px;
  border-bottom: 1px solid #f5ebe4;
}

.var-table td {
  padding: 10px 4px;
  font-size: 14px;
  color: #4c2a18;
  border-bottom: 1px solid #f5ebe4;
}

.var-table tr:last-child td {
  border-bottom: none;
}

.var-col {
  width: 140px;
}

.desc-col {
  color: #8c6b5a;
  font-size: 13px;
}

.sample-col {
  color: #b89a84;
  font-size: 12px;
  word-break: break-all;
}

.monospace {
  font-family: monospace;
}

/* 变量标签 */
.var-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 11px;
  font-family: monospace;
}

.var-tag.inline {
  vertical-align: middle;
}

.var-tag.tag-cond {
  background: #fdf0f0;
  color: #b06060;
  font-style: italic;
}

/* 帮助文本 */
.help-text {
  font-size: 14px;
  color: #4c2a18;
  line-height: 1.7;
  margin: 0 0 12px;
}

.help-note {
  font-size: 12px;
  color: #b89a84;
  margin: 12px 0 0;
  line-height: 1.6;
}

.rule-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: #4c2a18;
  line-height: 1.6;
}

.rule-desc {
  padding-top: 1px;
}

.tag-result {
  background: #e8f5e9;
  color: #2e7d32;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}

.empty-result {
  color: #c4a494;
  font-style: italic;
}

/* 模板示例 */
.example-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.example-item {
  border-bottom: 1px solid #f5ebe4;
  padding-bottom: 14px;
}

.example-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.example-usage {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  margin-bottom: 6px;
}

.example-template {
  margin-bottom: 4px;
}

.tpl-text {
  font-family: monospace;
  font-size: 13px;
  color: #b06060;
  background: #fdf0f0;
  padding: 2px 8px;
  border-radius: 4px;
  word-break: break-all;
}

.example-arrow {
  font-size: 12px;
  color: #c4a494;
  padding: 2px 0;
}

.example-result {
  font-family: monospace;
  font-size: 12px;
  color: #2e7d32;
  background: #e8f5e9;
  padding: 4px 10px;
  border-radius: 6px;
  word-break: break-all;
  line-height: 1.5;
}
</style>
