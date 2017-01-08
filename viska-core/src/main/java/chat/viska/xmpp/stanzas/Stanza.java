package chat.viska.xmpp.stanzas;

import chat.viska.xmpp.Jid;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.convert.AnnotationStrategy;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.strategy.ClassAttributeRemovingVisitor;
import org.simpleframework.xml.strategy.VisitorStrategy;
import org.simpleframework.xml.transform.RegistryMatcher;
import org.simpleframework.xml.transform.XmppJidTransform;
import org.simpleframework.xml.transform.XmppStanzaTypeTransform;

/**
 * Complete XML document sent between XMPP clients and XMPP servers. There are
 * only 3 types of a {@link Stanza}: {@link Presence}, {@link Message} and
 * {@link InfoQuery}. There should be no more subtypes of this type.
 * @since 0.1
 */
public abstract class Stanza implements SimpleXmlSerializable {

  /**
   * The type of a {@link Stanza}.
   * <p>
   *   Different {@link Stanza}s support different sets of types. Note that only
   *   {@link Type#ERROR} is the common type of all {@link Stanza}s.
   * </p>
   * @see <a href="https://tools.ietf.org/html/rfc6121#section-5.2.2">"Type"
   *      attribute of a message stanza</a>
   * @see <a href="https://tools.ietf.org/html/rfc6121#section-4.7.1">"Type"
   *      attribute of a presence stanza</a>
   * @see <a href="https://tools.ietf.org/html/rfc6120#section-8.2.3">"Type"
   *      attribute of an iq stanza</a>
   */
  public enum Type {

    /**
     * Sending the {@link Message} to a ont-to-one chat session.
     */
    CHAT(Message.class),

    /**
     * Indicating an error has occurred.
     */
    ERROR(InfoQuery.class, Message.class, Presence.class),

    /**
     * Requesting information, inquires about what data is needed in order to
     * complete further operations.
     */
    GET(InfoQuery.class),

    /**
     * Sending the {@link Message} to a multi-user chat session.
     */
    GROUPCHAT(Message.class),

    /**
     * Sending an alert, a notification, or other transient information to which
     * no reply is expected.
     */
    HEADLINE(Message.class),

    /**
     * Sending the {@link Message} to a one-to-one or multi-user chat session.
     */
    NORMAL(Message.class),

    /**
     * Requesting for an entity's current presence.
     */
    PROBE(Presence.class),

    /**
     * Representing a response to a successful get or set request.
     */
    RESULT(InfoQuery.class),

    /**
     * Providing data that is needed for an operation to be completed, setting
     * new values, replacing existing values, etc..
     */
    SET(InfoQuery.class),

    /**
     * Wishing to subscribe to the recipient's presence.
     */
    SUBSCRIBE(Presence.class),

    /**
     * Allowing the recipient to receive the presence.
     */
    SUBSCRIBED(Presence.class),

    /**
     * Indicating the sender is no longer available for communication.
     */
    UNAVAILABLE(Presence.class),

    /**
     * Unsubscribing from the recipient's presence.
     */
    UNSUBSCRIBE(Presence.class),

    /**
     * Denying the subscription request or indicating a previously granted
     * subscription has been canceled.
     */
    UNSUBSCRIBED(Presence.class);

    /**
     * Parse the value of a stanza attribute as an instance of this enum.
     * @return {@code null} if the argument is {@code null}.
     */
    public static Type of(String value) {
      return (value == null) ? null : Enum.valueOf(
        Type.class, value.toUpperCase()
      );
    }

    private List<Class<?>> applicableStanzasTypes;

    Type(Class<?>... applicableStanzasTypes) {
      this.applicableStanzasTypes = Arrays.asList(applicableStanzasTypes);
    }

    /**
     * Returns the name of this enum compatible with the XMPP standards
     * and suitable for being written into a stanza.
     */
    @Override
    public String toString() {
      return this.name().toLowerCase();
    }

    /**
     * Determines if an {@link InfoQuery} accepts this {@link Type}.
     * @return {@code true} if this enum constant is valid for an
     *         {@link InfoQuery}, {@code false} otherwise.
     */
    public boolean isInfoQueryType() {
      return applicableStanzasTypes.contains(InfoQuery.class);
    }

    /**
     * Determines if an {@link Message} accepts this {@link Type}.
     * @return {@code true} if this enum constant is valid for an
     *         {@link Message}, {@code false} otherwise.
     */
    public boolean isMessageType() {
      return applicableStanzasTypes.contains(Message.class);
    }

    /**
     * Determines if an {@link Presence} accepts this {@link Type}.
     * @return {@code true} if this enum constant is valid for an
     *         {@link Presence}, {@code false} otherwise.
     */
    public boolean isPresenceType() {
      return applicableStanzasTypes.contains(Presence.class);
    }
  }

  private String id;
  private Type type;
  private Jid sender;
  private Jid recipient;

  protected Stanza(String id, Type type, Jid sender, Jid recipient) {
    this.id = id;
    this.type = type;
    this.sender = sender;
    this.recipient = recipient;
  }

  @Override
  public void writeXml(Writer output) throws Exception {
    RegistryMatcher matcher = new RegistryMatcher();
    matcher.bind(Jid.class, new XmppJidTransform());
    matcher.bind(Stanza.Type.class, new XmppStanzaTypeTransform());
    Serializer serializer = new Persister(
        new AnnotationStrategy(new VisitorStrategy(
            new ClassAttributeRemovingVisitor()
        )),
        matcher
    );
    serializer.write(this, output);
  }

  /**
   * Returns the recipient.
   * <p>
   *   This property must be {@code null} if this {@link Stanza} is sent to the
   *   server for processing commands and must not be {@code null} in other
   *   cases.
   * </p>
   * <p>
   *   This property represents the {@code to} attribute.
   * </p>
   * @throws chat.viska.xmpp.InvalidJidSyntaxException If the original value of
   *         this attribute is not a valid {@link Jid}.
   * @see <a href="https://tools.ietf.org/html/rfc6120#section-8.1.1">RFC 6120:
   *      XMPP Core</a>
   *
   */
  @Attribute(name = "to", required = false)
  public Jid getRecipient() {
    return recipient;
  }

  /**
   * Returns the sender.
   * <p>
   *   This property may be {@code null} if this {@link Stanza} is generated by
   *   the home server.
   * </p>
   * <p>
   *   This property represents the {@code from} attribute.
   * </p>
   * @throws chat.viska.xmpp.InvalidJidSyntaxException If the original value of
   *         this attribute is not a valid {@link Jid}.
   * @see <a href="https://tools.ietf.org/html/rfc6120#section-8.1.2">RFC 6120:
   *      XMPP Core</a>
   */
  @Attribute(name = "from", required = false)
  public Jid getSender() {
    return sender;
  }

  /**
   * Returns the ID .
   * <p>
   *   This property represents the {@code id} attribute. It is mandatory for an
   *   {@code <iq/>} and optional for a {@code <message/>} or a
   *   {@code <presence/>}.
   * </p>
   * @see <a href="https://tools.ietf.org/html/rfc6120#section-8.1.3">RFC 6120:
   *      XMPP Core</a>
   */
  @Attribute(name = "id", required = false)
  public String getId() {
    return id;
  }

  /**
   * Returns the {@link Type} of this {@link Stanza}.
   * <p>
   *   This property represents the {@code type} attribute.
   * </p>
   * @see <a href="https://tools.ietf.org/html/rfc6120#section-8.1.4">RFC 6120:
   *      XMPP Core</a>
   */
  @Attribute(name = "type", required = false)
  public Type getType() {
    return type;
  }
}