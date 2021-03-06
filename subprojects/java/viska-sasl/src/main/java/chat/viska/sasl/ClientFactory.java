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

package chat.viska.sasl;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Factory to instantiate {@link Client}s from
 * <a href="https://datatracker.ietf.org/doc/rfc4422">SASL</a> Mechanism names.
 */
public class ClientFactory {

  private final List<String> mechanisms;

  @Nullable
  public static Client newClient(final String mechanism,
                                 final String authnId,
                                 final String authzId,
                                 final CredentialRetriever retriever) {
    if (mechanism.startsWith("SCRAM-")) {
      try {
        return new ScramClient(
            new ScramMechanism(mechanism.substring(6)),
            authnId,
            authzId,
            retriever
        );
      } catch (NoSuchAlgorithmException ex) {
        return null;
      }
    } else {
      return null;
    }
  }

  public ClientFactory(final List<String> mechanisms) {
    this.mechanisms = new ArrayList<>(mechanisms);
  }

  @Nullable
  public Client newClient(List<String> mechanisms,
                          String authnId,
                          String authzId,
                          CredentialRetriever retriever) {
    for (String mech : this.mechanisms) {
      if (mechanisms.contains(mech)) {
        return newClient(mech, authnId, authzId, retriever);
      }
    }
    return null;
  }

  public List<String> getPreferredMechanisms() {
    return new ArrayList<>(mechanisms);
  }
}