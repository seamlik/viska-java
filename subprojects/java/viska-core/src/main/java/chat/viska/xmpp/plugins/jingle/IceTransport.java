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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <a href="https://xmpp.org/extensions/xep-0371.html">Jingle ICE transport method</a>.
 */
public class IceTransport {

  public static class Candidate {
  }

  public final List<Candidate> candidates;
  public final String ufrag;
  public final String pwd;

  public IceTransport(final String ufrag, final String pwd, final List<Candidate> candidates) {
    this.ufrag = ufrag;
    this.pwd = pwd;
    this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
  }
}