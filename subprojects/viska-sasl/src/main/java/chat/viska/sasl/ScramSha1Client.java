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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;

public class ScramSha1Client extends ScramClient {

  public ScramSha1Client(final String username, final String password)
      throws NoSuchAlgorithmException{
    super(
        new ScramMechanism(
            MessageDigest.getInstance("SHA-1"),
            Mac.getInstance("HmacSHA1")
        ),
        username,
        password
    );
  }

  public ScramSha1Client(final String username,
                         final byte[] saltedPassword,
                         final byte[] salt,
                         final int iteration) throws NoSuchAlgorithmException {
    super(
        new ScramMechanism(
            MessageDigest.getInstance("SHA-1"),
            Mac.getInstance("HmacSHA1")
        ),
        username,
        saltedPassword,
        salt,
        iteration
    );
  }
}