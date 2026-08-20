package io.akka.remark42.domain;

import java.time.Instant;
import java.util.Map;

/**
 * One comment. SPEC-001 §2.
 *
 * <p>{@code votes} and {@code voterIps} are separate ledgers because they answer different
 * questions: {@code votes} is keyed by user id and gates rule 6(b); {@code voterIps} is keyed by
 * the caller-supplied IP token and gates rule 6(c) / rule 9 independently of who is signed in.
 */
public record Comment(
    String id,
    String parentId,
    String siteId,
    String postUrl,
    String text,
    String authorId,
    String authorName,
    int score,
    double controversy,
    Instant timestamp,
    boolean pinned,
    boolean deleted,
    Map<String, Boolean> votes,
    Map<String, VoterIp> voterIps) {

  public record VoterIp(boolean value, Instant timestamp) {}

  public boolean isTopLevel() {
    return parentId == null || parentId.isEmpty();
  }
}
