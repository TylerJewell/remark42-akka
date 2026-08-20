package io.akka.remark42.domain;

import java.util.List;

/** Result of paginating a built tree. SPEC-001 §3 rule 5. */
public record Tree(List<Node> nodes, int countLeft, String lastComment) {}
