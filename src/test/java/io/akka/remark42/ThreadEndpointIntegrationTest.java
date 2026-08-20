package io.akka.remark42;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.remark42.api.ThreadEndpoint;
import io.akka.remark42.domain.Comment;
import io.akka.remark42.domain.Tree;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The HTTP surface, end to end against a running runtime — the whole capability driven the way a
 * caller drives it, since this port has no rendered interface (see {@code gui/manifest.json}).
 */
public class ThreadEndpointIntegrationTest extends TestKitSupport {

  private String site() {
    return "site-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  public void postsAReplyVotesOnItAndReadsTheTree() {
    var site = site();
    var post = "post-1";

    var root =
        httpClient
            .POST("/sites/" + site + "/posts/" + post + "/comments")
            .withRequestBody(new ThreadEndpoint.CommentRequest("", "hello", "author-1", "Author One"))
            .responseBodyAs(Comment.class)
            .invoke()
            .body();
    assertThat(root.parentId()).isEmpty();

    var reply =
        httpClient
            .POST("/sites/" + site + "/posts/" + post + "/comments")
            .withRequestBody(
                new ThreadEndpoint.CommentRequest(root.id(), "hi back", "author-2", "Author Two"))
            .responseBodyAs(Comment.class)
            .invoke()
            .body();
    assertThat(reply.parentId()).isEqualTo(root.id());

    var voted =
        httpClient
            .POST("/sites/" + site + "/posts/" + post + "/comments/" + root.id() + "/vote")
            .withRequestBody(new ThreadEndpoint.VoteRequest("voter-1", "1.2.3.4", true))
            .responseBodyAs(Comment.class)
            .invoke()
            .body();
    assertThat(voted.score()).isEqualTo(1);

    var tree =
        httpClient
            .POST("/sites/" + site + "/posts/" + post + "/tree")
            .withRequestBody(new ThreadEndpoint.TreeRequest("time", 0, ""))
            .responseBodyAs(Tree.class)
            .invoke()
            .body();
    assertThat(tree.nodes()).hasSize(1);
    assertThat(tree.nodes().get(0).replies()).hasSize(1);
  }

  @Test
  public void blockedUserCannotComment() {
    var site = site();
    var post = "post-2";

    httpClient.POST("/sites/" + site + "/users/troll/block").withRequestBody(new ThreadEndpoint.BlockRequest(0)).invoke();

    var response =
        httpClient
            .POST("/sites/" + site + "/posts/" + post + "/comments")
            .withRequestBody(new ThreadEndpoint.CommentRequest("", "spam", "troll", "Troll"))
            .invoke();
    assertThat(response.status().intValue()).isEqualTo(403);
  }
}
