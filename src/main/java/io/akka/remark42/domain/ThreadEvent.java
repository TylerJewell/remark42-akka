package io.akka.remark42.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/** Everything that can change one thread's (site + post url) comment state. SPEC-001 §3. */
public sealed interface ThreadEvent {

  @TypeName("comment-added")
  record CommentAdded(
      String id,
      String parentId,
      String siteId,
      String postUrl,
      String text,
      String authorId,
      String authorName,
      Instant timestamp)
      implements ThreadEvent {}

  @TypeName("comment-voted")
  record CommentVoted(
      String commentId,
      String voterId,
      String voterIp,
      boolean value,
      int newScore,
      double newControversy,
      Instant timestamp)
      implements ThreadEvent {}

  @TypeName("comment-pin-changed")
  record CommentPinChanged(String commentId, boolean pinned) implements ThreadEvent {}

  @TypeName("comment-deleted")
  record CommentDeleted(String commentId, boolean hard) implements ThreadEvent {}
}
