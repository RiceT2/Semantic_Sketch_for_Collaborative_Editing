# 协同编辑消融框架（Java 18 原型）

本仓库目标：构建一个**高性能、可解释、可做人机协同仲裁**的协同编辑消融框架。

## 1) 语义指纹 + 局部语义校验 + 低拷贝传输

### 已实现
- `Message` 扩展了 `long semanticFingerprint` 字段，用于压缩语义状态。
- `SimHash64`：把关键词权重映射到 64 位指纹。
- `SlidingWindowSemanticValidator`：仅在活动窗口内做局部语义校验（类似 TCP 窗口思想）。
- `SemanticProtocolCodec`：基于 Netty `CompositeByteBuf` 的协议封包，降低内存拷贝与序列化开销。

### 可替换点
- 当前 `LightweightSemanticFingerprintService` 使用轻量关键词抽取。
- 生产环境可替换为 DJL + ONNX 模型推理，再把 embedding 投影到关键词权重。

## 2) 自动消融引擎（带人机协同接口）

### 已实现流水线
1. **收集阶段**：`ConflictManager` 基于向量时钟识别并发冲突。
2. **建模阶段**：`FactorGraphBuilder` 构建因子图，边权 `ψ = 1 / (1 + hammingDistance)`。
3. **推理阶段**：`GreedyInferenceEngine` 输出近似最优合并建议 `X*`。
4. **影子存储**：`ShadowStore` 抽象 + `InMemoryShadowStore`（预留 Redis MVCC 替换位）。
5. **提交/回溯**：`AutoAblationEngine` 结合 `HumanArbiter` 支持用户确认与一键回滚。

## 3) 与其他算法对比 + 实验验证

新增实验包 `com.semantic.sketch.experiment`，提供可复现实验。

### 对比算法
- **创新方法（本方案）**：`SemanticFingerprintWindowStrategy`
  - SimHash 语义指纹 + 滑动窗口局部校验。
- **基线A**：`FullHistoryKeywordOverlapStrategy`
  - 全历史关键词 Jaccard 扫描（高开销、可解释但慢）。
- **基线B**：`Sha256FullHistoryStrategy`
  - 全历史 SHA-256 精确匹配（低语义召回）。

### 指标
- `comparisons`：校验比较次数（代理 CPU 成本）。
- `throughputOpsPerSecond`：吞吐（ops/s）。
- `estimatedBytes`：状态存储估计（代理内存负担）。
- `precision/recall/F1`：冲突识别效果。

### 默认实验配置
- 工作负载：`2,000` 条操作，固定随机种子 `20260328`。
- 创新策略窗口：`window=96`，`maxHammingDistance=12`。
- 命令入口：`ExperimentCli`。

### 理论上界（同配置下）
> 比较次数可由策略结构直接推导。

- 创新方法（窗口 96）：约 `187,344` 次比较。
- 全历史方法（A/B）：`1,999,000` 次比较。
- 对比可见，窗口法将比较次数压缩到约 **9.37%**。

### 如何复现实验
```bash
mvn test
mvn -q -DskipTests exec:java -Dexec.mainClass=com.semantic.sketch.experiment.ExperimentCli
```

> 若本地 Maven 可联网，`ExperimentRunnerTest` 会验证：
> - 创新方法比较次数 < 全历史扫描。
> - 创新方法估计内存 < 全历史关键词存储。
> - 创新方法语义召回率 > SHA-256 精确匹配。

## 4) 下一阶段建议
- 接入 DJL/ONNX 轻量模型，升级关键词/权重抽取。
- 将 `ShadowStore` 替换为 Redis MVCC（版本号 + CAS）以支持高并发。
- 用 Spring AI 接入本地模型（如 DeepSeek-7B）实现自动仲裁建议。
- 第三阶段再扩展多模态（文本-图形）约束矩阵与实时对齐。
