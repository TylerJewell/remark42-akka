package io.akka.remark42.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 12: block state with an optional expiry. */
class BlockedUserEntityTest {

  private KeyValueEntityTestKit<BlockedUserEntity.State, BlockedUserEntity> blockedUser() {
    return KeyValueEntityTestKit.of("site-1:user-1", BlockedUserEntity::new);
  }

  @Test
  void permanentBlockNeverExpires() {
    var kit = blockedUser();
    kit.method(BlockedUserEntity::block).invoke(new BlockedUserEntity.Block(Duration.ZERO));
    assertThat(kit.method(BlockedUserEntity::isBlocked).invoke().getReply()).isTrue();
  }

  @Test
  void unblockedUserReadsFalse() {
    var kit = blockedUser();
    assertThat(kit.method(BlockedUserEntity::isBlocked).invoke().getReply()).isFalse();
  }

  @Test
  void unblockClearsAnExistingBlock() {
    var kit = blockedUser();
    kit.method(BlockedUserEntity::block).invoke(new BlockedUserEntity.Block(Duration.ZERO));
    kit.method(BlockedUserEntity::unblock).invoke();
    assertThat(kit.method(BlockedUserEntity::isBlocked).invoke().getReply()).isFalse();
  }
}
