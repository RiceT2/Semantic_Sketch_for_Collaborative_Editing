package com.semantic.sketch.crdt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process sidecar stub that provides deterministic merge/export behavior for replay validation.
 */
public class InProcessYjsSidecarClient implements YjsSidecarClient {
    private static final String VERSION = "in-process-yjs-sidecar/v1";
    private final Map<String, TreeSet<String>> branchUpdates = new ConcurrentHashMap<>();

    @Override
    public synchronized SidecarMergeResult applyUpdate(String branchId, String yjsUpdateBase64) {
        TreeSet<String> updates = branchUpdates.computeIfAbsent(branchId, ignored -> new TreeSet<>());
        updates.add(yjsUpdateBase64);
        Map<String, Long> stateVector = Map.of("updates", (long) updates.size());
        return new SidecarMergeResult(stateVector, computeHash(updates));
    }

    @Override
    public synchronized String exportText(String branchId) {
        TreeSet<String> updates = branchUpdates.getOrDefault(branchId, new TreeSet<>());
        StringBuilder builder = new StringBuilder();
        for (String update : updates) {
            builder.append(decodeSafe(update));
        }
        return builder.toString();
    }

    @Override
    public synchronized Map<String, ?> exportJson(String branchId) {
        TreeSet<String> updates = branchUpdates.getOrDefault(branchId, new TreeSet<>());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branchId);
        out.put("updateCount", updates.size());
        out.put("updates", updates.stream().toList());
        return out;
    }

    @Override
    public String sidecarVersion() {
        return VERSION;
    }

    private String decodeSafe(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String computeHash(TreeSet<String> updates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String update : updates) {
                digest.update(update.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
