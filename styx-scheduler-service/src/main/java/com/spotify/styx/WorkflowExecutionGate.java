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
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.RunState;
import java.util.Collection;
import java.util.Collections;

public interface WorkflowExecutionGate {

  /**
   * Check for known missing dependencies of a workflow instance. This method is called before the workflow container
   * itself is executed, in order to provide an opportunity to perform cheaper dependency lookup. Implementations of
   * this method should be careful to consider whether dependency information gathered e.g. from previous executions has
   * been invalidated by docker image and/or args changes, etc.
   *
   * @param wfi The workflow instance to check dependencies for.
   * @param preState The {@link RunState} of the workflow instance before the current execution. Contains information
   *     about the previous execution, like exit code and {@link ExecutionDescription}.
   * @param executionDescription The {@link ExecutionDescription} of the current execution under consideration.
   * @return A list of missing dependency resource identifiers, e.g. uris.
   */
  Collection<String> missingDependencies(WorkflowInstance wfi, RunState preState,
      ExecutionDescription executionDescription);

  /**
   * A nop {@link WorkflowExecutionGate} that never returns any missing dependencies. I.e., the execution can always
   * proceed.
   */
  WorkflowExecutionGate NOOP = (wfi, state, ed) -> Collections.emptyList();
}
