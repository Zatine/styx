/*-
 * -\-\-
 * Spotify Styx API Service
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

package com.spotify.styx.api;

import static com.spotify.apollo.test.unit.ResponseMatchers.hasHeader;
import static com.spotify.apollo.test.unit.ResponseMatchers.hasNoPayload;
import static com.spotify.apollo.test.unit.ResponseMatchers.hasStatus;
import static com.spotify.apollo.test.unit.StatusTypeMatchers.withCode;
import static com.spotify.apollo.test.unit.StatusTypeMatchers.withReasonPhrase;
import static com.spotify.styx.api.JsonMatchers.assertJson;
import static com.spotify.styx.api.JsonMatchers.assertNoJson;
import static com.spotify.styx.model.SequenceEvent.create;
import static com.spotify.styx.model.WorkflowState.patchDockerImage;
import static com.spotify.styx.serialization.Json.deserialize;
import static com.spotify.styx.serialization.Json.serialize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyQuery;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.testing.LocalDatastoreHelper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.spotify.apollo.Environment;
import com.spotify.apollo.Response;
import com.spotify.apollo.Status;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.Schedule;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowConfiguration;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.state.Trigger;
import com.spotify.styx.storage.AggregateStorage;
import com.spotify.styx.storage.BigtableMocker;
import com.spotify.styx.storage.BigtableStorage;
import com.spotify.styx.util.DockerImageValidator;
import com.spotify.styx.util.TriggerUtil;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import okio.ByteString;
import org.apache.hadoop.hbase.client.Connection;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class WorkflowResourceTest extends VersionedApiTest {

  private static final String SCHEDULER_BASE = "http://localhost:12345";

  private static LocalDatastoreHelper localDatastore;

  private Datastore datastore = localDatastore.getOptions().getService();
  private Connection bigtable = setupBigTableMockTable();
  private AggregateStorage storage = new AggregateStorage(bigtable, datastore, Duration.ZERO);

  public WorkflowResourceTest(Api.Version version) {
    super("/workflows", version, "workflow-test");
    MockitoAnnotations.initMocks(this);
  }

  private static final WorkflowConfiguration WORKFLOW_CONFIGURATION =
      WorkflowConfiguration.builder()
          .id("bar")
          .schedule(Schedule.DAYS)
          .build();

  private static final WorkflowConfiguration WORKFLOW_CONFIGURATION_WITH_IMAGE =
      WorkflowConfiguration.builder()
          .id("bar")
          .schedule(Schedule.DAYS)
          .dockerImage("bar-dummy:dummy")
          .build();

  private static final Workflow WORKFLOW =
      Workflow.create("foo", WORKFLOW_CONFIGURATION);

  private static final Workflow WORKFLOW_WITH_IMAGE =
      Workflow.create("foo", WORKFLOW_CONFIGURATION_WITH_IMAGE);

  private static final Trigger NATURAL_TRIGGER = Trigger.natural();
  private static final Trigger BACKFILL_TRIGGER = Trigger.backfill("backfill-1");

  private static final ByteString STATEPAYLOAD_FULL =
      ByteString.encodeUtf8("{\"enabled\":\"true\", "
                            + "\"next_natural_trigger\":\"2016-08-10T07:00:01Z\", "
                            + "\"next_natural_offset_trigger\":\"2016-08-10T08:00:01Z\"}");

  private static final ByteString STATEPAYLOAD_ENABLED =
      ByteString.encodeUtf8("{\"enabled\":\"true\"}");

  private static final ByteString STATEPAYLOAD_IMAGE =
      ByteString.encodeUtf8("{\"docker_image\":\"berry:image\"}");

  private static final ByteString STATEPAYLOAD_BAD =
      ByteString.encodeUtf8("{\"The BAD\"}");

  private static final ByteString STATEPAYLOAD_OTHER_FIELD =
      ByteString.encodeUtf8("{\"enabled\":\"true\",\"other_field\":\"ignored\"}");

  @Mock DockerImageValidator dockerImageValidator;

  @Override
  protected void init(Environment environment) {
    when(dockerImageValidator.validateImageReference(Mockito.anyString())).thenReturn(Collections.emptyList());
    WorkflowResource workflowResource = new WorkflowResource(storage, SCHEDULER_BASE, dockerImageValidator,
                                                             environment.client());

    environment.routingEngine()
        .registerRoutes(Api.withCommonMiddleware(
            workflowResource.routes()));
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    localDatastore = LocalDatastoreHelper.create(1.0); // 100% global consistency
    localDatastore.start();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (localDatastore != null) {
      try {
        localDatastore.stop(org.threeten.bp.Duration.ofSeconds(30));
      } catch (Throwable e) {
        e.printStackTrace();
      }
    }
  }

  @Before
  public void setUp() throws Exception {
    storage.storeWorkflow(WORKFLOW);
  }

  @After
  public void tearDown() throws Exception {
    // clear datastore after each test
    Datastore datastore = localDatastore.getOptions().getService();
    KeyQuery query = Query.newKeyQueryBuilder().build();
    final QueryResults<Key> keys = datastore.run(query);
    while (keys.hasNext()) {
      datastore.delete(keys.next());
    }
    serviceHelper.stubClient().clear();
  }

  @Test
  public void shouldSucceedWithFullPatchStatePerWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/state")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertJson(response, "enabled", equalTo(false));
    assertNoJson(response, "docker_image");
    assertNoJson(response, "commit_sha");

    response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/bar/state"),
                                            STATEPAYLOAD_FULL));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertThat(response, hasHeader("Content-Type", equalTo("application/json")));
    assertJson(response, "enabled", equalTo(true));
    assertJson(response, "next_natural_trigger", equalTo("2016-08-10T07:00:01Z"));
    assertJson(response, "next_natural_offset_trigger", equalTo("2016-08-10T08:00:01Z"));

    assertThat(storage.enabled(WORKFLOW.id()), is(true));
    assertThat(storage.workflowState(WORKFLOW.id()).nextNaturalTrigger().get().toString(),
               equalTo("2016-08-10T07:00:01Z"));
    assertThat(storage.workflowState(WORKFLOW.id()).nextNaturalOffsetTrigger().get().toString(),
               equalTo("2016-08-10T08:00:01Z"));
  }

  @Test
  public void shouldSucceedWithEnabledPatchStatePerWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    storage.patchState(WORKFLOW.id(), patchDockerImage("preset:image"));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/bar/state"),
                                            STATEPAYLOAD_ENABLED));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertThat(response, hasHeader("Content-Type", equalTo("application/json")));
    assertJson(response, "enabled", equalTo(true));
    assertJson(response, "docker_image", equalTo("preset:image"));

    assertThat(storage.enabled(WORKFLOW.id()), is(true));
    assertThat(storage.getDockerImage(WORKFLOW.id()), is(Optional.of("preset:image")));
  }

  @Test
  public void shouldSucceedWhenStatePayloadWithOtherFieldsIsSent() throws Exception {
    sinceVersion(Api.Version.V3);

    storage.patchState(WORKFLOW.id(), patchDockerImage("preset:image"));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/bar/state"),
                                            STATEPAYLOAD_OTHER_FIELD));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertThat(response, hasHeader("Content-Type", equalTo("application/json")));
    assertJson(response, "enabled", equalTo(true));

    assertThat(storage.enabled(WORKFLOW.id()), is(true));
  }

  @Test
  public void shouldNotPatchStatePerComponent() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/state"),
                                            STATEPAYLOAD_IMAGE));

    assertThat(response, hasStatus(withCode(Status.METHOD_NOT_ALLOWED)));
  }

  @Test
  public void shouldReturnCurrentWorkflowState() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/state")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertJson(response, "enabled", equalTo(false));
    assertNoJson(response, "docker_image");
    assertNoJson(response, "commit_sha");

    storage.patchState(WORKFLOW.id(),
                       WorkflowState.builder().enabled(true).dockerImage("tina:ranic")
                           .commitSha("470a229b49a14e7682af2abfdac3b881a8aacdf9").build());

    response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/state")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertJson(response, "enabled", equalTo(true));
    assertJson(response, "docker_image", equalTo("tina:ranic"));
    assertJson(response, "commit_sha", equalTo("470a229b49a14e7682af2abfdac3b881a8aacdf9"));
  }

  @Test
  public void shouldReturnBadRequestWhenMalformedStatePayloadIsSent() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/bar/state"),
                                            STATEPAYLOAD_BAD));

    assertThat(response, hasStatus(withCode(Status.BAD_REQUEST)));
    assertThat(response, hasNoPayload());
    assertThat(response, hasStatus(withReasonPhrase(equalTo("Invalid payload."))));
  }

  @Test
  public void shouldReturnBadRequestWhenNoPayloadIsSent() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("PATCH", path("/foo/bar/state")));

    assertThat(response, hasStatus(withCode(Status.BAD_REQUEST)));
    assertThat(response, hasNoPayload());
    assertThat(response, hasStatus(withReasonPhrase(equalTo("Missing payload."))));
  }

  @Test
  public void shouldReturnWorkflowInstancesData() throws Exception {
    sinceVersion(Api.Version.V3);

    WorkflowInstance wfi = WorkflowInstance.create(WORKFLOW.id(), "2016-08-10");
    storage.writeEvent(create(Event.triggerExecution(wfi, NATURAL_TRIGGER), 0L, ms("07:00:00")));
    storage.writeEvent(create(Event.created(wfi, "exec", "img"), 1L, ms("07:00:01")));
    storage.writeEvent(create(Event.started(wfi), 2L, ms("07:00:02")));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/instances")));

    assertThat(response, hasStatus(withCode(Status.OK)));

    assertJson(response, "[*]", hasSize(1));
    assertJson(response, "[0].workflow_instance.parameter", is("2016-08-10"));
    assertJson(response, "[0].workflow_instance.workflow_id.component_id", is("foo"));
    assertJson(response, "[0].workflow_instance.workflow_id.id", is("bar"));
    assertJson(response, "[0].triggers", hasSize(1));
    assertJson(response, "[0].triggers.[0].trigger_id", is(TriggerUtil.NATURAL_TRIGGER_ID));
    assertJson(response, "[0].triggers.[0].complete", is(false));
    assertJson(response, "[0].triggers.[0].executions", hasSize(1));
    assertJson(response, "[0].triggers.[0].executions.[0].execution_id", is("exec"));
    assertJson(response, "[0].triggers.[0].executions.[0].docker_image", is("img"));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses", hasSize(2));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses.[0].status", is("SUBMITTED"));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses.[1].status", is("STARTED"));
  }

  @Test
  public void shouldReturnWorkflowRangeOfInstancesData() throws Exception {
    sinceVersion(Api.Version.V3);

    WorkflowInstance wfi = WorkflowInstance.create(WORKFLOW.id(), "2016-08-10");
    storage.writeEvent(create(Event.triggerExecution(wfi, NATURAL_TRIGGER), 0L, ms("07:00:00")));
    storage.writeEvent(create(Event.created(wfi, "exec", "img"), 1L, ms("07:00:01")));
    storage.writeEvent(create(Event.started(wfi), 2L, ms("07:00:02")));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/instances?start=2016-08-10")));

    assertThat(response, hasStatus(withCode(Status.OK)));

    assertJson(response, "[*]", hasSize(1));
    assertJson(response, "[0].workflow_instance.parameter", is("2016-08-10"));
    assertJson(response, "[0].workflow_instance.workflow_id.component_id", is("foo"));
    assertJson(response, "[0].workflow_instance.workflow_id.id", is("bar"));
    assertJson(response, "[0].triggers", hasSize(1));
    assertJson(response, "[0].triggers.[0].trigger_id", is(TriggerUtil.NATURAL_TRIGGER_ID));
    assertJson(response, "[0].triggers.[0].complete", is(false));
    assertJson(response, "[0].triggers.[0].executions", hasSize(1));
    assertJson(response, "[0].triggers.[0].executions.[0].execution_id", is("exec"));
    assertJson(response, "[0].triggers.[0].executions.[0].docker_image", is("img"));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses", hasSize(2));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses.[0].status", is("SUBMITTED"));
    assertJson(response, "[0].triggers.[0].executions.[0].statuses.[1].status", is("STARTED"));
  }

  @Test
  public void shouldReturnWorkflowInstanceData() throws Exception {
    sinceVersion(Api.Version.V3);

    WorkflowInstance wfi = WorkflowInstance.create(WORKFLOW.id(), "2016-08-10");
    storage.writeEvent(create(Event.triggerExecution(wfi, NATURAL_TRIGGER), 0L, ms("07:00:00")));
    storage.writeEvent(create(Event.created(wfi, "exec", "img"), 1L, ms("07:00:01")));
    storage.writeEvent(create(Event.started(wfi), 2L, ms("07:00:02")));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/instances/2016-08-10")));

    assertThat(response, hasStatus(withCode(Status.OK)));

    assertJson(response, "workflow_instance.parameter", is("2016-08-10"));
    assertJson(response, "workflow_instance.workflow_id.component_id", is("foo"));
    assertJson(response, "workflow_instance.workflow_id.id", is("bar"));
    assertJson(response, "triggers", hasSize(1));
    assertJson(response, "triggers.[0].trigger_id", is(TriggerUtil.NATURAL_TRIGGER_ID));
    assertJson(response, "triggers.[0].timestamp", is("2016-08-10T07:00:00Z"));
    assertJson(response, "triggers.[0].complete", is(false));
    assertJson(response, "triggers.[0].executions", hasSize(1));
    assertJson(response, "triggers.[0].executions.[0].execution_id", is("exec"));
    assertJson(response, "triggers.[0].executions.[0].docker_image", is("img"));
    assertJson(response, "triggers.[0].executions.[0].statuses", hasSize(2));
    assertJson(response, "triggers.[0].executions.[0].statuses.[0].status", is("SUBMITTED"));
    assertJson(response, "triggers.[0].executions.[0].statuses.[1].status", is("STARTED"));
    assertJson(response, "triggers.[0].executions.[0].statuses.[0].timestamp",
               is("2016-08-10T07:00:01Z"));
    assertJson(response, "triggers.[0].executions.[0].statuses.[1].timestamp",
               is("2016-08-10T07:00:02Z"));
  }

  @Test
  public void shouldReturnWorkflowInstanceDataBackfill() throws Exception {
    sinceVersion(Api.Version.V3);

    WorkflowInstance wfi = WorkflowInstance.create(WORKFLOW.id(), "2016-08-10");
    storage.writeEvent(create(Event.triggerExecution(wfi, BACKFILL_TRIGGER), 0L, ms("07:00:00")));

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("GET", path("/foo/bar/instances/2016-08-10")));

    assertThat(response, hasStatus(withCode(Status.OK)));

    assertJson(response, "workflow_instance.parameter", is("2016-08-10"));
    assertJson(response, "workflow_instance.workflow_id.component_id", is("foo"));
    assertJson(response, "workflow_instance.workflow_id.id", is("bar"));
    assertJson(response, "triggers", hasSize(1));
    assertJson(response, "triggers.[0].trigger_id", is("backfill-1"));
    assertJson(response, "triggers.[0].timestamp", is("2016-08-10T07:00:00Z"));
    assertJson(response, "triggers.[0].complete", is(false));
  }

  @Test
  public void shouldPaginateWorkflowInstancesData() throws Exception {
    sinceVersion(Api.Version.V3);

    WorkflowInstance wfi1 = WorkflowInstance.create(WORKFLOW.id(), "2016-08-11");
    WorkflowInstance wfi2 = WorkflowInstance.create(WORKFLOW.id(), "2016-08-12");
    WorkflowInstance wfi3 = WorkflowInstance.create(WORKFLOW.id(), "2016-08-13");
    storage.writeEvent(create(Event.triggerExecution(wfi1, NATURAL_TRIGGER), 0L, ms("07:00:00")));
    storage.writeEvent(create(Event.triggerExecution(wfi2, NATURAL_TRIGGER), 0L, ms("07:00:00")));
    storage.writeEvent(create(Event.triggerExecution(wfi3, NATURAL_TRIGGER), 0L, ms("07:00:00")));

    Response<ByteString> response = awaitResponse(
        serviceHelper.request("GET", path("/foo/bar/instances?offset=2016-08-12&limit=1")));

    assertThat(response, hasStatus(withCode(Status.OK)));

    assertJson(response, "[*]", hasSize(1));
    assertJson(response, "[0].workflow_instance.parameter", is("2016-08-12"));
  }

  @Test
  public void shouldReturnBadRequestWhenNoPayloadIsSentWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("POST", path("/foo")));

    assertThat(response, hasStatus(withCode(Status.BAD_REQUEST)));
    assertThat(response, hasNoPayload());
    assertThat(response, hasStatus(withReasonPhrase(equalTo("Missing payload."))));
  }

  @Test
  public void shouldReturnBadRequestWhenMalformedStatePayloadIsSentWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    Response<ByteString> response =
        awaitResponse(serviceHelper.request("POST", path("/foo"),
                                            STATEPAYLOAD_BAD));

    assertThat(response, hasStatus(withCode(Status.BAD_REQUEST)));
    assertThat(response, hasNoPayload());
    assertThat(response, hasStatus(withReasonPhrase(equalTo("Invalid payload."))));
  }

  @Test
  public void shouldReturnOkWhenSchedulerReturnsSuccessWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    serviceHelper.stubClient()
        .respond(Response.forPayload(serialize(WORKFLOW_WITH_IMAGE)))
        .to(SCHEDULER_BASE + "/api/v0/workflows/foo");

    Response<ByteString> response =
        awaitResponse(
            serviceHelper
                .request("POST", path("/foo"), serialize(WORKFLOW_CONFIGURATION_WITH_IMAGE)));

    verify(dockerImageValidator).validateImageReference(WORKFLOW_CONFIGURATION_WITH_IMAGE.dockerImage().get());

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertThat(deserialize(response.payload().get(), Workflow.class), equalTo(WORKFLOW_WITH_IMAGE));
  }

  @Test
  public void shouldReturnErrorMessageWhenSchedulerFailsWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    serviceHelper.stubClient()
        .respond(Response.forStatus(Status.SERVICE_UNAVAILABLE))
        .to(SCHEDULER_BASE + "/api/v0/workflows/foo");

    Response<ByteString> response =
        awaitResponse(
            serviceHelper
                .request("POST", path("/foo"), serialize(WORKFLOW_CONFIGURATION_WITH_IMAGE)));

    verify(dockerImageValidator).validateImageReference(WORKFLOW_CONFIGURATION_WITH_IMAGE.dockerImage().get());

    assertThat(response, hasStatus(withCode(Status.SERVICE_UNAVAILABLE)));
    assertThat(response, hasNoPayload());
  }

  @Test
  public void shouldForwardInternalResponseForDeleteWorkflow() throws Exception {
    sinceVersion(Api.Version.V3);

    serviceHelper.stubClient()
        .respond(Response.forStatus(Status.OK))
        .to(SCHEDULER_BASE + "/api/v0/workflows/foo/bar");

    Response<ByteString> response =
        awaitResponse(
            serviceHelper.request("DELETE", path("/foo/bar")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertThat(response, hasNoPayload());
  }

  @Test
  public void shouldFailInvalidWorkflowImageWithoutForwarding() throws Exception {
    sinceVersion(Api.Version.V3);

    when(dockerImageValidator.validateImageReference(any())).thenReturn(ImmutableList.of("bad", "image"));

    Response<ByteString> response = awaitResponse(serviceHelper
        .request("POST", path("/foo"), serialize(WORKFLOW_CONFIGURATION_WITH_IMAGE)));

    verify(dockerImageValidator).validateImageReference(WORKFLOW_CONFIGURATION_WITH_IMAGE.dockerImage().get());

    assertThat(serviceHelper.stubClient().sentRequests(), is(empty()));

    assertThat(response, hasStatus(withCode(Status.BAD_REQUEST)));
  }

  @Test
  public void shouldReturnWorkflows() throws Exception {
    sinceVersion(Api.Version.V3);

    storage.storeWorkflow(Workflow.create("other_component", WORKFLOW.configuration()));

    Response<ByteString> response = awaitResponse(
        serviceHelper.request("GET", path("")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertJson(response, "[*]", hasSize(2));
  }

  @Test
  public void shouldReturnWorkflowsInComponent() throws Exception {
    sinceVersion(Api.Version.V3);

    storage.storeWorkflow(Workflow.create("other_component", WORKFLOW.configuration()));

    Response<ByteString> response = awaitResponse(
        serviceHelper.request("GET", path("/foo")));

    assertThat(response, hasStatus(withCode(Status.OK)));
    assertJson(response, "[*]", hasSize(1));
    assertJson(response, "[0].component_id", is("foo"));
  }

  private long ms(String time) {
    return Instant.parse("2016-08-10T" + time + "Z").toEpochMilli();
  }

  private Connection setupBigTableMockTable() {
    Connection bigtable = mock(Connection.class);
    try {
      new BigtableMocker(bigtable)
          .setNumFailures(0)
          .setupTable(BigtableStorage.EVENTS_TABLE_NAME)
          .finalizeMocking();
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
    return bigtable;
  }
}
