package io.akka.remark42.domain;

/** Result of validating a vote against SPEC-001 §3 rule 6, before any event is persisted. */
public sealed interface VoteOutcome {

  record Accepted(ThreadEvent.CommentVoted event) implements VoteOutcome {}

  record Rejected(String reason) implements VoteOutcome {}
}
