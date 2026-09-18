# Perf Investigation: Runtime Data Layout

Date: 2026-09-18  
Class: runtime throughput

The data-oriented entity pipeline is promoted. Component-heavy updates are 77.2% faster, and all secondary workloads stay within the 5% regression limit.

## Target

Reduce the median component-heavy event step by at least 10%. No secondary median or p99 may regress by more than 5%.

## Harness

Run `clojure -M:benchmark` from `modules/morphe-next`. Criterium warms each implementation, then collects 60 round-robin samples.

The primary workload delivers 25 broadcasts to 1,000 entities with four ordered components. Secondary workloads cover 10,000 targeted events, 25 broadcasts to 1,000 entities, and rendering 10,000 entities.

Environment: Apple M4 Pro, 48 GiB RAM, Darwin 25.6.0, Temurin OpenJDK 25.0.3.

## Results

| Workload | Baseline median | Data-oriented median | Change | Baseline p99 | Data-oriented p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Targeted | 5.364 ms | 3.018 ms | -43.7% | 5.639 ms | 3.123 ms |
| Broadcast | 10.580 ms | 5.233 ms | -50.5% | 11.199 ms | 5.480 ms |
| Components | 93.280 ms | 21.295 ms | -77.2% | 96.918 ms | 23.205 ms |
| Render | 2.321 ms | 2.326 ms | +0.2% | 2.437 ms | 2.418 ms |

## Profile and hypothesis

The baseline CPU profile attributes 72.4% of samples to `changed-keys` and 63.0% to component updates. Key sequences and map entries account for about 30% of sampled allocations.

The hypothesis was allocation pressure from rebuilding complete changed-key sets after every valid handler call. The promoted pipeline checks unchanged entries without allocating success-path sets and precomputes component-owned keys.

Flame graphs:

- `target/perf/runtime-data-layout/baseline-cpu.html`
- `target/perf/runtime-data-layout/baseline-alloc.html`
- `target/perf/runtime-data-layout/data-oriented-cpu.html`
- `target/perf/runtime-data-layout/data-oriented-alloc.html`

## Decision

Promote the descriptor pipeline behind `morphe.core`. Keep the original engine in the benchmark source path for repeatable comparisons.

Fixed-shape records remain because the plain-map experiment regressed rendering by about 8% in the first quick run. The FIFO queue remains unchanged because it was not a leading CPU or allocation source.
