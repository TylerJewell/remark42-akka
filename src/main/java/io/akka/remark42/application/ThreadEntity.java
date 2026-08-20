package io.akka.remark42.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.remark42.domain.Comment;
import io.akka.remark42.domain.ThreadEvent;
import io.akka.remark42.domain.ThreadState;
import io.akka.remark42.domain.Tree;
import io.akka.remark42.domain.VoteOutcome;
import java.time.Instant;

/**
 * One thread's comments — SPEC-001 §3. Entity id is {@code siteId + "|" + postUrl}, so a vote
 * and a reply to the same post are never processed concurrently (spec §2).
 *
 * <p>Block enforcement (spec §3 rule 13) is not this entity's job — a caller checks {@link
 * BlockedUserEntity} first, the same layering the source itself uses.
 */
@Component(id = "thread")
public class ThreadEntity extends EventSourcedEntity<ThreadState, ThreadEvent> {

  private final String siteId;
  private final String postUrl;

  public ThreadEntity(EventSourcedEntityContext context) {
    String[] parts = context.entityId().split(":", 2);
    this.siteId = parts.length > 0 ? parts[0] : "";
    this.postUrl = parts.length > 1 ? parts[1] : "";
  }

  @Override
  public ThreadState emptyState() {
    return ThreadState.empty(siteId, postUrl);
  }

  public record AddComment(String id, String parentId, String text, String authorId, String authorName) {}

  public record Vote(String commentId, String voterId, String voterIp, boolean value) {}

  public record SetPin(String commentId, boolean pinned) {}

  public record DeleteComment(String commentId, boolean hard) {}

  public record TreeQuery(String sortType, int limit, String offsetId) {}

  public Effect<Comment> addComment(AddComment command) {
    var event =
        currentState()
            .planAddComment(
                command.id(),
                command.parentId(),
                command.text(),
                command.authorId(),
                command.authorName(),
                Instant.now());
    return effects().persist(event).thenReply(state -> state.comment(command.id()).orElseThrow());
  }

  public Effect<Comment> vote(Vote command) {
    VoteOutcome outcome =
        currentState()
            .planVote(command.commentId(), command.voterId(), command.voterIp(), command.value(), Instant.now());
    return switch (outcome) {
      case VoteOutcome.Rejected r -> effects().error(r.reason());
      case VoteOutcome.Accepted a ->
          effects().persist(a.event()).thenReply(state -> state.comment(command.commentId()).orElseThrow());
    };
  }

  public Effect<Done> setPin(SetPin command) {
    if (currentState().comment(command.commentId()).isEmpty()) {
      return effects().error("comment " + command.commentId() + " not found");
    }
    var event = currentState().planSetPin(command.commentId(), command.pinned());
    return effects().persist(event).thenReply(state -> Done.getInstance());
  }

  public Effect<Done> deleteComment(DeleteComment command) {
    if (currentState().comment(command.commentId()).isEmpty()) {
      return effects().error("comment " + command.commentId() + " not found");
    }
    var event = currentState().planDelete(command.commentId(), command.hard());
    return effects().persist(event).thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<Tree> tree(TreeQuery query) {
    return effects().reply(currentState().buildTree(query.sortType(), query.limit(), query.offsetId()));
  }

  public ReadOnlyEffect<Comment> get(String commentId) {
    return currentState()
        .comment(commentId)
        .map(c -> effects().reply(c))
        .orElseGet(() -> effects().error("comment " + commentId + " not found"));
  }

  @Override
  public ThreadState applyEvent(ThreadEvent event) {
    return currentState().onEvent(event);
  }
}
