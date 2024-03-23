/*
 * Copyright 2024 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.topology;


import com.couchbase.client.core.annotation.Stability;

import java.util.List;

import static com.couchbase.client.core.util.CbCollections.listOf;
import static java.util.Objects.requireNonNull;

@Stability.Internal
public enum ClusterCapability {
  N1QL_ENHANCED_PREPARED_STATEMENTS("n1ql", "enhancedPreparedStatements"),
  N1QL_READ_FROM_REPLICA("n1ql", "readFromReplica"),

  SEARCH_VECTOR("search", "vectorSearch"),
  SEARCH_SCOPED("search", "scopedSearchIndex"),
  ;

  private static final List<ClusterCapability> VALUES = listOf(values());

  private final String namespace;
  private final String wireName;

  ClusterCapability(String namespace, String wireName) {
    this.namespace = requireNonNull(namespace);
    this.wireName = requireNonNull(wireName);
  }

  public static List<ClusterCapability> valueList() {
    return VALUES;
  }

  public String namespace() {
    return namespace;
  }

  public String wireName() {
    return wireName;
  }

}
