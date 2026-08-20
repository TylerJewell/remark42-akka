# remark42-akka

Decides comment tree shape, vote scoring, and moderation state for a comments widget.

A port of [umputun/remark42](https://github.com/umputun/remark42) onto **Akka**, built with **Akka Specify**.

---

## Where it came from

remark42 is a self-hosted comment engine — the thing a blog embeds instead of a
third-party comments widget. It was ported to derive a specification format precise
enough to regenerate a system on a different stack — the port is the vehicle, the
specification is the deliverable.

The specifications this port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `remark42-port/`.

---

## remark42 → this port

📉 324 Go lines (behavioural slice) → **577 Java lines**<br>
📁 3 Go files → **9 Java files**<br>
🖥️ 1 process (Go binary + BoltDB) → **2 entity types, 1 endpoint**<br>
⚡ 42.51 ms/op (2000-comment tree build) → **11.44 ms/op**<br>
🎯 controversy formula (7 test values) → **7 of 7 identical**

Full method and the numbers that did not make this list: [`bench/REPORT.md`](../remark42-port/bench/REPORT.md).

---

## What it took to build

⏱️ **0.3 hours** from the first command to the published repository, **0.3** of them active<br>
💬 **233** exchanges with the model<br>
✍️ **124,337** tokens written by the model, **33,650,987** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **23** tests

```bash
python toolkit/tokens.py --port remark42    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in [`port-log/`](../port-log).

---

## What it does

From the specification:

- **A deleted comment with a live reply stays visible.** Deleting a comment never
  breaks the thread underneath it — it only hides the deleted comment's own text.
- **A vote can only ever move a comment's score by one point at a time.** Even
  changing your mind from an upvote to a downvote costs one point, not two, because
  that is what the original system does — reproduced here rather than corrected.
- **A page of comments never splits a thread in half.** A reply and its parent are
  always returned together, even if that means one page holds a few more comments
  than the requested limit.
- **Being blocked on a site is independent of any one post.** A blocked person
  cannot comment or vote anywhere on that site, checked before their request ever
  reaches a specific comment thread.

---

## Design decisions

**One thread per post, one entity per thread.** A vote and a reply to the same post
would otherwise race each other over who wins. Putting every comment on a post in a
single Akka entity means the platform itself serializes them, for free.

**Block state lives in its own entity, not inside the thread.** A block should apply
to every post on a site, not just the one somebody happened to be reading when they
were blocked. Keeping it separate means a moderator's block takes effect everywhere
at once, without touching every thread that person ever commented on.

**An overlong comment is trimmed, not rejected.** The original system never bounds
comment length. This port trims one because every comment on a thread shares the
same storage slot, and one very long comment would make that slot bigger for
everyone reading the thread, not just its author.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/remark42-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9023.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9023**.

---

## Model providers

This port calls no model provider — the slice is comment threading, scoring, and
moderation state, none of which involves an AI agent.

---

## Configuration

Everything that is not a model provider.

| Variable | Default | Notes |
|---|---|---|
| none | — | this port has no environment-driven configuration; the vote/size limits in `ThreadState.java` are fixed constants, listed below |

---

## Where it differs from remark42

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes.

- **A post's identifier cannot contain a `/`.** remark42 uses the full post URL,
  slashes and all, as the identifier. This port builds an Akka entity id from the
  site id and post id joined by `:`, and a caller-supplied `/` inside the post id
  broke entity routing outright when tried (`docs/question-log.md` and
  `port-log/sessions/2026-08-20-remark42.md`). A caller supplies a post id with no
  `/` in it — typically the last path segment or a hash of the full URL.
- **A blocked person's comment or vote is rejected at the HTTP layer, not inside the
  entity that holds the comment.** remark42 does the same — the check lives in its
  REST middleware, not inside `DataStore.Vote`/`Create` — and this port keeps that
  separation on purpose, so a thread entity never has to know about site-wide
  moderation state.
- **Comment text longer than 20,000 characters is trimmed, not rejected.** remark42
  has a similar limit but rejects the whole comment instead; that whole validation
  path (markdown parsing, minimum length, link-scheme checking) is out of this
  port's scope, so this port applies only a size guard, silently, rather than
  reimplementing remark42's rejection message.
- **Voter IP addresses are stored as given, not hashed with a per-site secret.**
  remark42 hashes the IP before storing it so the raw address is never kept; the
  secret-management subsystem that makes that possible is out of this port's scope,
  so the IP token is stored as the caller supplies it. A caller who wants the same
  privacy property should hash the IP before calling this port.
- **Moderation endpoints (pin, delete, block) have no separate authorization check.**
  remark42 puts these behind admin-only auth middleware; authentication itself is
  out of this port's scope (SPEC-001 §1), so every endpoint here is reachable by
  anyone who can reach the service. A deployment that needs this port's moderation
  endpoints protected must put its own authorization in front of them.
- **A vote that switches direction lands the score at 0, not -1, after an up-then-down
  sequence by one voter.** This is not a difference — it is what remark42 itself
  computes (`service.go:408-412` applies a plain +1/-1 per call, never subtracting
  a prior vote's contribution back out) — listed here because it reads like a bug
  and a reader deserves to know it was checked, not missed.

---

## Licence

remark42 is MIT, © 2021 Umputun. This port reimplements the behaviour described in
`specs/SPEC-001-remark42.md` without copying source; see `ACKNOWLEDGEMENTS.md`.
