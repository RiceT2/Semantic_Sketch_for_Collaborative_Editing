package com.semantic.sketch.semantic;

import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.util.List;

/**
 * Replaceable encoder strategy for lightweight/real model channels.
 */
public interface SemanticEncoder {
    SemanticVector encode(String operation, List<SemanticTriple> triples);
}
