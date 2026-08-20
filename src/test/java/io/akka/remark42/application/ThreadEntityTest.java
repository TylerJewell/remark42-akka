package io.akka.remark42.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.remark42.domain.ThreadEvent;
import io.akka.remark42.domain.ThreadState;
import org.junit.jupiter.api.Test;

/** SPEC-001 §5 — entity-level: that a rejected vote does not persist, and pin/get roundtrip. */
class ThreadEntityTest {

  private EventSourcedTestKit<ThreadState, ThreadEvent, ThreadEntity> thread() {
    return EventSourcedTestKit.of("site-1:/post-1", ThreadEntity::new);
  }

  @Test
  void addCommentThenVoteThenGetReflectsScore() {
    var kit = thread();
    var comment =
        kit.method(ThreadEntity::addComment)
            .invoke(new ThreadEntity.AddComment("c1", "", "hello", "author-1", "Author One"));
    assertThat(comment.getReply().score()).isZero();

    var voted =
        kit.method(ThreadEntity::vote).invoke(new ThreadEntity.Vote("c1", "voter-1", "ip-1", true));
    assertThat(voted.getReply().score()).isEqualTo(1);

    var fetched = kit.method(ThreadEntity::get).invoke("c1");
    assertThat(fetched.getReply().score()).isEqualTo(1);
  }

  @Test
  void selfVoteIsRejectedAndDoesNotPersist() {
    var kit = thread();
    kit.method(ThreadEntity::addComment)
        .invoke(new ThreadEntity.AddComment("c1", "", "hello", "author-1", "Author One"));

    var result = kit.method(ThreadEntity::vote).invoke(new ThreadEntity.Vote("c1", "author-1", "ip-1", true));
    assertThat(result.isError()).isTrue();

    var fetched = kit.method(ThreadEntity::get).invoke("c1");
    assertThat(fetched.getReply().score()).isZero();
  }

  @Test
  void pinChangesFlagWithoutTouchingScore() {
    var kit = thread();
    kit.method(ThreadEntity::addComment)
        .invoke(new ThreadEntity.AddComment("c1", "", "hello", "author-1", "Author One"));
    kit.method(ThreadEntity::vote).invoke(new ThreadEntity.Vote("c1", "voter-1", "ip-1", true));
    kit.method(ThreadEntity::setPin).invoke(new ThreadEntity.SetPin("c1", true));

    var fetched = kit.method(ThreadEntity::get).invoke("c1").getReply();
    assertThat(fetched.pinned()).isTrue();
    assertThat(fetched.score()).isEqualTo(1);
  }

  @Test
  void treeReflectsAddedComments() {
    var kit = thread();
    kit.method(ThreadEntity::addComment)
        .invoke(new ThreadEntity.AddComment("c1", "", "hello", "author-1", "Author One"));
    kit.method(ThreadEntity::addComment)
        .invoke(new ThreadEntity.AddComment("c2", "", "world", "author-2", "Author Two"));

    var tree = kit.method(ThreadEntity::tree).invoke(new ThreadEntity.TreeQuery("time", 0, ""));
    assertThat(tree.getReply().nodes()).hasSize(2);
  }
}
