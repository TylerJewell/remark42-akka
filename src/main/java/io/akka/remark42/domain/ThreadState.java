package io.akka.remark42.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All comments for one (siteId, postUrl) thread, and every threading/scoring/pin/delete rule
 * that reads them. SPEC-001 §3.
 *
 * <p>The rules live here, not in the entity, so they can be tested without a runtime. {@code
 * comments} is insertion-ordered ({@link LinkedHashMap}) so tree-building output is stable for
 * comments that tie on every sort key, matching the source's stable {@code sort.Slice} input
 * order (question-log #4).
 */
public record ThreadState(String siteId, String postUrl, Map<String, Comment> comments) {

  /** SPEC-001 §4: source config is site-wide; this rebuild fixes it per thread as a constant. */
  public static final int MAX_VOTES = Integer.MAX_VALUE;

  public static final boolean POSITIVE_SCORE_ONLY = false;

  public static final boolean RESTRICT_SAME_IP_VOTES = true;

  /** Zero duration means "restrict forever" — SPEC-001 §3 rule 9, carried from the source. */
  public static final Duration RESTRICT_SAME_IP_DURATION = Duration.ZERO;

  /**
   * Platform-size guard, not a source business rule (out of scope per SPEC-001 §1's
   * MaxCommentSize/markdown-validation exclusion) — every comment lives in the same replicated
   * entity as every other comment on the thread, so an unbounded text field is an unbounded
   * entity, not just an unbounded comment. Truncated rather than rejected, the same trade-off
   * `docs/review-findings.md` records for this port.
   */
  public static final int MAX_TEXT_LENGTH = 20_000;

  public static ThreadState empty(String siteId, String postUrl) {
    return new ThreadState(siteId, postUrl, Map.of());
  }

  public Optional<Comment> comment(String id) {
    return Optional.ofNullable(comments.get(id));
  }

  public ThreadEvent.CommentAdded planAddComment(
      String id, String parentId, String text, String authorId, String authorName, Instant now) {
    String bounded = text != null && text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    return new ThreadEvent.CommentAdded(
        id, parentId == null ? "" : parentId, siteId, postUrl, bounded, authorId, authorName, now);
  }

  /** SPEC-001 §3 rule 6 — checks run in this exact order, each an independent early exit. */
  public VoteOutcome planVote(String commentId, String voterId, String voterIp, boolean value, Instant now) {
    Comment c = comments.get(commentId);
    if (c == null) {
      return new VoteOutcome.Rejected("comment " + commentId + " not found");
    }

    // rule 6(a): self-vote, except the source's own "dev" test fixture (dropped — see spec §4)
    if (c.authorId().equals(voterId)) {
      return new VoteOutcome.Rejected("user " + voterId + " can not vote for their own comment " + commentId);
    }

    Boolean prior = c.votes().get(voterId);
    if (prior != null && prior == value) {
      return new VoteOutcome.Rejected("user " + voterId + " already voted for " + commentId);
    }

    if (voterIp != null && !voterIp.isEmpty() && RESTRICT_SAME_IP_VOTES) {
      Comment.VoterIp ipRecord = c.voterIps().get(voterIp);
      if (ipRecord != null && ipRecord.value() == value) {
        boolean stillActive =
            RESTRICT_SAME_IP_DURATION.isZero() || ipRecord.timestamp().plus(RESTRICT_SAME_IP_DURATION).isAfter(now);
        if (stillActive) {
          return new VoteOutcome.Rejected("the same ip " + voterIp + " already voted for " + commentId);
        }
      }
    }

    if (c.votes().size() >= MAX_VOTES) {
      return new VoteOutcome.Rejected("maximum number of votes exceeded for comment " + commentId);
    }

    if (POSITIVE_SCORE_ONLY && c.score() <= 0 && !value) {
      return new VoteOutcome.Rejected("minimal score reached for comment " + commentId);
    }

    int newScore = c.score() + (value ? 1 : -1);
    int[] upsDowns = upsAndDownsAfter(c, voterId, value);
    double newControversy = controversy(upsDowns[0], upsDowns[1]);

    return new VoteOutcome.Accepted(
        new ThreadEvent.CommentVoted(commentId, voterId, voterIp, value, newScore, newControversy, now));
  }

  /** Recomputes ups/downs as they will read once this vote (with its opposite-direction reset
   * per rule 7) has been applied, without mutating state — used only to price controversy. */
  private int[] upsAndDownsAfter(Comment c, String voterId, boolean value) {
    Map<String, Boolean> votes = new LinkedHashMap<>(c.votes());
    votes.put(voterId, value);
    int ups = 0;
    int downs = 0;
    for (boolean v : votes.values()) {
      if (v) ups++;
      else downs++;
    }
    return new int[] {ups, downs};
  }

  /** SPEC-001 §3 rule 8, verified against the real source in probes/probe_02_controversy.go. */
  public static double controversy(int ups, int downs) {
    if (downs <= 0 || ups <= 0) {
      return 0;
    }
    int magnitude = ups + downs;
    double balance = ups <= downs ? (double) ups / downs : (double) downs / ups;
    return Math.pow(magnitude, balance);
  }

  public ThreadEvent.CommentPinChanged planSetPin(String commentId, boolean pinned) {
    return new ThreadEvent.CommentPinChanged(commentId, pinned);
  }

  public ThreadEvent.CommentDeleted planDelete(String commentId, boolean hard) {
    return new ThreadEvent.CommentDeleted(commentId, hard);
  }

  public ThreadState onEvent(ThreadEvent event) {
    return switch (event) {
      case ThreadEvent.CommentAdded e -> onCommentAdded(e);
      case ThreadEvent.CommentVoted e -> onCommentVoted(e);
      case ThreadEvent.CommentPinChanged e -> onPinChanged(e);
      case ThreadEvent.CommentDeleted e -> onDeleted(e);
    };
  }

  private ThreadState onCommentAdded(ThreadEvent.CommentAdded e) {
    Comment c =
        new Comment(
            e.id(),
            e.parentId(),
            e.siteId(),
            e.postUrl(),
            e.text(),
            e.authorId(),
            e.authorName(),
            0,
            0,
            e.timestamp(),
            false,
            false,
            Map.of(),
            Map.of());
    return withComment(c);
  }

  private ThreadState onCommentVoted(ThreadEvent.CommentVoted e) {
    Comment c = comments.get(e.commentId());
    Map<String, Boolean> votes = new LinkedHashMap<>(c.votes());
    Map<String, Comment.VoterIp> ips = new LinkedHashMap<>(c.voterIps());

    Boolean prior = votes.get(e.voterId());
    if (prior != null && prior != e.value()) {
      // rule 7: opposite-direction correction forgets the prior vote and its IP record
      votes.remove(e.voterId());
      if (e.voterIp() != null) {
        ips.remove(e.voterIp());
      }
    }
    votes.put(e.voterId(), e.value());
    if (e.voterIp() != null && !e.voterIp().isEmpty()) {
      ips.put(e.voterIp(), new Comment.VoterIp(e.value(), e.timestamp()));
    }

    Comment updated =
        new Comment(
            c.id(),
            c.parentId(),
            c.siteId(),
            c.postUrl(),
            c.text(),
            c.authorId(),
            c.authorName(),
            e.newScore(),
            e.newControversy(),
            c.timestamp(),
            c.pinned(),
            c.deleted(),
            votes,
            ips);
    return withComment(updated);
  }

  private ThreadState onPinChanged(ThreadEvent.CommentPinChanged e) {
    Comment c = comments.get(e.commentId());
    Comment updated =
        new Comment(
            c.id(), c.parentId(), c.siteId(), c.postUrl(), c.text(), c.authorId(), c.authorName(),
            c.score(), c.controversy(), c.timestamp(), e.pinned(), c.deleted(), c.votes(), c.voterIps());
    return withComment(updated);
  }

  private ThreadState onDeleted(ThreadEvent.CommentDeleted e) {
    Comment c = comments.get(e.commentId());
    // SPEC-001 §3 rule 11: both modes clear text/score/votes/pin; hard also scrubs identity
    Comment updated =
        new Comment(
            c.id(),
            c.parentId(),
            c.siteId(),
            c.postUrl(),
            "",
            e.hard() ? "deleted" : c.authorId(),
            e.hard() ? "deleted" : c.authorName(),
            0,
            0,
            c.timestamp(),
            false,
            true,
            Map.of(),
            Map.of());
    return withComment(updated);
  }

  private ThreadState withComment(Comment c) {
    Map<String, Comment> updated = new LinkedHashMap<>(comments);
    updated.put(c.id(), c);
    return new ThreadState(siteId, postUrl, updated);
  }

  // ---- tree building — SPEC-001 §3 rules 1-5, a direct port of tree.go ----

  private static final class RecurData {
    Instant tsModified;
    Instant tsCreated;
    boolean visible;
  }

  public Tree buildTree(String sortType, int limit, String offsetId) {
    if (comments.isEmpty()) {
      return new Tree(List.of(), 0, "");
    }

    List<Comment> all = new ArrayList<>(comments.values());
    List<Comment> topLevel = all.stream().filter(Comment::isTopLevel).toList();

    List<Node> nodes = new ArrayList<>();
    for (Comment root : topLevel) {
      RecurData rd = new RecurData();
      Node node = buildNode(all, root, rd);
      // rule 3: skip deleted top-level with no replies or no visible subtree
      if (root.deleted() && (node.replies().isEmpty() || !rd.visible)) {
        continue;
      }
      nodes.add(node);
    }

    nodes = sortNodes(nodes, sortType);
    return applyLimit(nodes, limit, offsetId);
  }

  private Node buildNode(List<Comment> all, Comment comment, RecurData rd) {
    if (rd.tsModified == null || rd.tsCreated == null) {
      rd.tsModified = comment.timestamp();
      rd.tsCreated = comment.timestamp();
    }

    List<Comment> children =
        all.stream().filter(c -> comment.id().equals(c.parentId())).toList();

    List<Node> replies = new ArrayList<>();
    for (Comment rc : children) {
      if (rc.timestamp() != null && rc.timestamp().isAfter(rd.tsModified) && !rc.deleted()) {
        rd.tsModified = rc.timestamp();
      }
      if (rc.timestamp() != null && rc.timestamp().isBefore(rd.tsCreated) && !rc.deleted()) {
        rd.tsCreated = rc.timestamp();
      }
      if (!rc.deleted()) {
        rd.visible = true;
      }
      Node child = buildNode(all, rc, rd);
      // rule 3, applied recursively: drop an all-deleted subtree once nothing became visible
      if (!rd.visible || (child.replies().isEmpty() && rc.deleted())) {
        continue;
      }
      replies.add(child);
    }

    // rule 2: replies always sorted by timestamp ascending
    replies = new ArrayList<>(replies);
    replies.sort(Comparator.comparing(n -> n.comment().timestamp()));

    return new Node(comment, replies, rd.tsModified);
  }

  private List<Node> sortNodes(List<Node> nodes, String sortType) {
    List<Node> sorted = new ArrayList<>(nodes);
    boolean descending = sortType != null && sortType.startsWith("-");
    String key = sortType == null ? "time" : sortType.replaceFirst("^[+-]", "");

    Comparator<Node> cmp =
        switch (key) {
          case "time" -> Comparator.comparing(n -> n.comment().timestamp());
          case "active" -> Comparator.comparing(Node::lastActive);
          case "score" ->
              Comparator.<Node>comparingInt(n -> n.comment().score())
                  .thenComparing(n -> n.comment().timestamp());
          case "controversy" ->
              Comparator.<Node>comparingDouble(n -> n.comment().controversy())
                  .thenComparing(n -> n.comment().timestamp());
          default -> Comparator.comparing(n -> n.comment().timestamp());
        };
    if (descending && (key.equals("score") || key.equals("controversy"))) {
      // rule 4: score/controversy ties still break ascending by time even when reversed
      Comparator<Node> tieBreak = Comparator.comparing(n -> n.comment().timestamp());
      Comparator<Node> primary =
          key.equals("score")
              ? Comparator.<Node>comparingInt(n -> n.comment().score()).reversed()
              : Comparator.<Node>comparingDouble(n -> n.comment().controversy()).reversed();
      cmp =
          (a, b) -> {
            int byPrimary =
                key.equals("score")
                    ? Integer.compare(b.comment().score(), a.comment().score())
                    : Double.compare(b.comment().controversy(), a.comment().controversy());
            return byPrimary != 0 ? byPrimary : tieBreak.compare(a, b);
          };
    } else if (descending) {
      cmp = cmp.reversed();
    }
    sorted.sort(cmp);
    return sorted;
  }

  private Tree applyLimit(List<Node> nodes, int limit, String offsetId) {
    if ((offsetId == null || offsetId.isEmpty()) && limit <= 0) {
      return new Tree(nodes, 0, "");
    }

    int start = 0;
    if (offsetId != null && !offsetId.isEmpty()) {
      for (int i = 0; i < nodes.size(); i++) {
        if (nodes.get(i).comment().id().equals(offsetId)) {
          start = i + 1;
          break;
        }
      }
    }

    if (start == nodes.size()) {
      return new Tree(List.of(), 0, "");
    }

    List<Node> remaining = nodes.subList(start, nodes.size());
    if (limit <= 0) {
      return new Tree(new ArrayList<>(remaining), 0, "");
    }

    List<Node> limited = new ArrayList<>();
    int count = 0;
    int countLeft = 0;
    for (Node node : remaining) {
      int subtreeSize = countReplies(node) + 1;
      if (count >= limit) {
        countLeft += subtreeSize;
        continue;
      }
      if (count + subtreeSize > limit && !limited.isEmpty()) {
        countLeft += subtreeSize;
        count = limit;
        continue;
      }
      limited.add(node);
      count += subtreeSize;
    }

    String lastComment = limited.isEmpty() ? "" : limited.get(limited.size() - 1).comment().id();
    return new Tree(limited, countLeft, lastComment);
  }

  private static int countReplies(Node node) {
    int count = 0;
    for (Node reply : node.replies()) {
      count++;
      count += countReplies(reply);
    }
    return count;
  }
}
