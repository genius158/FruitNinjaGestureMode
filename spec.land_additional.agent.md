# spec.land_additional.agent

## 1. 目标

基于用户输入的 `prompt`，在既有主 PRD 约束下完成一次小步增量的代码调整与优化，并在编码完成后将本次需求语义合并回主 PRD。

一句话定位：`spec.land_additional.agent` 负责“按一句补充需求直接改代码 + 回写主 PRD”。

## 2. 使用方式

- 命令格式：`/spec.land_additional.agent <mainprd> <prompt>`
- 使用示例：`/spec.land_additional.agent feature/PROJECT_SUMMARY.md "修改支持识别手的个数为1"`

参数说明：

- `mainprd`：主 PRD 文件路径（本轮改动的需求基线）。
- `prompt`：补充需求的自然语言描述，用于触发本轮增量编码。

## 3. 输入与输出

- 输入：
  1. 一个主 PRD 文件 `mainprd`。
  2. 一条补充需求 `prompt`。
- 输出：
  1. 根据 `prompt` 完成的代码改动与必要测试/验证结果。
  2. 一份补充需求归档文件（`spec_additional/` 下）。
  3. 编码执行留痕（`spec_modifylog/` 与必要步骤记录）。
  4. 调用 `/spec.merge_prd.agent` 后更新完成的 `mainprd`。

## 4. 执行流程

### Step 1：预检查

1. 校验 `mainprd` 存在且可读取。
2. 校验 `prompt` 非空，且具有明确可实现语义。
3. 检查 `spec_additional/` 目录；若不存在，必须先自动创建后再继续执行。
4. 若任一条件不满足，或目录自动创建失败，进入阻塞（见第 8 节）。

### Step 2：将 prompt 结构化为补充需求

1. 读取 `mainprd`，识别与 `prompt` 相关的目标、约束、验收口径。
2. 生成本轮补充需求归档文件到 `spec_additional/`，建议命名：`<主题>_YYYYMMDDHHmmss.md`（若目录缺失应已在 Step 1 自动创建）。
3. 归档文件至少包含：
   - 需求意图（来自 `prompt`）
   - 影响范围（模块/文件/接口）
   - 验收标准（可验证）

### Step 3：执行增量编码与优化

1. 基于结构化补充需求完成代码改动，优先最小必要变更。
2. 同步补充测试或验证步骤，确保行为符合 `prompt` 预期。
3. 记录变更到 `spec_modifylog/`（遵循 `AMENDMENT.md` 命名与内容规则）。
4. 如涉及接口兼容性，必须在记录中说明影响范围与回滚要点。

### Step 4：调用主 PRD 合并 agent

1. 编码完成后，必须调用：
   - `/spec.merge_prd.agent <mainprd> <additional>`
2. 其中 `<additional>` 使用 Step 2 生成的补充需求归档文件路径。
3. 若仅有原始 `prompt`，不得直接跳过结构化归档步骤。

### Step 5：输出本轮结果

输出编码结果、补充归档文件路径、PRD 合并结果与待确认项。

## 5. 编码与文档约束（强制）

1. 必须遵循：`CONSTITUTION.md`、`AMENDMENT.md`。
2. 以正确性和兼容性优先，避免为单次 prompt 进行过度重构。
3. 改动必须可追踪：代码、变更记录、补充归档、PRD 合并四者要能相互映射。
4. 禁止只改文档不改代码（除非 `prompt` 明确为文档修订任务）。
5. 禁止只改代码不回写 PRD（即必须执行 Step 4）。

## 6. 产物路径约定

- 补充需求归档：`spec_additional/<主题>_YYYYMMDDHHmmss.md`（目录不存在时需自动创建）
- 变更留痕：`spec_modifylog/<标题>_YYYYMMDDHHmmss.md`
- 主 PRD：`<mainprd>`（原路径更新）

示例链路：

1. 输入：`/spec.land_additional.agent feature/PROJECT_SUMMARY.md "修改支持识别手的个数为1"`
2. 生成：`spec_additional/gesture_max_num_hands_20260511113000.md`
3. 编码完成后调用：`/spec.merge_prd.agent feature/PROJECT_SUMMARY.md spec_additional/gesture_max_num_hands_20260511113000.md`

## 7. 输出格式

每轮执行完成后，输出：

```markdown
## 执行摘要

- 输入主 PRD：`<mainprd>`
- 输入 prompt：`<prompt>`
- 补充归档：`<spec_additional/...md>`
- 编码结果：已完成 / 部分完成 / 阻塞（含原因）
- 关键改动文件：`<file1>`, `<file2>` ...
- 变更记录：`<spec_modifylog/...md>`
- PRD 合并调用：`/spec.merge_prd.agent <mainprd> <additional>`
- PRD 合并结果：已完成 / 未完成（含原因）
- 待确认问题：（若有）
```

要求：结果清晰、路径可追踪、可直接进入下一轮补充需求。

## 8. 歧义与阻塞处理（强制）

出现以下情况必须暂停，禁止猜测执行：

1. `mainprd` 不存在或不可读。
2. `prompt` 语义过于模糊，无法提取验收标准。
3. 编码涉及关键行为冲突，且无法判断应遵循主 PRD 还是 prompt 最新意图。
4. `spec_additional/` 或 `spec_modifylog/` 无法写入。
5. `/spec.merge_prd.agent` 调用失败且无法自动重试修复。

暂停时必须输出：

1. 阻塞原因（定位到文件/步骤）。
2. 最小必要补充清单（按优先级）。
3. 可直接回复的澄清问题示例。
