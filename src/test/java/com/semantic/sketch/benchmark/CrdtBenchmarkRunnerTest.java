package com.semantic.sketch.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrdtBenchmarkRunnerTest {
    @Test
    void automergeTraceLoader_parsesEditByIndexSpliceOperations() {
        String trace = "const edits = [[0, 0, 'a'], [1, 0, \"\\n\"], [1, 1], [1, 0, '\\u03bb']]\n"
                + "const finalText = 'aλ'\nmodule.exports = { edits, finalText }";

        List<CrdtBenchmarkOperation> operations = AutomergeEditingTraceLoader.parse(trace, 0);

        assertEquals(List.of(
                CrdtBenchmarkOperation.insert(0, "a"),
                CrdtBenchmarkOperation.insert(1, "\n"),
                CrdtBenchmarkOperation.delete(1, 1),
                CrdtBenchmarkOperation.insert(1, "λ")
        ), operations);
    }

    @Test
    void runner_collectsCrdtBenchmarksStyleMetricsForSyntheticScenario() throws Exception {
        CrdtBenchmarkResult result = CrdtBenchmarkRunner.run(new CrdtBenchmarkRunner.Config(
                "b1-append", 25, 1L, null, null));

        assertEquals("B1 append", result.benchmark());
        assertEquals(25, result.operationCount());
        assertEquals(25, result.docSizeBytes());
        assertTrue(result.timeNanos() > 0);
        assertTrue(result.updateSizeBytes() > 0);
        assertTrue(result.encodeTimeNanos() >= 0);
        assertTrue(result.parseTimeNanos() >= 0);
        assertNotEquals("", result.finalDocumentHash());
        assertTrue(result.toJson().contains("avgUpdateSizeBytes"));
    }

    @Test
    void runner_replaysAutomergeEditByIndexFixtureAndCanWriteJson() throws Exception {
        Path trace = Files.createTempFile("editing-trace", ".js");
        Path output = Files.createTempFile("benchmark-result", ".json");
        Files.writeString(trace, "const edits = [[0,0,'h'],[1,0,'i'],[1,1],[1,0,'!']]\nmodule.exports = { edits }\n");

        CrdtBenchmarkRunner.main(new String[] {
                "--scenario", "b4-edit-by-index",
                "--trace", trace.toString(),
                "--operations", "10",
                "--output", output.toString()
        });

        String json = Files.readString(output);
        assertTrue(json.contains("B4 real-world edit-by-index"));
        assertTrue(json.contains("\"operationCount\": 4"));
        assertTrue(json.contains("\"docSizeBytes\": 2"));
    }
}
