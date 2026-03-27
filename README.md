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

## 后续建议

- 接入 DJL/ONNX：将 `extractWeightedKeywords` 替换为 embedding + 稀疏投影。
- 接入 Redis MVCC：`ShadowStore` 改为 Redis Hash + 版本号 CAS。
- 接入 Spring AI + 本地模型（如 DeepSeek-7B）：为 `HumanArbiter` 提供快速自动仲裁建议。
- 多模态（文本-图形）约束矩阵：可作为下一阶段扩展（本原型未展开）。

## 运行

```bash
mvn test
```
