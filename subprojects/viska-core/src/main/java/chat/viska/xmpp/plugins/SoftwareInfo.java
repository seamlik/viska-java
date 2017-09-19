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

package chat.viska.xmpp.plugins;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Information of an XMPP software. This class is part of
 * <a href="https://xmpp.org/extensions/xep-0092.html">XEP-0092: Software
 * Version</a>.
 */
public class SoftwareInfo {

  private String name;
  private String version;
  private String operatingSystem;

  public SoftwareInfo(final @Nullable String name,
                      final @Nullable String version,
                      final @Nullable String operatingSystem) {
    this.name = name == null ? "" : name;
    this.version = version == null ? "" : version;
    this.operatingSystem = operatingSystem == null ? "" : operatingSystem;
  }

  /**
   * Gets the software name.
   */
  @Nonnull
  public String getName() {
    return name;
  }

  /**
   * Gets the software version.
   */
  @Nonnull
  public String getVersion() {
    return version;
  }

  /**
   * Gets the operating system name.
   */
  @Nonnull
  public String getOperatingSystem() {
    return operatingSystem;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    SoftwareInfo that = (SoftwareInfo) obj;
    return Objects.equals(name, that.name)
        && Objects.equals(version, that.version)
        && Objects.equals(operatingSystem, that.operatingSystem);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, version, operatingSystem);
  }
}