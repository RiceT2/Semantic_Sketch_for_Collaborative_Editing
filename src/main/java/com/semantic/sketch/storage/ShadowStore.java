package com.semantic.sketch.storage;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.maintenance.OperationArchive;
import com.semantic.sketch.model.ShadowMetadata;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ShadowStore {
    void save(String branchId, MergeDecision decision);

    void save(String branchId, MergeDecision decision, ShadowMetadata metadata);

    Optional<MergeDecision> get(String branchId);

    Optional<ShadowMetadata> getMetadata(String branchId);

    Set<String> branchIds();

    void archive(String branchId, Message operation, String reason);

    List<OperationArchive> archives(String branchId);

    void clear(String branchId);
}
