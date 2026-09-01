# 参与贡献

感谢你为 JQ Viewer 提交 Issue 或 Pull Request。请先搜索现有内容，确认问题或建议没有重复，并移除截图、日志中的账号和其他敏感信息。

## Issue 与 Label

Bug 和 Feature Issue Form 会自动添加对应的 Type，以及 `Status: Triage`。维护者完成分诊后按以下规则调整标签：

| 维度         | 约束                                                                                                                                           |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Type         | 每个 Open Issue 必须且只能有一个：`Type: Bug`、`Type: Feature`、`Type: Documentation` 或 `Type: Maintenance`。                                 |
| Status       | 每个 Open Issue 必须且只能有一个：`Status: Triage`、`Status: Needs Info`、`Status: Needs Decision`、`Status: Ready` 或 `Status: In Progress`。 |
| Priority     | 只有 `Status: Ready` 或 `Status: In Progress` 必须且只能有一个 Priority（`P0 Critical` 至 `P3 Low`）；其他分诊状态可以暂不设置。               |
| Area         | 每个 Open Issue 选择 1～3 个 Area，例如 `History`、`UI/UX`、`Localization`、`PDF`、`Browse & Search`、`Repository`、`Reader`。                 |
| Contribution | `Contribution: Good First Issue` 与 `Contribution: Help Wanted` 按需使用，不替代 Type、Status 或 Priority。                                    |
| Resolution   | 只用于 Closed Issue：`Resolution: Duplicate`、`Resolution: Invalid` 或 `Resolution: Won't Fix`。完成不需要额外的 Resolution 标签。             |
| PR           | `PR: Bug Fix` 只用于 Pull Request；GitHub Labels 在 Issue 与 PR 之间共享。                                                                     |

Type 的唯一性只约束 Open Issues。历史 Closed Issues 和 PR 可以保留重命名后的 Type 标签，不为本规则全量清洗历史 PR。

## 分诊状态流

新 Issue 从 `Status: Triage` 开始：

1. 确认一个 Type，并选择 1～3 个 Area。
2. 缺少复现步骤、上下文或作者答复时使用 `Status: Needs Info`。
3. 信息基本完整但等待产品、范围或实现方案决定时使用 `Status: Needs Decision`。
4. 范围和验收标准明确后使用 `Status: Ready`，并设置一个 Priority。
5. 已有人实际处理或已有实现 PR 时改为 `Status: In Progress`，并保持一个 Priority。
6. 完成后关闭 Issue；如因重复、无效或明确不处理而关闭，再添加对应 Resolution。

## 拆分多目标 Issue

一个 Issue 应有一个可验证的目标。如果内容包含两个或以上可以独立实现、验收、发布或回滚的目标，或者目标需要不同 Type、Area、负责人或版本，应拆成独立 Issues，并在各自正文中链接来源和关联项。

如果暂时无法拆分，保留一个明确的过渡 Issue，列出每个目标和待确认问题；未经维护者确认，不要擅自关闭原 Issue 或改变其承诺范围。

## Milestone 与 Assignee

- Milestone 表示目标版本或发布批次，例如 `v1.5.0`，不为版本创建重复 Label。
- Assignee 表示当前负责推进、沟通和交付的人，不为负责人创建重复 Label。

## Pull Request 与本地检查

PR 标题或正文应说明对应 Issue、行为变化和验证结果；只有确实希望合并后关闭 Issue 时才使用 GitHub 的 closing keyword。提交前至少运行：

```bash
npm ci
npm run lint
npm run typecheck
npm run build
```

更完整的 Label v1 清单、迁移映射和回填规则见 [GitHub Issue #72](https://github.com/JUKOMU/JQ-Viewer/issues/72)。
