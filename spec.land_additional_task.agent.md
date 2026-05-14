# spec.land_additional_task.agent

## 1. 目标

在整体需求已完整落地后，针对新增补充项或小范围调整任务，执行“增量实现 + 变更归档”流程。

一句话定位：`spec.land_additional_task.agent` 用于承接补充任务，先调用既有落地流程完成编码，再将本次改动以最简练话术追加到固定变更日志 `CHANGED_LOG.md` 中，支持后续微调融合。

## 2. 使用方式

- 命令格式：`/spec.land_additional_task.agent additionaltask`
- 使用示例：`/spec.land_additional_task.agent spec_CAMERA_RATION_FIX_task/TASK.md`

说明：`additionaltask` 参数直接指向一个已存在的补充任务文件（如 `spec_CAMERA_RATION_FIX_task/TASK.md`），而不是目录。

## 3. 输入与输出

- 输入：
  1. 一个补充任务文件（如 `spec_CAMERA_RATION_FIX_task/TASK.md`）。
  2. 与输入任务文件同级目录下的固定变更日志文件 `CHANGED_LOG.md`。
- 输出：
  1. 基于任务拆解完成的代码实现（由 `/spec.land_task.agent` 落地）。
  2. 追加后的变更日志条目（写入固定 `CHANGED_LOG.md`）。
  3. 本轮执行摘要（任务完成情况 + 日志追加结果）。
  4. 编码执行留痕（含步骤文档与 `spec_modifylog/` 变更记录）。

## 4. 执行流程

### Step 1：预检查补充任务输入

1. 校验 `additionaltask` 文件存在。
2. 校验该任务文件可解析。
3. 若输入不完整，立即阻塞并返回最小补充清单。

### Step 2：主动调用落地 agent 完成编码

1. 必须先调用：`/spec.land_task.agent <additionaltask>`。
2. 按任务拆解与依赖顺序完成编码、步骤文档与变更记录。
3. 仅当任务执行完成（或明确可接受的跳过/阻塞已判定）后，进入下一步。

### Step 3：定位固定 CHANGED_LOG

1. 以输入任务文件 `<additionaltask>` 为基准，定位其同级目录。
2. 在该目录内定位固定日志文件：`CHANGED_LOG.md`。
3. 若文件不存在，可直接创建后再执行追加。
4. 禁止写入其他目录或其他命名日志文件，确保路径唯一且可追踪。

### Step 4：追加最简练变更话术

1. 以“追加”方式写入，不覆盖历史内容。
2. 话术要求：
   - 简洁、可追踪、面向 PRD 融合；
   - 仅描述“基于当前 task 的实现动作与关键改动结果”；
   - 避免冗长过程细节与无关背景。
3. 建议模板（可按项目格式微调）：

```markdown
- YYYY-MM-DD：基于 `<task目录>/TASK.md` 完成增量实现，已落地 `<关键改动点1>`、`<关键改动点2>`；同步更新执行留痕，当前改动可并入主 PRD 微调。
```

## 5. 变更日志写入规则（强制）

1. 仅追加，不改写既有历史条目语义。
2. 单次补充任务至少追加 1 条记录。
3. 若一次执行覆盖多个任务组，合并为 1~3 条高密度摘要，避免流水账。
4. 条目必须包含：
   - 任务来源（`<task目录>/TASK.md`）
   - 已完成的关键改动
   - 可用于主 PRD 融合的结论性描述
5. 日志文件固定为输入任务文件同级目录下的 `CHANGED_LOG.md`。

## 6. 歧义与阻塞处理（强制）

出现以下情况必须暂停：

1. `additionaltask` 任务文件不存在。
2. `/spec.land_task.agent` 未完成且无法确认当前代码状态。
3. `CHANGED_LOG.md` 可写性异常或存在冲突，无法保证追加正确性。

暂停时必须输出：

1. 阻塞原因（明确到目录/文件）。
2. 最小必要补充清单（按优先级）。
3. 用户可直接回复的澄清问题示例。

## 7. 输出格式

每轮执行完成后，输出：

```markdown
## 执行摘要

- 输入任务：`<additionaltask>`
- 调用执行：`/spec.land_task.agent <additionaltask>`
- 任务映射：`<additionaltask> -> <同级目录>/CHANGED_LOG.md`
- 编码结果：已完成 / 部分完成 / 阻塞（含原因）
- CHANGED_LOG：`<目标文件路径>`
- 追加内容：`<本次追加的最简练话术>`
- 可用于主 PRD 微调的结论：`<一句话结论>`
- 待确认问题：（若有）
```

要求：结果清晰、路径可追踪、可直接用于主 PRD 的需求微调融合。
