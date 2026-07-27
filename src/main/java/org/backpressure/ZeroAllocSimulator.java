package org.backpressure;

import net.openhft.affinity.AffinityLock;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ZeroAllocSimulator {

    static class SpscRingBuffer {
        private final int capacity;
        private final int mask;
        private final long[] buffer;

        @jdk.internal.vm.annotation.Contended
        private final AtomicLong tail = new AtomicLong(0);

        @jdk.internal.vm.annotation.Contended
        private final AtomicLong head = new AtomicLong(0);

        public SpscRingBuffer(int powerOfTwoCapacity) {
            this.capacity = powerOfTwoCapacity;
            this.mask = capacity - 1;
            this.buffer = new long[capacity];
        }

        public boolean offer(long value) {
            long currentTail = tail.get();
            if (currentTail - head.get() == capacity) return false;
            buffer[(int) (currentTail & mask)] = value;
            tail.lazySet(currentTail + 1);
            return true;
        }

        public long poll() {
            long currentHead = head.get();
            if (currentHead == tail.get()) return -1;
            long value = buffer[(int) (currentHead & mask)];
            head.lazySet(currentHead + 1);
            return value;
        }

        public double getOccupancy() {
            return (double) (tail.get() - head.get()) / capacity;
        }
    }

    static class DataPoint {
        long timeMs;
        double occupancyPct, latencyUs, gradient;

        DataPoint(long timeMs, double occupancyPct, double latencyUs, double gradient) {
            this.timeMs = timeMs;
            this.occupancyPct = occupancyPct;
            this.latencyUs = latencyUs;
            this.gradient = gradient;
        }
    }

    static class Detector {
        double shortEwma = -1, longEwma = -1, advisoryRate = 10000;
        final double alphaS = 0.2, alphaL = 0.005, gTrip = 0.9, occHigh = 0.75, occLow = 0.50;

        enum State { NORMAL, HIGH }
        State state = State.NORMAL;
        long messageCount = 0;

        List<DataPoint> history = new ArrayList<>();
        long startTimeMs = System.currentTimeMillis();

        public void update(long latencyUs, double occupancy) {
            if (shortEwma < 0) {
                shortEwma = latencyUs;
                longEwma = latencyUs;
            } else {
                shortEwma = (alphaS * latencyUs) + (1 - alphaS) * shortEwma;
                longEwma = (alphaL * latencyUs) + (1 - alphaL) * longEwma;
            }

            double gradient = longEwma / shortEwma;

            if (gradient < gTrip || occupancy > occHigh) {
                state = State.HIGH;
                advisoryRate *= 0.75;
            } else if (gradient >= gTrip && occupancy < occLow) {
                state = State.NORMAL;
                advisoryRate += 50;
            }

            // Downsample: Record 1 point every 1000 messages to keep LaTeX compiling fast
            if (++messageCount % 1000 == 0) {
                long elapsedMs = System.currentTimeMillis() - startTimeMs;
                history.add(new DataPoint(elapsedMs, occupancy * 100, latencyUs, gradient));
            }
        }
    }

    private static void spinWaitNanos(long nanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < nanos) {
            Thread.onSpinWait();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        SpscRingBuffer queue = new SpscRingBuffer(1024);
        Detector detector = new Detector();
        AtomicBoolean running = new AtomicBoolean(true);

        System.out.println("Starting simulation... Please wait 10 seconds.");

        Thread producer = new Thread(() -> {
            try (AffinityLock al = AffinityLock.acquireLock()) {
                while (running.get()) {
                    while (!queue.offer(System.nanoTime())) {
                        Thread.yield();
                    }
                    spinWaitNanos(100_000);
                }
            }
        });

        Thread consumer = new Thread(() -> {
            try (AffinityLock al = AffinityLock.acquireLock()) {
                long startTime = System.currentTimeMillis();
                while (running.get()) {
                    long enqueueTime = queue.poll();
                    if (enqueueTime == -1) continue;

                    long elapsedMs = System.currentTimeMillis() - startTime;

                    if (elapsedMs > 3000 && elapsedMs < 7000) {
                        spinWaitNanos(200_000);
                    } else {
                        spinWaitNanos(50_000);
                    }

                    long latencyUs = (System.nanoTime() - enqueueTime) / 1000;
                    detector.update(latencyUs, queue.getOccupancy());
                }
            }
        });

        producer.start();
        consumer.start();

        Thread.sleep(10000);
        running.set(false);

        producer.join();
        consumer.join();

        generateLatexReport(detector.history);
    }

    private static void generateLatexReport(List<DataPoint> history) {
        StringBuilder occCoords = new StringBuilder();
        StringBuilder gradCoords = new StringBuilder();

        for (DataPoint dp : history) {
            double timeSec = dp.timeMs / 1000.0;
            // Cap gradient at 2.5 for a clean chart
            double displayGradient = Math.min(dp.gradient, 2.5);

            occCoords.append(String.format("(%.2f, %.2f) ", timeSec, dp.occupancyPct));
            gradCoords.append(String.format("(%.2f, %.2f) ", timeSec, displayGradient));
        }

        // We use {OCC_COORDS} and {GRAD_COORDS} placeholders to avoid % conflicts
        String latexTemplate = """
            \\documentclass[border=10pt]{standalone}
            \\usepackage{pgfplots}
            \\pgfplotsset{compat=1.18}
            
            \\begin{document}
            \\begin{tikzpicture}
            
            %% Left Axis for Queue Occupancy
            \\begin{axis}[
                width=12cm, height=7cm,
                scale only axis,
                xmin=0, xmax=10,
                ymin=0, ymax=105,
                axis y line*=left,
                xlabel={Time (Seconds)},
                ylabel={Queue Occupancy (\\%)},
                ylabel style={font=\\bfseries},
                legend pos=north west,
                grid=major
            ]
            \\addplot[color=red, thick] coordinates {
                {OCC_COORDS}
            };
            \\addlegendentry{Queue Occupancy}
            \\end{axis}
            
            %% Right Axis for Latency Gradient
            \\begin{axis}[
                width=12cm, height=7cm,
                scale only axis,
                xmin=0, xmax=10,
                ymin=0, ymax=2.5,
                axis y line*=right,
                axis x line=none,
                ylabel={Latency Gradient},
                ylabel style={font=\\bfseries}
            ]
            
            %% Gradient Threshold Line (0.9)
            \\addplot[color=black, dashed, thick] coordinates {(0, 0.9) (10, 0.9)};
            
            \\addplot[color=blue, thick] coordinates {
                {GRAD_COORDS}
            };
            \\end{axis}
            
            \\end{tikzpicture}
            \\end{document}
            """;

        // Safely replace the placeholders without triggering String.format % errors
        String finalLatex = latexTemplate
                .replace("{OCC_COORDS}", occCoords.toString())
                .replace("{GRAD_COORDS}", gradCoords.toString());

        try (FileWriter file = new FileWriter("backpressure-graph.tex")) {
            file.write(finalLatex);
            System.out.println("\\nSuccess! LaTeX chart generated: backpressure-graph.tex");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}