package com.semantic.sketch.semantic;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DistilBERT ONNX 纯本地推理语义指纹服务。
 */
public class OnnxDistilBertSemanticFingerprintService implements SemanticFingerprintService {
    public static final String MODEL_PATH_PROPERTY = "semantic.sketch.distilbert.onnx.path";
    public static final String MODEL_PATH_ENV = "DISTILBERT_ONNX_PATH";
    public static final String DEFAULT_MODEL_PATH = "src/main/resources/static/distilbert_Opset18.onnx";
    public static final String DEFAULT_TOKENIZER_PATH = "src/main/resources/static/tokenizer.json";

    private final Path modelPath;
    private final SemanticFingerprintService fallback;
    private final boolean modelAvailable;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public OnnxDistilBertSemanticFingerprintService() {
        this(configuredModelPath(), new LightweightSemanticFingerprintService());
    }

    public OnnxDistilBertSemanticFingerprintService(Path modelPath) {
        this(modelPath, new LightweightSemanticFingerprintService());
    }

    public OnnxDistilBertSemanticFingerprintService(Path modelPath, SemanticFingerprintService fallback) {
        this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
        this.fallback = Objects.requireNonNull(fallback, "fallback");

        boolean basicAvailable = Files.isRegularFile(modelPath) && onnxRuntimeAvailable();
        Path tokenizerPath = Path.of(DEFAULT_TOKENIZER_PATH);

        boolean success = false;
        if (basicAvailable && Files.isRegularFile(tokenizerPath)) {
            try {
                this.env = OrtEnvironment.getEnvironment();
                this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
                this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
                success = true;
            } catch (Exception e) {
                System.err.println("【警告】本地 ONNX 初始化失败: " + e.getMessage());
            }
        }
        this.modelAvailable = success;
    }

    /**
     * 核心特征提取：将本地模型推理出的 float[] 转换为系统定义的 Map<String, Double> 特征
     */
    @Override
    public SemanticVector extractFeatures(String operation) {
        if (operation == null || operation.isBlank()) {
            return fallback.extractFeatures(operation);
        }

        if (modelAvailable && session != null && tokenizer != null) {
            try {
                // 1. 本地分词
                var encoding = tokenizer.encode(operation);
                long[] inputIds = encoding.getIds();
                long[] attentionMask = encoding.getAttentionMask();

                long[] shape = new long[]{1, inputIds.length};

                try (
                        OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(inputIds), shape);
                        OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attentionMask), shape)
                ) {
                    Map<String, OnnxTensor> inputs = Map.of(
                            "input_ids", inputIdsTensor,
                            "attention_mask", attentionMaskTensor
                    );

                    try (OrtSession.Result results = session.run(inputs)) {
                        OnnxTensor outputTensor = (OnnxTensor) results.get(0);
                        float[][][] outputData = (float[][][]) outputTensor.getValue();

                        // 2. 拿到 [CLS] 处的 768 维密集特征向量
                        float[] clsEmbedding = outputData[0][0];

                        // 3. 【核心适配修改】将 float[] 转为系统的 Map<String, Double> 特征结构
                        Map<String, Double> featureMap = new HashMap<>(clsEmbedding.length);
                        for (int i = 0; i < clsEmbedding.length; i++) {
                            // 格式化为 "dim_0", "dim_1" 的特征名，数值转为 Double
                            featureMap.put("dim_" + i, (double) clsEmbedding[i]);
                        }

                        // 4. 成功装配出满足系统的 Java Record 实例
                        return new SemanticVector(featureMap);
                    }
                }
            } catch (Exception e) {
                System.err.println("【异常】本地 ONNX 推理失败，触发降级: " + e.getMessage());
                return fallback.extractFeatures(operation);
            }
        }
        return fallback.extractFeatures(operation);
    }

    @Override
    public SemanticVector extractFeatures(CrdtOperationEnvelope envelope) {
        if (envelope == null) return fallback.extractFeatures(envelope);
        // 【安全防报错适配】如果 envelope 没有 getOperation()，通过透明路由至 fallback 提取，或提取其 String 表达形式
        try {
            return extractFeatures(envelope.toString());
        } catch (Exception e) {
            return fallback.extractFeatures(envelope);
        }
    }

    /**
     * 计算语义指纹：直接对 Record 内部的 Map 进行高性能一致性哈希
     */
    @Override
    public long fingerprint(String text) {
        if (modelAvailable) {
            SemanticVector vector = extractFeatures(text);
            if (vector != null && vector.features() != null) {
                // 【完美适配】调用 Record 的 features() 方法获取 Map 并计算常规哈希指纹
                return vector.features().hashCode();
            }
        }
        return fallback.fingerprint(text);
    }

    @Override
    public long fingerprint(CrdtOperationEnvelope envelope) {
        if (envelope == null) return fallback.fingerprint(envelope);
        try {
            return fingerprint(envelope.toString());
        } catch (Exception e) {
            return fallback.fingerprint(envelope);
        }
    }

    @Override
    public List<SemanticTriple> extractTriples(String operation) { return fallback.extractTriples(operation); }
    @Override
    public List<SemanticTriple> extractTriples(CrdtOperationEnvelope envelope) { return fallback.extractTriples(envelope); }
    @Override
    public Map<String, Double> extractWeightedKeywords(String text) { return fallback.extractWeightedKeywords(text); }

    public Path modelPath() { return modelPath; }
    public boolean isModelAvailable() { return modelAvailable; }

    private static Path configuredModelPath() {
        String propertyValue = System.getProperty(MODEL_PATH_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) return Path.of(propertyValue.trim());
        String environmentValue = System.getenv(MODEL_PATH_ENV);
        if (environmentValue != null && !environmentValue.isBlank()) return Path.of(environmentValue.trim());
        return Path.of(DEFAULT_MODEL_PATH);
    }

    private static boolean onnxRuntimeAvailable() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}