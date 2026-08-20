package io.akka.remark42.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Duration;
import java.time.Instant;

/**
 * One (siteId, userId) block record. SPEC-001 §2, §3 rule 12.
 *
 * <p>Entity id is {@code siteId + "|" + userId}. Expiry is read at query time rather than
 * enforced by a timer: "blocked" is defined as "a record exists and has not expired," which is a
 * pure function of stored state and the clock, not an event that needs to fire (question-log
 * #12). This mirrors the source's own {@code IsBlocked} being a read, not a scheduled unblock.
 */
@Component(id = "blocked-user")
public class BlockedUserEntity extends KeyValueEntity<BlockedUserEntity.State> {

  /** {@code expiresAt} of {@link Optional#empty} (encoded here as {@code null}) means permanent. */
  public record State(boolean blocked, Instant expiresAt) {}

  public record Block(Duration ttl) {}

  @Override
  public State emptyState() {
    return new State(false, null);
  }

  public Effect<Done> block(Block command) {
    Instant expiresAt =
        (command.ttl() == null || command.ttl().isZero()) ? null : Instant.now().plus(command.ttl());
    return effects().updateState(new State(true, expiresAt)).thenReply(Done.getInstance());
  }

  public Effect<Done> unblock() {
    return effects().updateState(new State(false, null)).thenReply(Done.getInstance());
  }

  public Effect<Boolean> isBlocked() {
    State s = currentState();
    boolean blocked = s.blocked() && (s.expiresAt() == null || s.expiresAt().isAfter(Instant.now()));
    return effects().reply(blocked);
  }
}
