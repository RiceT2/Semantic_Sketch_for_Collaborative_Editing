# Benchmark Baseline

Workload dimensions are aligned with `dmonad/crdt-benchmarks` core axes:
- document size
- actor count
- concurrency window
- edit pattern (`insert-heavy`, `delete-heavy`, `mixed`)

## Trace format (JSONL)
Each line:
```json
{"opId":"op-1","actorId":"actor-1","seq":1,"mode":"mixed","opType":"insert","index":0,"text":"a","deleteLen":0,"logicalTs":1}
```

The same trace is replayable by:
- `InMemoryTextCrdtAdapter`
- `YjsUpdateCrdtAdapter` (sidecar)
- future JSON-CRDT adapter

## Results layout
`benchmarks/results/<date>/<adapter>_smoke.json`

## Compare with previous baseline
```bash
python3 benchmarks/compare_results.py benchmarks/results/2026-01-01 benchmarks/results/2026-01-02
```
