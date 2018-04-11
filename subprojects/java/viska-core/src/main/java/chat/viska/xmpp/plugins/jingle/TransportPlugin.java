/*
 * Copyright 2018 Kai-Chung Yan (殷啟聰)
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

package chat.viska.xmpp.plugins.jingle;

import chat.viska.xmpp.Plugin;
import org.w3c.dom.Element;

/**
 * Provides support for a Jingle transport method.
 */
public interface TransportPlugin extends Plugin {

  /**
   * Constructs a {@link Transport} instance from a {@code <transport/>} element.
   */
  Transport readTransport(Element transportElement);

  /**
   * Writes a {@link Transport} description into a {@code <transport/>} element.
   */
  void writeTransport(Transport transport, Element transportElement);
}