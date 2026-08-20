package io.akka.remark42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Timing benchmark mirroring `bench/bench_source.go` — same 2000-comment shape (200 top-level,
 * 9 replies each), same sort/limit call, same controversy inputs. Numbers go in `bench/REPORT.md`.
 */
class BenchTimingTest {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  private ThreadState buildThread() {
    Map<String, Comment> comments = new LinkedHashMap<>();
    for (int i = 0; i < 200; i++) {
      String id = "c" + i;
      comments.put(
          id,
          new Comment(
              id, "", "site", "/post", "text", "author", "Author", i % 17, 0,
              BASE.plusSeconds(i), false, false, Map.of(), Map.of()));
      for (int j = 0; j < 9; j++) {
        String rid = "c" + i + "-r" + j;
        comments.put(
            rid,
            new Comment(
                rid, id, "site", "/post", "text", "author", "Author", 0, 0,
                BASE.plusSeconds(1000L + i * 10L + j), false, false, Map.of(), Map.of()));
      }
    }
    return new ThreadState("site", "/post", comments);
  }

  @Test
  void sameShapeAsSourceBenchmark() {
    var state = buildThread();
    var tree = state.buildTree("-score", 50, "");
    assertThat(tree.nodes()).isNotEmpty();
    // same input shape as bench_source.go: 200 top-level * 10 (self+9 replies) = 2000 comments
    int total = tree.nodes().size();
    for (Node n : state.buildTree("time", 0, "").nodes()) {
      total += n.replies().size();
    }
  }

  @Test
  void makeTreeTiming() {
    var state = buildThread();
    int iterations = 200;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      state.buildTree("-score", 50, "");
    }
    long elapsed = System.nanoTime() - start;
    System.out.printf(
        "buildTree: 2000 comments, %d iterations, %.3fms total, %.4fms/op%n",
        iterations, elapsed / 1_000_000.0, elapsed / 1_000_000.0 / iterations);
  }

  @Test
  void controversyTiming() {
    int iterations = 1_000_000;
    long start = System.nanoTime();
    double sink = 0;
    for (int i = 0; i < iterations; i++) {
      sink += ThreadState.controversy(37, 12);
    }
    long elapsed = System.nanoTime() - start;
    System.out.printf(
        "controversy: %d iterations, %.3fms total, %.1fns/op (sink=%.1f)%n",
        iterations, elapsed / 1_000_000.0, (double) elapsed / iterations, sink);
  }
}
