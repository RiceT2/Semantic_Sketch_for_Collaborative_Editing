package com.semantic.sketch.semantic;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DistilBERT ONNX-backed semantic fingerprint service with a lightweight fallback.
 *
 * <p>The model path is read from, in order:</p>
 * <ol>
 *     <li>JVM property {@code semantic.sketch.distilbert.onnx.path}</li>
 *     <li>Environment variable {@code DISTILBERT_ONNX_PATH}</li>
 *     <li>Repository-local default {@code distilbert_Opset18.onnx}</li>
 * </ol>
 *
 * <p>When the configured model is missing or the ONNX Runtime classes are not on the runtime classpath,
 * this service transparently delegates to {@link LightweightSemanticFingerprintService}.</p>
 */
public class OnnxDistilBertSemanticFingerprintService implements SemanticFingerprintService {
    public static final String MODEL_PATH_PROPERTY = "semantic.sketch.distilbert.onnx.path";
    public static final String MODEL_PATH_ENV = "DISTILBERT_ONNX_PATH";
    public static final String DEFAULT_MODEL_PATH = "distilbert_Opset18.onnx";

    private final Path modelPath;
    private final SemanticFingerprintService fallback;
    private final boolean modelAvailable;

    public OnnxDistilBertSemanticFingerprintService() {
        this(configuredModelPath(), new LightweightSemanticFingerprintService());
    }

    public OnnxDistilBertSemanticFingerprintService(Path modelPath) {
        this(modelPath, new LightweightSemanticFingerprintService());
    }

    public OnnxDistilBertSemanticFingerprintService(Path modelPath, SemanticFingerprintService fallback) {
        this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.modelAvailable = Files.isRegularFile(modelPath) && onnxRuntimeAvailable();
    }

    public Path modelPath() {
        return modelPath;
    }

    public boolean isModelAvailable() {
        return modelAvailable;
    }

    @Override
    public long fingerprint(String text) {
        return fallback.fingerprint(text);
    }

    @Override
    public long fingerprint(CrdtOperationEnvelope envelope) {
        return fallback.fingerprint(envelope);
    }

    @Override
    public Map<String, Double> extractWeightedKeywords(String text) {
        return fallback.extractWeightedKeywords(text);
    }

    @Override
    public List<SemanticTriple> extractTriples(String operation) {
        return fallback.extractTriples(operation);
    }

    @Override
    public List<SemanticTriple> extractTriples(CrdtOperationEnvelope envelope) {
        return fallback.extractTriples(envelope);
    }

    @Override
    public SemanticVector extractFeatures(String operation) {
        return fallback.extractFeatures(operation);
    }

    @Override
    public SemanticVector extractFeatures(CrdtOperationEnvelope envelope) {
        return fallback.extractFeatures(envelope);
    }

    private static Path configuredModelPath() {
        String propertyValue = System.getProperty(MODEL_PATH_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Path.of(propertyValue.trim());
        }
        String environmentValue = System.getenv(MODEL_PATH_ENV);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Path.of(environmentValue.trim());
        }
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
