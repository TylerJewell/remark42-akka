package io.akka.remark42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SPEC-001 §5 conformance for the threading/scoring/moderation rules in §3. */
class ThreadStateTest {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  private ThreadState withComment(ThreadState state, String id, String parentId, Instant ts) {
    var event = state.planAddComment(id, parentId, "text", "author-" + id, "Author " + id, ts);
    return state.onEvent(event);
  }

  // rule 3: deleted top-level with a live reply is kept; with no live reply, dropped
  @Test
  void deletedTopLevelWithLiveReplyIsKept() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    state = withComment(state, "c1r1", "c1", BASE.plusSeconds(10));
    state = state.onEvent(state.planDelete("c1", false));

    var tree = state.buildTree("time", 0, "");
    assertThat(tree.nodes()).hasSize(1);
    assertThat(tree.nodes().get(0).comment().id()).isEqualTo("c1");
    assertThat(tree.nodes().get(0).comment().deleted()).isTrue();
  }

  @Test
  void deletedTopLevelWithNoLiveReplyIsDropped() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    state = state.onEvent(state.planDelete("c1", false));

    var tree = state.buildTree("time", 0, "");
    assertThat(tree.nodes()).isEmpty();
  }

  // rule 4: four sorts, tie-break on timestamp for score/controversy
  @Test
  void sortsByScoreDescendingWithTimestampTieBreak() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "a", "", BASE);
    state = withComment(state, "b", "", BASE.plusSeconds(1));
    state = state.onEvent(voteEvent(state, "a", "u1", true, 1, BASE.plusSeconds(5)));
    state = state.onEvent(voteEvent(state, "b", "u1", true, 1, BASE.plusSeconds(5)));

    var tree = state.buildTree("-score", 0, "");
    // equal score -1/-score ties break ascending by timestamp: "a" (earlier) before "b"
    assertThat(tree.nodes()).extracting(n -> n.comment().id()).containsExactly("a", "b");
  }

  private ThreadEvent.CommentVoted voteEvent(ThreadState state, String id, String voter, boolean value, int score, Instant ts) {
    return new ThreadEvent.CommentVoted(id, voter, "ip-" + voter, value, score, ThreadState.controversy(1, 0), ts);
  }

  // rule 5: pagination counts a subtree as one unit against the limit
  @Test
  void limitCountsSubtreeAsOneUnit() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    state = withComment(state, "c2", "", BASE.plusSeconds(10));
    state = withComment(state, "c2r1", "c2", BASE.plusSeconds(20));

    var tree = state.buildTree("time", 1, "");
    assertThat(tree.nodes()).extracting(n -> n.comment().id()).containsExactly("c1");
    assertThat(tree.countLeft()).isEqualTo(2);
    assertThat(tree.lastComment()).isEqualTo("c1");
  }

  // rule 6: five independent vote gates, checked in order
  @Test
  void rejectsSelfVote() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var outcome = state.planVote("c1", "author-c1", "ip", true, BASE.plusSeconds(1));
    assertThat(outcome).isInstanceOf(VoteOutcome.Rejected.class);
  }

  @Test
  void rejectsSecondVoteInSameDirection() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var accepted = (VoteOutcome.Accepted) state.planVote("c1", "voter", "ip-1", true, BASE.plusSeconds(1));
    state = state.onEvent(accepted.event());

    var outcome = state.planVote("c1", "voter", "ip-1", true, BASE.plusSeconds(2));
    assertThat(outcome).isInstanceOf(VoteOutcome.Rejected.class);
  }

  @Test
  void rejectsSameIpVotingSameDirectionAgain() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var accepted = (VoteOutcome.Accepted) state.planVote("c1", "voter-1", "shared-ip", true, BASE.plusSeconds(1));
    state = state.onEvent(accepted.event());

    var outcome = state.planVote("c1", "voter-2", "shared-ip", true, BASE.plusSeconds(2));
    assertThat(outcome).isInstanceOf(VoteOutcome.Rejected.class);
  }

  // rule 7: opposite-direction vote is accepted and swings the score by one, not two at once
  @Test
  void oppositeDirectionVoteIsAcceptedAndSwingsScore() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var up = (VoteOutcome.Accepted) state.planVote("c1", "voter", "ip-1", true, BASE.plusSeconds(1));
    state = state.onEvent(up.event());
    assertThat(state.comment("c1").orElseThrow().score()).isEqualTo(1);

    var down = state.planVote("c1", "voter", "ip-2", false, BASE.plusSeconds(2));
    assertThat(down).isInstanceOf(VoteOutcome.Accepted.class);
    state = state.onEvent(((VoteOutcome.Accepted) down).event());
    // source applies only +-1 per vote call, even on a direction flip — the prior +1 is never
    // subtracted back out, so the net score after up-then-down is 0, not -1 (question-log #8)
    assertThat(state.comment("c1").orElseThrow().score()).isZero();
    assertThat(state.comment("c1").orElseThrow().votes()).containsEntry("voter", false);
  }

  // rule 8: controversy formula, verified against the real source in probes/probe_02_controversy.go
  @Test
  void controversyMatchesSourceValues() {
    assertThat(ThreadState.controversy(5, 5)).isEqualTo(10.0);
    assertThat(ThreadState.controversy(5, 0)).isEqualTo(0.0);
    assertThat(ThreadState.controversy(0, 5)).isEqualTo(0.0);
    assertThat(ThreadState.controversy(10, 2)).isEqualTo(1.6437518295172258);
    assertThat(ThreadState.controversy(2, 10)).isEqualTo(1.6437518295172258);
    assertThat(ThreadState.controversy(0, 0)).isEqualTo(0.0);
    assertThat(ThreadState.controversy(1, 1)).isEqualTo(2.0);
  }

  // rule 10, 11: pin leaves score untouched; soft delete clears fields but keeps identity;
  // hard delete also scrubs identity
  @Test
  void pinDoesNotAffectScoreOrVotes() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var accepted = (VoteOutcome.Accepted) state.planVote("c1", "voter", "ip", true, BASE.plusSeconds(1));
    state = state.onEvent(accepted.event());
    state = state.onEvent(state.planSetPin("c1", true));

    var c = state.comment("c1").orElseThrow();
    assertThat(c.pinned()).isTrue();
    assertThat(c.score()).isEqualTo(1);
    assertThat(c.votes()).hasSize(1);
  }

  @Test
  void softDeleteClearsScoreAndVotesButKeepsAuthor() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var accepted = (VoteOutcome.Accepted) state.planVote("c1", "voter", "ip", true, BASE.plusSeconds(1));
    state = state.onEvent(accepted.event());
    state = state.onEvent(state.planDelete("c1", false));

    var c = state.comment("c1").orElseThrow();
    assertThat(c.deleted()).isTrue();
    assertThat(c.score()).isZero();
    assertThat(c.votes()).isEmpty();
    assertThat(c.authorId()).isEqualTo("author-c1");
  }

  @Test
  void hardDeleteScrubsAuthorIdentity() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    state = state.onEvent(state.planDelete("c1", true));

    var c = state.comment("c1").orElseThrow();
    assertThat(c.deleted()).isTrue();
    assertThat(c.authorId()).isEqualTo("deleted");
    assertThat(c.authorName()).isEqualTo("deleted");
  }

  // platform-size guard added by /akka:review (docs/review-findings.md), not a source rule
  @Test
  void overlongCommentTextIsTruncatedRatherThanRejected() {
    var state = ThreadState.empty("site", "/post");
    String tooLong = "x".repeat(ThreadState.MAX_TEXT_LENGTH + 500);
    var event = state.planAddComment("c1", "", tooLong, "author-1", "Author One", BASE);
    state = state.onEvent(event);
    assertThat(state.comment("c1").orElseThrow().text()).hasSize(ThreadState.MAX_TEXT_LENGTH);
  }

  // rule 9: zero-duration same-IP restriction never expires
  @Test
  void zeroDurationSameIpRestrictionNeverExpires() {
    var state = ThreadState.empty("site", "/post");
    state = withComment(state, "c1", "", BASE);
    var accepted = (VoteOutcome.Accepted) state.planVote("c1", "voter-1", "shared-ip", true, BASE);
    state = state.onEvent(accepted.event());

    var muchLater = state.planVote("c1", "voter-2", "shared-ip", true, BASE.plus(Duration.ofDays(365)));
    assertThat(muchLater).isInstanceOf(VoteOutcome.Rejected.class);
  }
}
