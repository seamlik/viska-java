package chat.viska.xmpp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JidTest {

  @Test
  public void parseJidTest() {
    new Jid("juliet@example.com");
    new Jid("example.com");
    new Jid("example.com/foo");
    new Jid("juliet@example.com/foo@bar");
    Assertions.assertThrows(
        InvalidJidSyntaxException.class,
        () -> new Jid("@example.com")
    );
    Assertions.assertThrows(
        InvalidJidSyntaxException.class,
        () -> new Jid("@")
    );
    Assertions.assertThrows(
        InvalidJidSyntaxException.class,
        () -> new Jid("/")
    );
  }
}