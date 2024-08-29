/*
 * Copyright (c) 2024 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.columnar.fit.core.util;

import java.time.Instant;

public class TimeUtil {
  public static com.google.protobuf.Timestamp getTimeNow() {
    Instant now = Instant.now();

    return com.google.protobuf.Timestamp.newBuilder()
      .setSeconds(now.getEpochSecond())
      .setNanos(now.getNano())
      .build();
  }
}