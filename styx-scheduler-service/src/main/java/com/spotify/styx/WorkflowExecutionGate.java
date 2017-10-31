/*
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2017 Spotify AB
 * --
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
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx;


import com.spotify.styx.model.ExecutionDescription;
import com.spotify.styx.model.WorkflowConfiguration;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.RunState;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface WorkflowExecutionGate {

  CompletableFuture<List<String>> NO_MISSING_DEPS =
      CompletableFuture.completedFuture(Collections.emptyList());

  /**
   * Check for known missing dependencies of a workflow instance. This method is called before the
   * workflow instance is dequeued, in order to provide an opportunity to perform cheaper dependency
   * lookup. Implementations of this method should be careful to consider whether dependency
   * information gathered e.g. from previous executions has been invalidated by docker image and/or
   * args changes, etc. This method must not block.
   *
   * @param instance The workflow instance to check dependencies for.
   * @param configuration The {@link WorkflowConfiguration} of the workflow instance.
   * @param runState The current {@link RunState} of the workflow instance. Contains information
   *     about the previous execution, like exit code and {@link ExecutionDescription}.
   * @return A list of missing dependency resource identifiers, e.g. uris.
   */
  CompletableFuture<List<String>> missingDependencies(
      WorkflowInstance instance, WorkflowConfiguration configuration, RunState runState);

  /**
   * A nop {@link WorkflowExecutionGate} that never returns any missing dependencies. I.e., the
   * execution can always proceed.
   */
  WorkflowExecutionGate NOOP = (wfi, state, storage) -> NO_MISSING_DEPS;
}
