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
   - 影子存储：`ShadowStore`（当前是 `InMemoryShadowStore`，可替换为 Redis MVCC）。
   - 提交/回溯：`AutoAblationEngine` + `HumanArbiter`。

5. **后台图维护**
   - `GraphMaintenanceService`：可通过 `startPeriodic` 周期扫描 `ShadowStore` 中的影子图快照，先检查因果稳定性；不稳定分支会在报告中记录跳过原因。
   - 对无主干前置依赖且未被接受节点引用的孤立拒绝操作进行归档后物理删除，保留 `OperationArchive` 便于审计追溯。
   - 对语义漂移量小于阈值 ε 的连续同作者接受节点进行压缩合并，并在清理前后写入维护元数据保证读取一致性。
   - `MaintenanceReport` 输出删除数、压缩数和跳过原因，当前基于 `InMemoryShadowStore`，后续可切换到 Redis/MVCC 等持久化存储。


6. **浏览器验证台与多用户转发**
   - `CollaborativeEditingWebServer`：基于 JDK `HttpServer` 提供静态页面、REST API 与 Server-Sent Events 实时事件通道。
   - `CollaborationSessionHub`：在内存中模拟多用户向量时钟、语义指纹生成、并发冲突检测、最优方案计算和人工介入请求。
   - 前端页面 `src/main/resources/static/index.html`：支持提交编辑、查看冲突提示、处理人工介入请求，并可在多个浏览器窗口间观察实时转发。


## 意图残留度 R

`IntentResidualCalculator` 位于 `ablation` 模块，用于把合并结果中“被保留下来的意图”统一映射为 0~1 的量纲：

```text
R = Σ(accepted_i × semanticWeight_i × entropyWeight_i × roleWeight_i)
    / Σ(all_i × semanticWeight_i × entropyWeight_i × roleWeight_i)
```

- `semanticWeight_i`：操作语义重要度，默认值为 1.0，可由上层按操作 ID 传入。
- `entropyWeight_i`：信息熵权重，默认由操作文本自动归一化估计，也可由上层按操作 ID 传入。
- `roleWeight_i`：用户角色权重，默认值为 1.0，可按 `actorId` 传入。
- `R = 1` 表示候选方案完整保留了加权意图；`R = 0` 表示加权意图完全丢失。
- 阈值方向固定为：当 `R < τ` 时触发 `HumanArbiter`；当 `R >= τ` 时可自动应用最优方案。

`HumanArbiter` 现在返回 `HumanArbitrationResult`，结果类型包括接受系统方案和回溯重做；回溯结果预留 `reason`、`decidedBy`、`rollbackScope` 审计字段，便于后续接入日志恢复与审计系统。

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
