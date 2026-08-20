package io.akka.remark42.domain;

import java.time.Instant;
import java.util.List;

/** One tree node: a comment plus its replies, in reply-timestamp order. SPEC-001 §3 rule 2. */
public record Node(Comment comment, List<Node> replies, Instant lastActive) {}
