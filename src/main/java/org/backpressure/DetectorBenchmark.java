package org.backpressure;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class DetectorBenchmark {

    private ZeroAllocSimulator.Detector detector;
    private long mockLatencyUs;
    private double mockOccupancy;

    @Setup(Level.Iteration)
    public void setup() {
        detector = new ZeroAllocSimulator.Detector();
        mockLatencyUs = 80;
        mockOccupancy = 0.10;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void measureAlgorithmOverhead(Blackhole blackhole) {
        mockLatencyUs = (mockLatencyUs == 80) ? 85 : 80;
        detector.update(mockLatencyUs, mockOccupancy);
        blackhole.consume(detector.state);
        blackhole.consume(detector.advisoryRate);
    }
}