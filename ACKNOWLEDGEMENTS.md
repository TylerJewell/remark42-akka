# Acknowledgements

This project is a port of **[umputun/remark42](https://github.com/umputun/remark42)**.

- **Licence and copyright.** MIT, copyright (c) 2021 Umputun (`remark42-src/LICENSE`,
  read directly, not assumed from the repository badge).
- **Copied verbatim.** Nothing — no source files, fixtures, or test corpora were
  copied. `probes/probe_01_tree.go` and `probes/probe_02_controversy.go` were copied
  *into* the source tree temporarily to run against its own unexported types, then
  deleted before this rebuild's own code was written; they are not part of the
  published rebuild.
- **Force on this project's licence.** None — with nothing copied verbatim, this
  rebuild is not obligated to any licence but the one it chooses for itself.
- **Behaviour derived without copying text.** Yes, throughout, and stated plainly:
  every rule in `specs/SPEC-001-remark42.md` §3 is read from `umputun/remark42`'s
  behaviour (comment.go, tree.go, service.go) and reimplemented in Java against the
  Akka SDK, including one quirk (SPEC-001 §3 rule 7 — a direction-flip vote nets to
  a 0 score, not -1) that reads as a bug but is carried forward because it is what
  the source actually computes.

## Also used

- Akka
