/*
 * Copyright (C) 2017 Kai-Chung Yan (殷啟聰)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package chat.viska.xmpp;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * XMPP address.
 *
 * <p>Also known as Jabber Identifier, an JID is an address for locating an XMPP
 * entity. A typical example of a JID would be
 * "{@literal localPart@domainPart/resourcePart}".</p>
 *
 * <p>A JID usually consists of 3 parts: local part, domain part and resource
 * part. The local part refers to the user name of an XMPP account, the domain
 * part refers to an XMPP server, and the resource part refers to a client
 * connected to the server and logged in with the account.</p>
 *
 * <p>This class is immutable and will not validate the JID before it is
 * created.</p>
 * @see <a href="https://tools.ietf.org/html/rfc7622">RFC 7622</a>
 */
public class Jid {

  public static final Jid EMPTY = new Jid("", "", "");

  private final String localPart;
  private final String domainPart;
  private final String resourcePart;

  private static List<String> parseJidParts(final String rawJid) {
    final List<String> result = Arrays.asList("", "", "");
    if (StringUtils.isBlank(rawJid)) {
      return result;
    }

    final int indexOfSlash = rawJid.indexOf("/");

    if (indexOfSlash > 0) {
      result.set(2, rawJid.substring(indexOfSlash + 1, rawJid.length()));
    } else if (indexOfSlash < 0) {
      result.set(2, "");
    } else {
      throw new InvalidJidSyntaxException();
    }

    final String bareJid = indexOfSlash > 0
        ? rawJid.substring(0, indexOfSlash)
        : rawJid;
    final int indexOfAt = rawJid.indexOf("@");
    if (indexOfAt > 0) {
      result.set(0, bareJid.substring(0, indexOfAt));
      result.set(1, bareJid.substring(indexOfAt + 1));
    } else if (indexOfAt < 0) {
      result.set(0, "");
      result.set(1, rawJid);
    } else {
      throw new InvalidJidSyntaxException();
    }
    return result;
  }

  public static boolean isEmpty(@Nullable final Jid jid) {
    return jid == null || jid.isEmpty();
  }

  private Jid(@Nullable final List<String> parts) {
    if (parts == null || parts.size() == 0) {
      localPart = "";
      domainPart = "";
      resourcePart = "";
    } else if (parts.size() != 3) {
      throw new InvalidJidSyntaxException();
    } else {
      localPart = parts.get(0);
      domainPart = parts.get(1);
      resourcePart = parts.get(2);
    }
  }

  /**
   * Constructs with 3 parts specified.
   */
  public Jid(final String localPart, final String domainPart, final String resourcePart) {
    this.localPart = localPart;
    this.domainPart = domainPart;
    this.resourcePart = resourcePart;
  }

  /**
   * Constructs using a literal.
   */
  public Jid(final String jid) {
    this(parseJidParts(jid));
  }

  /**
   * Returns the {@link String} representation of this JID.
   * @return never {@code null}.
   */
  @Override
  public String toString() {
    if (isEmpty()) {
      return "";
    }
    StringBuilder result = new StringBuilder(domainPart);
    if (!localPart.isEmpty()) {
      result.insert(0, '@').insert(0, localPart);
    }
    if (!resourcePart.isEmpty()) {
      result.append('/').append(resourcePart);
    }
    return result.toString();
  }

  /**
   * Returns the localPart part of this JID.
   * @return never {@code null}.
   */
  public String getLocalPart() {
    return localPart;
  }

  /**
   * Returns the domainPart part of this JID.
   * @return {@code null}.
   */
  public String getDomainPart() {
    return domainPart;
  }

  /**
   * Returns the resourcePart part of this JID.
   * @return never {@code null}.
   */
  public String getResourcePart() {
    return resourcePart;
  }

  public Jid toBareJid() {
    return resourcePart.isEmpty() ? this : new Jid(localPart, domainPart, "");
  }

  public boolean isEmpty() {
    return localPart.isEmpty() && domainPart.isEmpty() && resourcePart.isEmpty();
  }

  @Override
  public boolean equals(@Nullable final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Jid that = (Jid)obj;
    return Objects.equals(localPart, that.localPart)
        && Objects.equals(domainPart, that.domainPart)
        && Objects.equals(resourcePart, that.resourcePart);
  }

  @Override
  public int hashCode() {
    return Objects.hash(localPart, domainPart, resourcePart);
  }
}