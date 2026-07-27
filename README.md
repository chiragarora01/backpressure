# Early Backpressure Detection

A zero-allocation, constant-time latency gradient detector for high-throughput distributed systems.

This repository contains the Java reference implementation, a high-throughput pipeline simulator, and JMH microbenchmarks as detailed in the paper: *Early Backpressure Detection for High-Throughput Distributed Systems: A Latency Gradient Based Approach Using Dual EWMA and Queue Occupancy*.

## Features

* **O(1) Evaluation:** Dual EWMA and gradient calculations execute in ~0.5µs per event.
* **Zero-Allocation:** Built on a cache-line padded SPSC ring buffer to eliminate GC pauses.
* **Early Warning:** Identifies downstream degradation before physical queue exhaustion.

## Requirements

* JDK 21+
* Apache Maven 3.8+

## Quick Start

Build the project and package the JMH benchmarks:

```bash
mvn clean package
```

### 1. Run the Simulator

Models a 10,000 msg/sec pipeline, injects a downstream bottleneck at T≈3.2s, and automatically generates a LaTeX (`pgfplots`) evaluation graph (`backpressure-graph.tex`).

```bash
java -XX:-RestrictContended --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -cp target/classes org.example.ZeroAllocSimulator
```

### 2. Run the Microbenchmarks

Verifies the algorithmic execution overhead on your local hardware using the Java Microbenchmark Harness (JMH).

```bash
java -XX:-RestrictContended --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -jar target/benchmarks.jar
```

## License

This project is licensed under the [Apache License 2.0](LICENSE).