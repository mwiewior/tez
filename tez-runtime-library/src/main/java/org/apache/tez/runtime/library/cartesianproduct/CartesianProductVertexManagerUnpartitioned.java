/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tez.runtime.library.cartesianproduct;

import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import org.apache.tez.dag.api.EdgeManagerPluginDescriptor;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.dag.api.VertexManagerPluginContext;
import org.apache.tez.dag.api.VertexManagerPluginContext.ScheduleTaskRequest;
import org.apache.tez.dag.api.event.VertexState;
import org.apache.tez.dag.api.event.VertexStateUpdate;
import org.apache.tez.runtime.api.TaskAttemptIdentifier;
import org.apache.tez.runtime.api.events.VertexManagerEvent;
import org.apache.tez.runtime.library.shuffle.impl.ShuffleUserPayloads.VertexManagerEventPayloadProto;
import org.apache.tez.runtime.library.utils.Grouper;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.apache.tez.dag.api.EdgeProperty.DataMovementType.CUSTOM;
import static org.apache.tez.runtime.library.cartesianproduct.CartesianProductUserPayload.CartesianProductConfigProto;

class CartesianProductVertexManagerUnpartitioned extends CartesianProductVertexManagerReal {
  private static final Logger LOG =
    org.slf4j.LoggerFactory.getLogger(CartesianProductVertexManagerUnpartitioned.class);

  List<String> sourceVertices;
  private int parallelism = 1;
  private boolean vertexReconfigured = false;
  private boolean vertexStarted = false;
  private boolean vertexStartSchedule = false;
  private int numCPSrcNotInConfigureState = 0;
  private int numBroadcastSrcNotInRunningState = 0;
  private int[] numTasks;

  private Queue<TaskAttemptIdentifier> completedSrcTaskToProcess = new LinkedList<>();
  private Map<String, RoaringBitmap> sourceTaskCompleted = new HashMap<>();
  private RoaringBitmap scheduledTasks = new RoaringBitmap();
  private CartesianProductConfig config;

  /* auto reduce related */
  private int[] numGroups;
  private Set<String> vertexSentVME = new HashSet<>();
  private long[] vertexOutputBytes;
  private int[] numVertexManagerEventsReceived;
  private long desiredBytesPerGroup;
  private boolean enableGrouping;
  private Grouper grouper = new Grouper();

  public CartesianProductVertexManagerUnpartitioned(VertexManagerPluginContext context) {
    super(context);
  }

  @Override
  public void initialize(CartesianProductVertexManagerConfig config) throws Exception {
    sourceVertices = config.getSourceVertices();
    numTasks = new int[sourceVertices.size()];
    numGroups = new int[sourceVertices.size()];
    vertexOutputBytes = new long[sourceVertices.size()];
    numVertexManagerEventsReceived = new int[sourceVertices.size()];

    enableGrouping = config.isEnableAutoGrouping();
    desiredBytesPerGroup = config.getDesiredBytesPerGroup();

    for (String vertex : sourceVertices) {
      sourceTaskCompleted.put(vertex, new RoaringBitmap());
    }

    for (String vertex : getContext().getInputVertexEdgeProperties().keySet()) {
      if (sourceVertices.indexOf(vertex) != -1) {
        sourceTaskCompleted.put(vertex, new RoaringBitmap());
        getContext().registerForVertexStateUpdates(vertex, EnumSet.of(VertexState.CONFIGURED));
        numCPSrcNotInConfigureState++;
      } else {
        getContext().registerForVertexStateUpdates(vertex, EnumSet.of(VertexState.RUNNING));
        numBroadcastSrcNotInRunningState++;
      }
    }
    this.config = config;
    getContext().vertexReconfigurationPlanned();
  }

  @Override
  public synchronized void onVertexStarted(List<TaskAttemptIdentifier> completions)
    throws Exception {
    vertexStarted = true;
    if (completions != null) {
      for (TaskAttemptIdentifier attempt : completions) {
        addCompletedSrcTaskToProcess(attempt);
      }
    }
    tryScheduleTasks();
  }

  @Override
  public synchronized void onVertexStateUpdated(VertexStateUpdate stateUpdate) throws IOException {
    String vertex = stateUpdate.getVertexName();
    VertexState state = stateUpdate.getVertexState();

    if (state == VertexState.CONFIGURED) {
      numTasks[sourceVertices.indexOf(vertex)] = getContext().getVertexNumTasks(vertex);
      numCPSrcNotInConfigureState--;
    } else if (state == VertexState.RUNNING) {
      numBroadcastSrcNotInRunningState--;
    }
    tryScheduleTasks();
  }

  @Override
  public synchronized void onSourceTaskCompleted(TaskAttemptIdentifier attempt) throws Exception {
    addCompletedSrcTaskToProcess(attempt);
    tryScheduleTasks();
  }

  private void addCompletedSrcTaskToProcess(TaskAttemptIdentifier attempt) {
    int taskId = attempt.getTaskIdentifier().getIdentifier();
    String vertex = attempt.getTaskIdentifier().getVertexIdentifier().getName();
    if (sourceVertices.indexOf(vertex) == -1) {
      return;
    }
    if (sourceTaskCompleted.get(vertex).contains(taskId)) {
      return;
    }
    sourceTaskCompleted.get(vertex).add(taskId);
    completedSrcTaskToProcess.add(attempt);
  }

  private boolean tryStartSchedule() {
    if (!vertexReconfigured || !vertexStarted || numBroadcastSrcNotInRunningState > 0) {
      return false;
    }
    for (RoaringBitmap bitmap: sourceTaskCompleted.values()) {
      if (bitmap.isEmpty()) {
        return false;
      }
    }
    vertexStartSchedule = true;
    return true;
  }

  public synchronized void onVertexManagerEventReceived(VertexManagerEvent vmEvent)
    throws IOException {
    /* vmEvent after reconfigure doesn't matter */
    if (vertexReconfigured) {
      return;
    }

    if (vmEvent.getUserPayload() != null) {
      String srcVertex =
        vmEvent.getProducerAttemptIdentifier().getTaskIdentifier().getVertexIdentifier().getName();
      int position = sourceVertices.indexOf(srcVertex);
      // vmEvent from non-cp vertex doesn't matter
      if (position == -1) {
        return;
      }
      VertexManagerEventPayloadProto proto =
        VertexManagerEventPayloadProto.parseFrom(ByteString.copyFrom(vmEvent.getUserPayload()));
      vertexOutputBytes[position] += proto.getOutputSize();
      numVertexManagerEventsReceived[position]++;
      vertexSentVME.add(srcVertex);
    }

    tryScheduleTasks();
  }

  private boolean tryReconfigure() throws IOException {
    if (numCPSrcNotInConfigureState > 0) {
      return false;
    }
    if (enableGrouping) {
      if (vertexSentVME.size() != sourceVertices.size()) {
        return false;
      }
      for (int i = 0; i < vertexOutputBytes.length; i++) {
        if (vertexOutputBytes[i] < desiredBytesPerGroup
          && numVertexManagerEventsReceived[i] < numTasks[i]) {
          return false;
        }
      }
    }

    LOG.info("Start reconfigure, grouping: " + enableGrouping
      + ", group size: " + desiredBytesPerGroup);
    LOG.info("src vertices: " + sourceVertices);
    LOG.info("number of source tasks in each src: " + Arrays.toString(numTasks));
    LOG.info("number of vmEvent from each src: "
      + Arrays.toString(numVertexManagerEventsReceived));
    LOG.info("output stats of each src: " + Arrays.toString(vertexOutputBytes));

    for (int i = 0; i < numTasks.length; i++) {
      if (enableGrouping) {
        vertexOutputBytes[i] =
          vertexOutputBytes[i] * numTasks[i] / numVertexManagerEventsReceived[i];
        int desiredNumGroup =
          (int) ((vertexOutputBytes[i] + desiredBytesPerGroup - 1) / desiredBytesPerGroup);
        numGroups[i] = Math.min(numTasks[i], desiredNumGroup);
      } else {
        numGroups[i] = numTasks[i];
      }
      parallelism *= numGroups[i];
    }

    LOG.info("estimated output size of each src: " + Arrays.toString(vertexOutputBytes));
    LOG.info("number of groups for each src: " + Arrays.toString(numGroups));
    LOG.info("Final parallelism: " + parallelism);

    UserPayload payload = null;
    Map<String, EdgeProperty> edgeProperties = getContext().getInputVertexEdgeProperties();
    Iterator<Map.Entry<String,EdgeProperty>> iter = edgeProperties.entrySet().iterator();
    while (iter.hasNext()) {
      EdgeProperty edgeProperty = iter.next().getValue();
      if (edgeProperty.getDataMovementType() != CUSTOM) {
        iter.remove();
        continue;
      }
      EdgeManagerPluginDescriptor descriptor = edgeProperty.getEdgeManagerDescriptor();
      if (payload == null) {
        CartesianProductConfigProto.Builder builder = CartesianProductConfigProto.newBuilder();
        builder.setIsPartitioned(false).addAllNumTasks(Ints.asList(numTasks))
          .addAllNumGroups(Ints.asList(numGroups)).addAllSourceVertices(config.getSourceVertices());
        payload = UserPayload.create(ByteBuffer.wrap(builder.build().toByteArray()));
      }
      descriptor.setUserPayload(payload);
    }
    getContext().reconfigureVertex(parallelism, null, edgeProperties);
    vertexReconfigured = true;
    getContext().doneReconfiguringVertex();
    return true;
  }

  private void tryScheduleTasks() throws IOException {
    if (!vertexReconfigured && !tryReconfigure()) {
      return;
    }
    if (!vertexStartSchedule && !tryStartSchedule()) {
      return;
    }

    while (!completedSrcTaskToProcess.isEmpty()) {
      scheduledTasksDependOnCompletion(completedSrcTaskToProcess.poll());
    }
  }

  private void scheduledTasksDependOnCompletion(TaskAttemptIdentifier attempt) {
    int taskId = attempt.getTaskIdentifier().getIdentifier();
    String vertex = attempt.getTaskIdentifier().getVertexIdentifier().getName();
    int position = sourceVertices.indexOf(vertex);

    List<ScheduleTaskRequest> requests = new ArrayList<>();
    CartesianProductCombination combination =
      new CartesianProductCombination(numGroups, position);
    grouper.init(numTasks[position], numGroups[position]);
    combination.firstTaskWithFixedPartition(grouper.getGroupId(taskId));
    do {
      List<Integer> list = combination.getCombination();

      if (scheduledTasks.contains(combination.getTaskId())) {
        continue;
      }
      boolean readyToSchedule = true;
      for (int i = 0; i < list.size(); i++) {
        int group = list.get(i);
        grouper.init(numTasks[i], numGroups[i]);
        for (int j = grouper.getFirstTaskInGroup(group); j <= grouper.getLastTaskInGroup(group); j++) {
          if (!sourceTaskCompleted.get(sourceVertices.get(i)).contains(j)) {
            readyToSchedule = false;
            break;
          }
        }
        if (!readyToSchedule) {
          break;
        }
      }

      if (readyToSchedule) {
        requests.add(ScheduleTaskRequest.create(combination.getTaskId(), null));
        scheduledTasks.add(combination.getTaskId());
      }
    } while (combination.nextTaskWithFixedPartition());
    if (!requests.isEmpty()) {
      getContext().scheduleTasks(requests);
    }
  }
}