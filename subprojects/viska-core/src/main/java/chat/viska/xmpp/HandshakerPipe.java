/*
 * Copyright 2017 Kai-Chung Yan (殷啟聰)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package chat.viska.xmpp;

import chat.viska.commons.pipelines.BlankPipe;
import chat.viska.commons.pipelines.Pipeline;
import chat.viska.sasl.AuthenticationException;
import chat.viska.sasl.Client;
import chat.viska.sasl.ClientFactory;
import chat.viska.sasl.CredentialRetriever;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventObject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.codec.binary.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * For handling handshaking, login and management of an XMPP stream.
 *
 * <h1>Usage</h1>
 *
 * <p>The handshake starts once the {@link Pipeline} starts running or it is
 * already running when this Pipe is added to a Pipeline. In order to get
 * get notified once the handshake/login completes, subscribe to a
 * {@link PropertyChangeEvent} in which {@code State} has changed to
 * {@link State#COMPLETED}. In order to check the cause of a failed handshake
 * or an abnormally closed XMPP stream, check {@link #getHandshakeError()},
 * {@link #getClientStreamError()} and {@link #getServerStreamError()}.</p>
 *
 * <h1>Notes on Behavior</h1>
 *
 * <p>Some behavior of its handshaking process differs from XMPP standards,
 * either because of security considerations or development convenience. These
 * notes may hopefully help contributors understand the logic more easily.</p>
 *
 * <h2>SASL</h2>
 *
 * <p>According to <a href="https://datatracker.ietf.org/doc/rfc6120">RFC
 * 6120</a>, the client may retry the
 * <a href="https://datatracker.ietf.org/doc/rfc4422">SASL</a> authentication
 * for a number of times or even try another mechanism if the authentication
 * fails. However, this class aborts the handshake and close the stream without
 * sending any stream error immediately after the authentication fails.</p>
 */
public class HandshakerPipe extends BlankPipe implements SessionAware {

  /**
   * Indicates the state of a {@link HandshakerPipe}.
   */
  public enum State {

    INITIALIZED,

    /**
     * Indicates a stream opening has been sent and awaiting a stream opening
     * from the server. During this state, any data received that is not a
     * {@link Document} will be forwarded as is.
     */
    STARTED,

    /**
     * Indicates an negotiation of stream features is happening. During this
     * state, any data received that is not a {@link Document} will be forwarded
     * as is.
     */
    NEGOTIATING,

    /**
     * Indicates the handshake is completed. During this state, any data
     * received that is not a {@link Document} will be forwarded as is.
     */
    COMPLETED,

    /**
     * Indicates a stream closing has been issued and awaiting for a closing
     * confirmation from the server. During this state, any data received that
     * is not a {@link Document} will be forwarded as is. The {@link Session}
     * is still functional as usual.
     *
     * <p>If it is the server who sends a stream closing first, a responsive
     * stream closing will be sent immediately and this class will directly
     * enter {@link #STREAM_CLOSED}.</p>
     */
    STREAM_CLOSING,

    /**
     * Indicates there is no XMPP stream running at the moment. This is the
     * initial state when a {@link HandshakerPipe} is newly created. During this
     * state, any data received that is a {@link Document} will be forwarded as
     * is but {@link Document}s will be discarded.
     */
    STREAM_CLOSED,

    /**
     * Indicates the handshaker has been removed from a {@link Pipeline} or the
     * {@link Pipeline} has been disposed. During this state, an
     * {@link IllegalStateException} will be thrown upon receiving any data.
     * This is a terminal state, which means the event stream will terminate and
     * the Pipe will no longer be able to enter other state.
     */
    DISPOSED
  }

  private static final Version SUPPORTED_VERSION = new Version(1, 0);

  private final Subject<EventObject> eventStream = PublishSubject.create();
  private final DocumentBuilder domBuilder;
  private final Session session;
  private final List<StreamFeature> streamFeaturesOrder = new ArrayList<>(
      Arrays.asList(StreamFeature.getRecommendedNeogtiationOrder())
  );
  private final Set<StreamFeature> negotiatedFeatures = new HashSet<>();
  private final Jid jid;
  private final Jid authzId;
  private final CredentialRetriever retriever;
  private final Base64 base64 = new Base64(0, new byte[0], false);
  private final String presetResource;
  private final List<String> saslMechanisms = new ArrayList<>();
  private Pipeline<?, ?> pipeline;
  private Client saslCient;
  private String streamId = "";
  private StreamFeature negotiatingFeature;
  private State state = State.STREAM_CLOSED;
  private Disposable pipelineStartedSubscription;
  private StreamErrorException serverStreamError;
  private StreamErrorException clientStreamError;
  private Exception handshakeError;
  private Jid negotiatedJid;
  private String resourceBindingIqId = "";

  private List<String> convertToMechanismList(NodeList nodes) {
    int cursor = 0;
    final List<String> mechanisms = new ArrayList<>(nodes.getLength());
    while (cursor < nodes.getLength()) {
      mechanisms.add(cursor, nodes.item(cursor).getTextContent());
      ++cursor;
    }
    return mechanisms;
  }

  private StreamErrorException
  convertToStreamErrorException(@NonNull final Document document) {
    StreamErrorException.Condition condition = null;
    Element conditionElement = null;
    int cursor = 0;
    final NodeList nodes = document.getDocumentElement().getChildNodes();
    while (cursor < nodes.getLength()) {
      conditionElement = (Element) nodes.item(cursor);
      condition = StreamErrorException.Condition.of(conditionElement.getLocalName());
      if (condition != null) {
        break;
      } else {
        ++cursor;
      }
    }
    if (condition != null) {
      NodeList textNodes = conditionElement.getElementsByTagNameNS(
          CommonXmlns.STREAM_CONTENT, "text"
      );
      if (textNodes.getLength() > 0) {
        return new StreamErrorException(
            condition,
            textNodes.item(0).getTextContent()
        );
      } else {
        return new StreamErrorException(condition);
      }
    } else if (document.getDocumentElement().hasChildNodes()) {
      return new StreamErrorException(
          StreamErrorException.Condition.UNDEFINED_CONDITION,
          String.format(
              "[UNRECOGNIZED ERROR: %1s]",
              document.getDocumentElement().getFirstChild().getLocalName()
          )
      );
    } else {
      return new StreamErrorException(
          StreamErrorException.Condition.UNDEFINED_CONDITION,
          "[NO ERROR CONDITION SPECIFIED]"
      );
    }
  }

  private boolean checkIfAllMandatoryFeaturesNegotiated() {
    final List<StreamFeature> notNegotiated = new ArrayList<>(streamFeaturesOrder);
    notNegotiated.removeAll(negotiatedFeatures);
    for (StreamFeature feature : notNegotiated) {
      if (feature.isMandatory()) {
        return false;
      }
    }
    return true;
  }

  private void sendXml(@NonNull final String xml) {
    try {
      pipeline.write(domBuilder.parse(
          new InputSource(new StringReader(xml))
      ));
    } catch (IOException | SAXException ex) {
      throw new RuntimeException(ex);
    }
  }

  private void sendStreamOpening() {
    sendXml(String.format(
        "<open xmlns=\"%1s\" to=\"%2s\" version=\"1.0\"/>",
        CommonXmlns.STREAM_OPENING_WEBSOCKET,
        jid.getDomainPart()
    ));
  }

  private void sendStreamClosing() {
    sendXml(String.format(
        "<close xmlns=\"%1s\"/>",
        CommonXmlns.STREAM_OPENING_WEBSOCKET
    ));
  }

  private void sendStreamError(@NonNull final StreamErrorException error) {
    final Document document;
    try {
      document = domBuilder.parse(new InputSource(new StringReader(String.format(
          "<error xmlns=\"%1s\"><%2s xmlns=\"%3s\"/></error>",
          CommonXmlns.STREAM_HEADER,
          error.getCondition().toString(),
          CommonXmlns.STREAM_CONTENT
      ))));
    } catch (SAXException | IOException ex) {
      throw new RuntimeException(ex);
    }
    if (error.getMessage() != null) {
      final Element textElement = document.createElementNS(
          CommonXmlns.STREAM_CONTENT,
          "text"
      );
      textElement.setTextContent(error.getMessage());
      document.getDocumentElement().appendChild(textElement);
    }
    pipeline.write(document);
    this.clientStreamError = error;
    closeStream();
  }

  private void consumeStreamOpening(@NonNull final Document document) {
    Objects.requireNonNull(document);
    Version serverVersion = null;
    final String serverVersionText = document.getDocumentElement().getAttribute(
        "version"
    );
    try {
      serverVersion = new Version(serverVersionText);
    } catch (Exception ex) {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.UNSUPPORTED_VERSION,
          serverVersionText,
          ex
      ));
    }
    if (!SUPPORTED_VERSION.equals(serverVersion)) {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.UNSUPPORTED_VERSION,
          serverVersionText
      ));
    }
    final String serverDomain = document
        .getDocumentElement()
        .getAttribute("from");
    if (!serverDomain.equals(this.jid.getDomainPart())) {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.INVALID_FROM,
          serverDomain
      ));
    }
    final String streamId = document
        .getDocumentElement()
        .getAttribute("id");
    if (streamId.trim().isEmpty()) {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.INVALID_XML,
          "Empty stream ID"
      ));
    }
    this.streamId = streamId;
  }

  private Element consumeStreamFeatures(@NonNull final Document document) {
    final NodeList serverFeatures = document.getDocumentElement().getChildNodes();
    if (serverFeatures.getLength() == 0) {
      return null;
    }

    for (StreamFeature it : this.streamFeaturesOrder) {
      int cursor = 0;
      while (cursor < serverFeatures.getLength()) {
        final Element currentFeature = (Element) serverFeatures.item(cursor);
        if (it.getName().equals(currentFeature.getLocalName())
            && it.getNamespace().equals(currentFeature.getNamespaceURI())) {
          this.negotiatingFeature = it;
          return currentFeature;
        }
        ++cursor;
      }
    }

    return null;
  }

  private void initializeSaslNegotiation(@NonNull final Element featureElement)
      throws AuthenticationException {
    this.saslCient = new ClientFactory(this.saslMechanisms).newClient(
        convertToMechanismList(featureElement.getChildNodes()),
        this.jid.getLocalPart(),
        this.authzId == null ? null : this.authzId.toString(),
        this.retriever
    );
    if (this.saslCient == null) {
      sendXml(String.format(
          "<abort xmlns=\"%1s\"/>",
          CommonXmlns.SASL
      ));
      AuthenticationException saslException = new AuthenticationException(
          AuthenticationException.Condition.INVALID_MECHANISM
      );
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.POLICY_VIOLATION,
          saslException
      ));
      throw saslException;
    }
    String msg = "";
    if (this.saslCient.isClientFirst()) {
      msg = this.base64.encodeToString(this.saslCient.respond());
      if (msg.isEmpty()) {
        msg = "=";
      }
    }
    sendXml(String.format(
        "<auth xmlns=\"%1s\" mechanism=\"%2s\">%3s</auth>",
        CommonXmlns.SASL,
        this.saslCient.getMechanism(),
        msg
    ));
  }

  private void initializeResourceBinding() {
    if (this.resourceBindingIqId.isEmpty()) {
      this.resourceBindingIqId = UUID.randomUUID().toString();
    }
    StringBuilder xml = new StringBuilder();
    xml.append(String.format(
        "<iq id=\"%1s\" type=\"set\"><bind xmlns=\"%2s\"",
        this.resourceBindingIqId,
        CommonXmlns.RESOURCE_BINDING
    ));
    if (!this.presetResource.isEmpty()) {
      xml.append(">");
      xml.append(String.format(
          "<resource>%1s</resource>",
          this.presetResource
      ));
      xml.append("</bind>");
    } else {
      xml.append("/>");
    }
    xml.append("</iq>");
    sendXml(xml.toString());
  }

  private void consumeStartTls(@NonNull final Document document) {
    throw new UnsupportedOperationException();
  }

  private void consumeSasl(@NonNull final Document document)
      throws AuthenticationException {
    final String rootName = document.getDocumentElement().getLocalName();
    final String text = document.getDocumentElement().getTextContent();

    if (this.saslCient.isCompleted() && !text.isEmpty()) {
      final AuthenticationException saslException = new AuthenticationException(
          AuthenticationException.Condition.MALFORMED_REQUEST
      );
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.POLICY_VIOLATION,
          saslException
      ));
      throw saslException;
    }

    if ("failure".equals(rootName)) {
      this.negotiatedFeatures.remove(this.negotiatingFeature);
      this.negotiatingFeature = null;
      closeStream();
      this.handshakeError = new AuthenticationException(
          AuthenticationException.Condition.CLIENT_NOT_AUTHORIZED
      );
      throw (AuthenticationException) this.handshakeError;
    } else if ("success".equals(rootName) && text.isEmpty()) {
      if (!this.saslCient.isCompleted()) {
        final AuthenticationException saslException = new AuthenticationException(
            AuthenticationException.Condition.MALFORMED_REQUEST
        );
        sendStreamError(new StreamErrorException(
            StreamErrorException.Condition.POLICY_VIOLATION,
            saslException
        ));
        throw saslException;
      } else {
        this.negotiatedFeatures.add(this.negotiatingFeature);
        this.negotiatingFeature = null;
        sendStreamOpening();
      }
    } else if ("success".equals(rootName) && !text.isEmpty()) {
      this.saslCient.acceptChallenge(this.base64.decode(text));
      if (!this.saslCient.isCompleted()) {
        final AuthenticationException saslException = new AuthenticationException(
            AuthenticationException.Condition.MALFORMED_REQUEST
        );
        sendStreamError(new StreamErrorException(
            StreamErrorException.Condition.POLICY_VIOLATION,
            saslException
        ));
        throw saslException;
      } else if (this.saslCient.getError() != null) {
        sendStreamError(new StreamErrorException(
            StreamErrorException.Condition.POLICY_VIOLATION,
            this.saslCient.getError()
        ));
        throw this.saslCient.getError();
      } else {
        this.negotiatedFeatures.add(this.negotiatingFeature);
        this.negotiatingFeature = null;
        sendStreamOpening();
      }
    } else if ("challenge".equals(rootName)) {
      this.saslCient.acceptChallenge(this.base64.decode(text));
      if (!this.saslCient.isCompleted()) {
        final byte[] response = this.saslCient.respond();
        if (response != null) {
          sendXml(String.format(
              "<response xmlns=\"%1s\">%2s</response>",
              CommonXmlns.SASL,
              this.base64.encodeToString(response)
          ));
        } else {
          sendXml(String.format(
              "<abort xmlns=\"%1s\"/>",
              CommonXmlns.SASL
          ));
          sendStreamError(new StreamErrorException(
              StreamErrorException.Condition.NOT_AUTHORIZED,
              this.saslCient.getError()
          ));
          throw this.saslCient.getError();
        }
      }
    } else {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.UNSUPPORTED_STANZA_TYPE
      ));
    }
  }

  private void consumeStreamCompression(@NonNull final Document document) {
    throw new UnsupportedOperationException();
  }

  private void consumeResourceBinding(@NonNull final Document document) {
    if (!this.resourceBindingIqId.equals(document.getDocumentElement().getAttribute("id"))) {
      sendStreamError(new StreamErrorException(
          StreamErrorException.Condition.NOT_AUTHORIZED
      ));
    } else if ("error".equals(document.getDocumentElement().getAttribute("type"))) {
      try {
        this.handshakeError = StanzaErrorException.fromXml(document);
      } catch (StreamErrorException ex) {
        sendStreamError(ex);
      }
    } else if ("result".equals(document.getDocumentElement().getAttribute("type"))) {
      final Element bindElement = (Element) document
          .getDocumentElement()
          .getElementsByTagNameNS(CommonXmlns.RESOURCE_BINDING, "bind")
          .item(0);
      final String[] results = bindElement
          .getElementsByTagNameNS(CommonXmlns.RESOURCE_BINDING, "jid")
          .item(0)
          .getTextContent()
          .split(" ");
      switch (results.length) {
        case 1:
          this.negotiatedJid = new Jid(results[0]);
          break;
        case 2:
          if (new Jid(results[0]).equals(this.jid)) {
            this.negotiatedJid = new Jid(
                this.jid.getLocalPart(),
                this.jid.getDomainPart(),
                results[1]
            );
          } else {
            sendStreamError(new StreamErrorException(
                StreamErrorException.Condition.INVALID_XML,
                "Resource Binding result contains incorrect JID."
            ));
          }
        default:
          sendStreamError(new StreamErrorException(
              StreamErrorException.Condition.INVALID_XML,
              "Malformed JID syntax."
          ));
          break;
      }
    }
    if (this.negotiatedJid != null) {
      this.negotiatedFeatures.add(this.negotiatingFeature);
      this.negotiatingFeature = null;
    }
  }

  private synchronized void setState(final State state) {
    if (state == this.state) {
      return;
    }
    final State oldState = this.state;
    this.state = state;
    eventStream.onNext(new PropertyChangeEvent(this, "State", oldState, state));
    if (state == State.DISPOSED) {
      eventStream.onComplete();
    }
  }

  private synchronized void start() {
    switch (state) {
      case DISPOSED:
        throw new IllegalStateException("HandshakePipe disposed.");
      case STREAM_CLOSED:
        setState(State.STARTED);
        serverStreamError = null;
        clientStreamError = null;
        handshakeError = null;
        sendStreamOpening();
        break;
      default:
    }
  }

  /**
   * Default constructor.
   * @param session Associated XMPP session.
   * @param jid Authentication ID, which is typically the local part of a
   *        {@link Jid} for
   *        <a href="https://datatracker.ietf.org/doc/rfc4422">SASL</a>
   *        mechanisms which uses a "simple user name".
   * @param authzId Authorization ID, which is a bare {@link Jid}.
   * @param retriever Credential retriever.
   * @param saslMechanisms <a href="https://datatracker.ietf.org/doc/rfc4422">SASL</a>
   *        Mechanisms used during handshake. Use {@code null} to specify the
   *        default ones.
   * @param resource XMPP Resource. If {@code null} or empty, the server will
   *                 generate a random one on behalf of the client.
   * @param registering Indicates if the handshake includes in-band
   *                    registration.
   */
  public HandshakerPipe(@NonNull final Session session,
                        @NonNull final Jid jid,
                        @Nullable final Jid authzId,
                        @NonNull final CredentialRetriever retriever,
                        @Nullable final List<String> saslMechanisms,
                        @Nullable final String resource,
                        final boolean registering) {
    Objects.requireNonNull(session, "`session` is absent.");
    Objects.requireNonNull(jid, "`jid` is absent.");
    Objects.requireNonNull(retriever, "`retriever` is absent.");
    this.session = session;
    this.jid = jid;
    this.authzId = authzId;
    this.retriever = retriever;
    if (saslMechanisms == null || saslMechanisms.isEmpty()) {
      this.saslMechanisms.add("SCRAM-SHA-1");
    } else {
      this.saslMechanisms.addAll(saslMechanisms);
    }
    this.presetResource = resource == null ? "" : resource;

    // Initializing DOM builder
    DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
    builderFactory.setIgnoringComments(true);
    builderFactory.setNamespaceAware(true);
    try {
      domBuilder = builderFactory.newDocumentBuilder();
    } catch (ParserConfigurationException ex) {
      throw new RuntimeException(ex);
    }

    // Preprocessing stream features list
    if (!this.session.getConnection().isStartTlsRequired()) {
      this.negotiatedFeatures.add(StreamFeature.STARTTLS);
    }
    if (this.session.getStreamCompression() == null) {
      this.negotiatedFeatures.add(StreamFeature.STREAM_COMPRESSION);
    }
    if (registering) {
      throw new UnsupportedOperationException(
          "In-Band Registration not supported."
      );
    }
  }

  /**
   * Closes the XMPP stream. After the stream is closed, a full handshake is
   * is required in order to reopen a stream, in which case this class can be
   * reused if it is not removed from the attached {@link Pipeline} yet.
   * @throws IllegalStateException If this class is in {@link State#DISPOSED}.
   */
  public synchronized Completable closeStream() {
    switch (state) {
      case STREAM_CLOSED:
        return Completable.complete();
      case DISPOSED:
        throw new IllegalStateException("The pipe has been disposed.");
      default:
        if (this.state != State.STREAM_CLOSING) {
          sendStreamClosing();
          setState(State.STREAM_CLOSING);
        }
        return this.eventStream
            .ofType(PropertyChangeEvent.class)
            .map(PropertyChangeEvent::getNewValue)
            .filter(it -> it == State.STREAM_CLOSED)
            .firstOrError()
            .toCompletable();
    }
  }

  /**
   * Gets the JID negotiated during Resource Binding.
   * @return {@code null} if the negotiation is not completed yet.
   */
  @Nullable
  public Jid getJid() {
    return negotiatedJid;
  }

  /**
   * Gets a stream of emitted {@link EventObject}s. It never emits any errors
   * but will emit a completion when this class is disposed of.
   *
   * <p>This class emits only the following types of {@link EventObject}:</p>
   *
   * <ul>
   *   <li>{@link chat.viska.commons.ExceptionCaughtEvent}</li>
   *   <li>
   *     {@link java.beans.PropertyChangeEvent}
   *     <ul>
   *       <li>{@code State}</li>
   *       <li>{@code Resource}</li>
   *     </ul>
   *   </li>
   * </ul>
   */
  @NonNull
  public Observable<EventObject> getEventStream() {
    return eventStream;
  }

  /**
   * Gets the stream ID. It corresponds to the {@code id} attribute of the
   * stream opening.
   */
  @NonNull
  public String getStreamId() {
    return streamId;
  }

  /**
   * Gets the current {@link State} of this class.
   */
  @NonNull
  public State getState() {
    return state;
  }

  /**
   * Gets the stream error sent by the server.
   * @return {@code null} if the XMPP stream is still running, or if the server
   *         did not send any stream error during the last stream.
   */
  @Nullable
  public StreamErrorException getServerStreamError() {
    return serverStreamError;
  }

  /**
   * Gets the stream error sent by this class to the server.
   * @return {@code null} if the XMPP stream is still running, or if this class
   *         did not send any stream error during the last stream.
   */
  @Nullable
  public StreamErrorException getClientStreamError() {
    return clientStreamError;
  }

  /**
   * Gets the error occured during a handshake.
   * @return {@code null} if the handshake is not completed yet or it was
   *         successful.
   */
  @Nullable
  public Exception getHandshakeError() {
    return handshakeError;
  }

  /**
   * Invoked when the Pipe is reading data.
   * @throws AuthenticationException If a failure occurred during an SASL
   *         negotiation.
   */
  @Override
  public synchronized void onReading(final Pipeline<?, ?> pipeline,
                                     final Object toRead,
                                     final List<Object> toForward)
      throws Exception {
    if (this.state == State.DISPOSED) {
      throw new IllegalStateException("Pipe disposed.");
    }

    final Document document;
    if (toRead instanceof Document) {
      document = (Document) toRead;
    } else {
      document = null;
    }
    if (document == null) {
      super.onReading(pipeline, toRead, toForward);
      return;
    }

    if (this.state == State.STREAM_CLOSED) {
      return;
    }

    final String rootName = document.getDocumentElement().getLocalName();
    final String rootNs = document.getDocumentElement().getNamespaceURI();

    if ("open".equals(rootName) && CommonXmlns.STREAM_OPENING_WEBSOCKET.equals(rootNs)) {
      switch (state) {
        case STARTED:
          consumeStreamOpening(document);
          setState(State.NEGOTIATING);
          break;
        case NEGOTIATING:
          consumeStreamOpening(document);
          break;
        case COMPLETED:
          sendStreamError(new StreamErrorException(
              StreamErrorException.Condition.CONFLICT,
              "Server unexpectedly restarted the stream."
          ));
          break;
        default:
          break;
      }
    } else if ("close".equals(rootName) && CommonXmlns.STREAM_OPENING_WEBSOCKET.equals(rootNs)) {
      switch (state) {
        case STREAM_CLOSING:
          setState(State.STREAM_CLOSED);
          return;
        default:
          sendStreamClosing();
          setState(State.STREAM_CLOSING);
          return;
      }
    } else if ("features".equals(rootName)
        && CommonXmlns.STREAM_HEADER.equals(rootNs)
        && this.state == State.NEGOTIATING) {
      final Element selectedFeature = consumeStreamFeatures(document);
      if (selectedFeature == null) {
        if (checkIfAllMandatoryFeaturesNegotiated()) {
          setState(State.COMPLETED);
        } else {
          sendStreamError(new StreamErrorException(
              StreamErrorException.Condition.UNSUPPORTED_FEATURE
          ));
        }
      } else {
        switch (negotiatingFeature) {
          case SASL:
            this.session.getLogger().fine("Negotiating SASL.");
            initializeSaslNegotiation(selectedFeature);
            break;
          case RESOURCE_BINDING:
            this.session.getLogger().fine("Negotiating Resource Binding.");
            initializeResourceBinding();
            break;
          default:
            sendStreamError(new StreamErrorException(
                StreamErrorException.Condition.UNSUPPORTED_FEATURE
            ));
            break;
        }
      }
    } else if (CommonXmlns.SASL.equals(rootNs)
        && this.state == State.NEGOTIATING
        && negotiatingFeature == StreamFeature.SASL) {
      consumeSasl(document);
    } else if (this.negotiatingFeature == StreamFeature.RESOURCE_BINDING
        && state == State.NEGOTIATING
        && "iq".equals(rootName)) {
      consumeResourceBinding(document);
      if (negotiatedFeatures.contains(StreamFeature.RESOURCE_BINDING)
          && checkIfAllMandatoryFeaturesNegotiated()) {
        setState(State.COMPLETED);
      }
    } else if ("error".equals(rootName) && CommonXmlns.STREAM_HEADER.equals(rootNs)) {
      closeStream().onErrorComplete().subscribe();
      this.serverStreamError = convertToStreamErrorException(document);
    } else {
      if (state == State.COMPLETED || state == State.STREAM_CLOSING) {
        super.onReading(pipeline, toRead, toForward);
      } else {
        sendStreamError(new StreamErrorException(
            StreamErrorException.Condition.UNSUPPORTED_STANZA_TYPE
        ));
      }
    }
    if (this.handshakeError != null) {
      closeStream().onErrorComplete().subscribe();
    }
  }

  @Override
  public synchronized void onAddedToPipeline(final Pipeline<?, ?> pipeline) {
    // TODO: Support for stream resumption
    if (state == State.DISPOSED) {
      throw new IllegalStateException();
    }
    this.session
        .getEventStream()
        .ofType(Session.ConnectionTerminatedEvent.class)
        .subscribe(event -> setState(State.STREAM_CLOSED));
    this.pipeline = pipeline;
    final Pipeline.State pipelineState = pipeline.getState();
    if (pipelineState == Pipeline.State.STOPPED) {
      pipelineStartedSubscription = pipeline.getEventStream()
          .ofType(PropertyChangeEvent.class)
          .filter(event -> event.getNewValue() == Pipeline.State.RUNNING)
          .firstElement()
          .subscribe(event -> start());
    } else {
      start();
    }
  }

  @Override
  public synchronized void onRemovedFromPipeline(final Pipeline<?, ?> pipeline) {
    setState(State.DISPOSED);
    pipelineStartedSubscription.dispose();
  }

  @Override
  public synchronized void onWriting(Pipeline<?, ?> pipeline,
                                     Object toWrite,
                                     List<Object> toForward) throws Exception {
    switch (this.state) {
      case DISPOSED:
        throw new IllegalStateException("Pipe disposed.");
      case STREAM_CLOSED:
        if (!(toWrite instanceof Document)) {
          super.onWriting(pipeline, toWrite, toForward);
        }
        break;
      default:
        super.onWriting(pipeline, toWrite, toForward);
    }
  }

  @Override
  public Session getSession() {
    return session;
  }
}