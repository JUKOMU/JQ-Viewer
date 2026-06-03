# JQ Viewer

JMComic 第三方 Android 漫画阅读器。它把搜索、收藏、下载、本地阅读、PDF 导入导出和批量解析放在一个移动端界面里，适合想在手机上更顺手地整理和阅读漫画的人。

[![Version](https://img.shields.io/badge/Version-1.1.3-brightgreen.svg)](https://github.com/JUKOMU/JQ-Viewer/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/JUKOMU/JQ-Viewer/releases)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Releases](https://img.shields.io/badge/Download-Releases-blue.svg)](https://github.com/JUKOMU/JQ-Viewer/releases)

> JQ Viewer 是个人项目，与 JMComic 官方无关。项目本身不包含漫画资源，请遵守所在地法律法规和目标站点规则。

## 应用截图

### 首页、搜索、分类

| 首页 | 搜索 | 分类 |
| --- | --- | --- |
| <img width="220" alt="首页" src="https://github.com/user-attachments/assets/b3979725-d2e7-4721-92c1-5181aa160ca9" /> | <img width="220" alt="搜索" src="https://github.com/user-attachments/assets/158b13ed-396d-4add-be2e-d7d415648c44" /> | <img width="220" alt="分类" src="https://github.com/user-attachments/assets/b12c77fe-1231-4485-bad5-5b45a275d308" /> |

### 详情与阅读

| 详情页 | 阅读器 |
| --- | --- |
| <img width="220" alt="详情页" src="https://github.com/user-attachments/assets/9d340dcd-0541-4219-a555-f51f9e69c109" /> | <img width="220" alt="图片阅读器" src="https://github.com/user-attachments/assets/7750db4c-6342-41b8-b537-e03ba8a236ea" /> |

### 收藏与历史

| 收藏夹 | 收藏夹搜索 | 浏览历史 |
| --- | --- | --- |
| <img width="220" alt="收藏夹" src="https://github.com/user-attachments/assets/9890ecad-ad3b-48a7-a2f1-f6497cd3ec5b" /> | <img width="220" alt="收藏夹搜索" src="https://github.com/user-attachments/assets/c669cf81-e5a2-43e5-91ad-fd468fdb9b75" /> | <img width="220" alt="浏览历史" src="https://github.com/user-attachments/assets/986400a7-3081-4f04-bc9e-afc80e7ae1f2" /> |

| 解析历史 | 批量解析 | 设置 |
| --- | --- | --- |
| <img width="220" alt="解析历史" src="https://github.com/user-attachments/assets/da2b69b0-1d09-489c-8f54-c31757926f8a" /> | <img width="220" alt="批量解析" src="https://github.com/user-attachments/assets/a89f8c24-0f9d-4707-87b4-73882f7844e4" /> | <img width="220" alt="设置" src="https://github.com/user-attachments/assets/2730911b-1cf1-4282-9b13-4bca4a57b652" /> |

### 下载与 PDF

| 下载队列 | 下载管理 | 后台进度                                                                                                                 |
| --- | --- |----------------------------------------------------------------------------------------------------------------------|
| <img width="220" alt="下载队列" src="https://github.com/user-attachments/assets/41abc94a-f3dc-45f2-8b3f-e4826bd4dc2c" /> | <img width="220" alt="下载管理" src="https://github.com/user-attachments/assets/6f227a1c-efa5-4788-ae1f-b96c83e0cbae" /> | <img width="220" alt="下载通知" src="https://github.com/user-attachments/assets/083a4bb5-c8a7-43e2-b1ea-80dc7bb6b4b8" /> |

| PDF 导出 | PDF 导入匹配 | 阅读来源                                                                                                                   |
| --- | --- |------------------------------------------------------------------------------------------------------------------------|
| <img width="220" alt="PDF 导出" src="https://github.com/user-attachments/assets/8c1a4178-ee19-4ba5-9e1e-60c47e2a5950" /> | <img width="220" alt="PDF 导出" src="https://github.com/user-attachments/assets/37bb4848-c376-4c0b-ade6-9e004d5d93fd" /> | <img width="220" alt="阅读来源选择" src="https://github.com/user-attachments/assets/9d340dcd-0541-4219-a555-f51f9e69c109" /> |

## 主要功能

### 内容检索

- 关键词、ID、作者、标签、登场人物等搜索方式。
- 分类浏览、排序筛选和时间范围筛选。
- 搜索历史按场景保存，便于重复检索。

### 阅读体验

- 支持横向翻页和纵向滚动两种阅读方式。
- 可调亮度、防息屏、音量键翻页、屏幕方向等阅读设置。
- 已下载图片和已导入 PDF 可以直接从详情页或下载页打开。

### 收藏与记录

- 支持在线收藏夹，也支持不登录时使用离线收藏夹。
- 收藏夹内搜索、移动、复制、批量下载和文本导出。
- 浏览历史、解析历史自动记录，方便回到之前看过或处理过的内容。

### 离线与导出

- 章节下载队列支持暂停、恢复、重试和删除。
- 下载完成后可离线阅读，也可以按需公开到系统相册。
- PDF 导出支持自定义目录、文件名模板、分卷和后台进度通知。
- 本地 PDF 可以导入应用，并尽量匹配到对应作品和章节。

### 批量解析

- 从多行文本里批量提取 ID，再统一搜索、收藏或下载。
- 可选 OCR 识别图片中的文字，用来辅助处理截图里的编号。

## 安装

前往 [Releases](https://github.com/JUKOMU/JQ-Viewer/releases) 下载 APK 安装。应用内也可以在“设置 > 关于 > 检查更新”查看最新版本。

最低支持 Android 7.0。

## 开发构建

项目主体是 Vue 3 + Ionic + Capacitor，Android 原生侧负责下载、缓存、PDF、OCR 等能力；接口调用依赖 [JMComic-Api-Java](https://github.com/JUKOMU/JMComic-Api-Java)。

构建环境需要 Node.js、JDK 21 和 Android SDK：

```bash
npm install
npm run build
npx cap sync android
cd android
./gradlew :app:assembleDebug
```

## 许可证

[MIT License](LICENSE)
