/*
 * Copyright (C) 2017 Kai-Chung Yan (殷啟聰)
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

package chat.viska;

import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;
import org.joda.time.Instant;

public abstract class Event {

  private Instant triggerdTime;
  private String message;

  public Event(@Nullable Instant triggerdTime, @Nullable String message) {
    this.triggerdTime = triggerdTime == null ? Instant.now() : triggerdTime;
    this.message = message;
  }

  public @NonNull Instant getTriggeredTime() {
    return triggerdTime;
  }

  public @Nullable String getMessage() {
    return message;
  }
}