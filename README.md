# 协同编辑消融框架（Java 原型）

本仓库提供一个可落地的最小原型，用于支持目标：
- 高性能：语义指纹 + 局部滑动窗口校验 + Netty ByteBuf 零拷贝协议封装。
- 可解释：冲突图（因子图）建模 + 可审计的边权（`ψ=1/(1+distance)`）。
- 人机协同：自动推理给出合并建议，用户确认后提交，否则直接回滚。

## 已实现模块

1. **语义指纹链路**
   - `LightweightSemanticFingerprintService`：轻量关键词抽取（可替换为 DJL + ONNX）。
   - `SimHash64`：将关键词权重映射为 64 位指纹。
   - `Message.semanticFingerprint`：CRDT 消息结构扩展。

2. **局部滑动窗口语义校验**
   - `SlidingWindowSemanticValidator`：只对窗口内受影响片段做逻辑校验（类似 TCP 滑动窗口）。

3. **Netty 自定义协议与零拷贝编码**
   - `SemanticProtocolCodec`：使用 `CompositeByteBuf` 组合 header/body/vectorClock，减少额外拷贝。

4. **自动消融引擎**
   - 冲突收集：`ConflictManager`（基于向量时钟并发判断）。
   - 建模：`FactorGraphBuilder`（以 SimHash 汉明距离生成边权 ψ）。
   - 推理：`GreedyInferenceEngine`（轻量贪心近似）。
   - 意图残差评分：`IntentResidualCalculator` 按操作执行状态、语义权重、信息熵权重、角色权重计算统一量纲的 R。
   - 影子存储：`ShadowStore`（当前是 `InMemoryShadowStore`，可替换为 Redis MVCC）。
   - 提交/回溯：`AutoAblationEngine` + `HumanArbiter`，人工结果支持接受系统方案或回溯重做。

5. **后台图维护**
   - `GraphMaintenanceService`：可通过 `startPeriodic` 周期扫描 `ShadowStore` 中的影子图快照，先检查因果稳定性；不稳定分支会在报告中记录跳过原因。
   - 对无主干前置依赖且未被接受节点引用的孤立拒绝操作进行归档后物理删除，保留 `OperationArchive` 便于审计追溯。
   - 对语义漂移量小于阈值 ε 的连续同作者接受节点进行压缩合并，并在清理前后写入维护元数据保证读取一致性。
   - `MaintenanceReport` 输出删除数、压缩数和跳过原因，当前基于 `InMemoryShadowStore`，后续可切换到 Redis/MVCC 等持久化存储。


6. **浏览器验证台与多用户转发**
   - `CollaborativeEditingWebServer`：基于 JDK `HttpServer` 提供静态页面、REST API 与 Server-Sent Events 实时事件通道。
   - `CollaborationSessionHub`：在内存中模拟多用户向量时钟、语义指纹生成、并发冲突检测、最优方案计算和人工介入请求。
   - 前端页面 `src/main/resources/static/index.html`：支持提交编辑、查看冲突提示、处理人工介入请求，并可在多个浏览器窗口间观察实时转发。


## API 兼容策略

浏览器验证台当前保留 `/api/operations` 的轻量请求格式，以便 demo 和已有脚本继续运行；该格式只把用户编辑意图放在 `payload` 字符串中。后续真实协同协议应逐步迁移到 `CrdtOperationEnvelope`，由 envelope 明确描述操作类型、目标路径、编辑范围、用户意图、Yjs update 二进制内容以及语义元数据。

### 兼容原则

1. **旧 `payload` 仍可用作 demo 兼容层**：`POST /api/operations` 继续接受 `branchId`、`actorId`、`payload` 三个字段；服务端把 `payload` 视为用户自然语言编辑说明，而不是完整 CRDT 操作。
2. **新协议使用 `CrdtOperationEnvelope`**：新客户端应发送结构化 envelope，至少包含 `operationType`、`targetPath`、`range`、`intent`、`yjsUpdate` 和 `semanticMetadata`。其中 `yjsUpdate` 建议使用 Base64 编码承载 Yjs update 字节，便于在 JSON API 中传输。
3. **旧请求默认转换规则**：
   - 当旧请求的 `payload` 看起来是内容插入或替换类编辑（例如包含 `insert`、`add`、`replace`、`write`、`append`，或没有明确注释语义）时，兼容层默认转换为 `operationType=INSERT`。
   - 当 `payload` 明确表现为批注、评论、标记、解释等语义（例如包含 `annotate`、`comment`、`note`、`tag`、`explain`）时，兼容层默认转换为 `operationType=ANNOTATE`。
   - 旧请求没有结构化路径和范围时，默认 `targetPath="/document"`，`range` 使用当前光标或服务端可推断的最小范围；无法推断时使用空范围 `{ "start": 0, "end": 0 }`。
   - 旧请求的 `intent.summary` 直接来自 `payload`，`intent.source="legacy-payload"`；`semanticMetadata.compatibilityMode=true`，并记录 `semanticMetadata.defaultRule` 说明命中的默认转换规则。
4. **迁移建议**：服务端可在一段时间内同时接受旧请求和 envelope；新功能（精确路径、多范围、Yjs 增量同步、可解释语义标签）只保证在 `CrdtOperationEnvelope` 上完整表达。

### 旧请求示例

```http
POST /api/operations
Content-Type: application/json

{
  "branchId": "main",
  "actorId": "alice",
  "payload": "replace sky with blue gradient"
}
```

兼容层可将上面的旧请求解释为：

```json
{
  "operationType": "INSERT",
  "targetPath": "/document",
  "range": { "start": 0, "end": 0 },
  "intent": {
    "summary": "replace sky with blue gradient",
    "source": "legacy-payload"
  },
  "yjsUpdate": null,
  "semanticMetadata": {
    "compatibilityMode": true,
    "defaultRule": "legacy payload without explicit annotation keywords defaults to INSERT"
  }
}
```

### 新 `CrdtOperationEnvelope` 请求示例

```http
POST /api/operations
Content-Type: application/json

{
  "branchId": "main",
  "actorId": "alice",
  "envelope": {
    "operationId": "op-20260511-0001",
    "operationType": "ANNOTATE",
    "targetPath": "/canvas/layers/sky",
    "range": {
      "start": 120,
      "end": 184,
      "unit": "utf16-code-unit"
    },
    "intent": {
      "summary": "Mark the sky layer as needing a softer blue gradient.",
      "confidence": 0.92,
      "source": "user"
    },
    "yjsUpdate": "AQIDBAU=",
    "semanticMetadata": {
      "fingerprint": "simhash64:8f14e45fceea167a",
      "tags": ["sky", "gradient", "annotation"],
      "compatibilityMode": false,
      "schemaVersion": "crdt-operation-envelope/v1"
    }
  }
}
```

## 意图残差 R 与阈值

`IntentResidualCalculator` 输出的 R 是一个无量纲归一化分数，范围固定为 `[0, 1]`：

```text
R = Σ(completion(op) × weight(op)) / Σ(weight(op))
weight(op) = mean(semanticWeight(op), entropyWeight(op), roleWeight(op))
```

- `completion(op)` 来自最优方案下的操作执行状态：`EXECUTED=1.0`、`PARTIAL=0.5`、`SKIPPED=0.0`。
- `semanticWeight`、`entropyWeight`、`roleWeight` 都会被裁剪到 `[0, 1]` 后再求平均，确保不同来源权重处于统一量纲。
- R 越高表示系统方案保留的加权意图越完整；R 越低表示关键意图被跳过或只部分执行的风险越高。
- 主编排入口使用阈值 `τ` 判断：当 `R < τ` 时触发 `HumanArbiter`；当 `R >= τ` 时自动接受最优方案。浏览器验证台默认示例阈值为 `τ=0.80`。
- `HumanArbiter` 的结果只有两类：`ACCEPT_SYSTEM_PLAN` 表示接受系统最优方案；`ROLLBACK_REDO` 表示回溯重做，并会把触发原因、决策人、回溯范围写入 `RecoveryAudit` 传给日志恢复流程。


## 后续建议

- 接入 DJL/ONNX：将 `extractWeightedKeywords` 替换为 embedding + 稀疏投影。
- 接入 Redis MVCC：`ShadowStore` 改为 Redis Hash + 版本号 CAS。
- 接入 Spring AI + 本地模型（如 DeepSeek-7B）：为 `HumanArbiter` 提供快速自动仲裁建议。
- 多模态（文本-图形）约束矩阵：可作为下一阶段扩展（本原型未展开）。

## 运行

```bash
mvn test
```

启动浏览器验证台：

```bash
mvn -DskipTests compile
java -cp target/classes com.semantic.sketch.web.CollaborativeEditingWebServer 8080
```

如果没有配置 Maven Exec 插件，也可以在 IDE 中直接运行 `com.semantic.sketch.web.CollaborativeEditingWebServer`，然后访问 `http://localhost:8080`。
