package io.akka.remark42.api;

import akka.Done;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.remark42.application.BlockedUserEntity;
import io.akka.remark42.application.ThreadEntity;
import io.akka.remark42.domain.Comment;
import io.akka.remark42.domain.Tree;
import java.time.Duration;
import java.util.UUID;

/**
 * The surface for one site's threads: post a comment, vote, pin, delete, block a user, and read
 * the tree. SPEC-001 §3 rule 13 — a blocked user's comment or vote is rejected here, before it
 * reaches {@link ThreadEntity}, the same layering the source uses.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/sites/{siteId}")
public class ThreadEndpoint {

  private final ComponentClient componentClient;

  public ThreadEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record CommentRequest(String parentId, String text, String authorId, String authorName) {}

  public record VoteRequest(String voterId, String voterIp, boolean value) {}

  public record PinRequest(boolean pinned) {}

  public record DeleteRequest(boolean hard) {}

  public record BlockRequest(long ttlSeconds) {}

  public record TreeRequest(String sort, int limit, String offsetId) {}

  @Post("/posts/{postUrl}/comments")
  public Comment addComment(String siteId, String postUrl, CommentRequest request) {
    requireNotBlocked(siteId, request.authorId());
    return thread(siteId, postUrl)
        .method(ThreadEntity::addComment)
        .invoke(
            new ThreadEntity.AddComment(
                UUID.randomUUID().toString(),
                request.parentId(),
                request.text(),
                request.authorId(),
                request.authorName()));
  }

  @Post("/posts/{postUrl}/comments/{commentId}/vote")
  public Comment vote(String siteId, String postUrl, String commentId, VoteRequest request) {
    requireNotBlocked(siteId, request.voterId());
    return thread(siteId, postUrl)
        .method(ThreadEntity::vote)
        .invoke(new ThreadEntity.Vote(commentId, request.voterId(), request.voterIp(), request.value()));
  }

  @Post("/posts/{postUrl}/comments/{commentId}/pin")
  public Done setPin(String siteId, String postUrl, String commentId, PinRequest request) {
    return thread(siteId, postUrl)
        .method(ThreadEntity::setPin)
        .invoke(new ThreadEntity.SetPin(commentId, request.pinned()));
  }

  @Post("/posts/{postUrl}/comments/{commentId}/delete")
  public Done delete(String siteId, String postUrl, String commentId, DeleteRequest request) {
    return thread(siteId, postUrl)
        .method(ThreadEntity::deleteComment)
        .invoke(new ThreadEntity.DeleteComment(commentId, request.hard()));
  }

  @Post("/posts/{postUrl}/tree")
  public Tree tree(String siteId, String postUrl, TreeRequest request) {
    return thread(siteId, postUrl)
        .method(ThreadEntity::tree)
        .invoke(new ThreadEntity.TreeQuery(request.sort(), request.limit(), request.offsetId()));
  }

  @Post("/users/{userId}/block")
  public Done block(String siteId, String userId, BlockRequest request) {
    Duration ttl = request.ttlSeconds() > 0 ? Duration.ofSeconds(request.ttlSeconds()) : Duration.ZERO;
    return blockedUser(siteId, userId).method(BlockedUserEntity::block).invoke(new BlockedUserEntity.Block(ttl));
  }

  @Post("/users/{userId}/unblock")
  public Done unblock(String siteId, String userId) {
    return blockedUser(siteId, userId).method(BlockedUserEntity::unblock).invoke();
  }

  @Get("/users/{userId}/blocked")
  public boolean isBlocked(String siteId, String userId) {
    return blockedUser(siteId, userId).method(BlockedUserEntity::isBlocked).invoke();
  }

  private void requireNotBlocked(String siteId, String userId) {
    if (blockedUser(siteId, userId).method(BlockedUserEntity::isBlocked).invoke()) {
      throw HttpException.forbidden("user " + userId + " is blocked on site " + siteId);
    }
  }

  private akka.javasdk.client.EventSourcedEntityClient thread(String siteId, String postUrl) {
    return componentClient.forEventSourcedEntity(siteId + ":" + postUrl);
  }

  private akka.javasdk.client.KeyValueEntityClient blockedUser(String siteId, String userId) {
    return componentClient.forKeyValueEntity(siteId + ":" + userId);
  }
}
