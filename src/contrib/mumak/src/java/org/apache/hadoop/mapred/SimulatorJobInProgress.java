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
package org.apache.hadoop.mapred;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapred.JobClient.RawSplit;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.tools.rumen.JobStory;
import org.apache.hadoop.tools.rumen.Pre21JobHistoryConstants;
import org.apache.hadoop.tools.rumen.ReduceTaskAttemptInfo;
import org.apache.hadoop.tools.rumen.TaskAttemptInfo;

public class SimulatorJobInProgress extends JobInProgress {
  static final Log LOG = LogFactory.getLog(SimulatorJobInProgress.class);
  
  // JobStory that contains all information that should be read from the
  // cache
  private final JobStory jobStory;

  RawSplit[] splits;

  @SuppressWarnings("deprecation")
  public SimulatorJobInProgress(JobID jobid, JobTracker jobtracker,
      JobConf default_conf, JobStory jobStory) {
    super(jobid, jobStory.getJobConf(), jobtracker);
    // jobSetupCleanupNeeded set to false in parent cstr, though
    // default is true

    restartCount = 0;
    jobSetupCleanupNeeded = false;
      
    this.memoryPerMap = conf.getMemoryForMapTask();
    this.memoryPerReduce = conf.getMemoryForReduceTask();
    this.maxTaskFailuresPerTracker = conf.getMaxTaskFailuresPerTracker();
    
    this.jobId = jobid;
    String url = "http://" + jobtracker.getJobTrackerMachine() + ":"
        + jobtracker.getInfoPort() + "/jobdetails.jsp?jobid=" + jobid;
    this.jobtracker = jobtracker;
    this.conf = jobStory.getJobConf();
    this.priority = conf.getJobPriority();
    Path jobDir = jobtracker.getSystemDirectoryForJob(jobid);
    this.jobFile = new Path(jobDir, "job.xml");
    this.status = new JobStatus(jobid, 0.0f, 0.0f, 0.0f, 0.0f, JobStatus.PREP,
            priority, conf.getUser());
    this.profile = new JobProfile(jobStory.getUser(), jobid, this.jobFile
        .toString(), url, jobStory.getName(), conf.getQueueName());
    this.startTime = JobTracker.getClock().getTime();
    status.setStartTime(startTime);
    this.resourceEstimator = new ResourceEstimator(this);

    this.numMapTasks = jobStory.getNumberMaps();
    this.numReduceTasks = jobStory.getNumberReduces();
    this.taskCompletionEvents = new ArrayList<TaskCompletionEvent>(numMapTasks
        + numReduceTasks + 10);

    this.mapFailuresPercent = conf.getMaxMapTaskFailuresPercent();
    this.reduceFailuresPercent = conf.getMaxReduceTaskFailuresPercent();
    MetricsContext metricsContext = MetricsUtil.getContext("mapred");
    this.jobMetrics = MetricsUtil.createRecord(metricsContext, "job");
    this.jobMetrics.setTag("user", conf.getUser());
    this.jobMetrics.setTag("sessionId", conf.getSessionId());
    this.jobMetrics.setTag("jobName", conf.getJobName());
    this.jobMetrics.setTag("jobId", jobid.toString());

    this.maxLevel = jobtracker.getNumTaskCacheLevels();
    this.anyCacheLevel = this.maxLevel + 1;
    this.nonLocalMaps = new LinkedList<TaskInProgress>();
    this.nonLocalRunningMaps = new LinkedHashSet<TaskInProgress>();
    this.runningMapCache = new IdentityHashMap<Node, Set<TaskInProgress>>();
    this.nonRunningReduces = new LinkedList<TaskInProgress>();
    this.runningReduces = new LinkedHashSet<TaskInProgress>();
    this.slowTaskThreshold = Math.max(0.0f, conf.getFloat(
        "mapred.speculative.execution.slowTaskThreshold", 1.0f));
    this.speculativeCap = conf.getFloat(
        "mapred.speculative.execution.speculativeCap", 0.1f);
    this.slowNodeThreshold = conf.getFloat(
        "mapred.speculative.execution.slowNodeThreshold", 1.0f);

    this.jobStory = jobStory;
//    this.jobHistory = this.jobtracker.getJobHistory();
  }

  // for initTasks, update information from JobStory object
  @Override
  public synchronized void initTasks() throws IOException {
    boolean loggingEnabled = LOG.isDebugEnabled();
    if (loggingEnabled) {
      LOG.debug("(initTasks@SJIP) Starting Initialization for " + jobId);
    }
    numMapTasks = jobStory.getNumberMaps();
    numReduceTasks = jobStory.getNumberReduces();

    JobHistory.JobInfo.logSubmitted(getJobID(), conf, jobFile.toString(),
                                    this.startTime, hasRestarted());
    if (loggingEnabled) {
      LOG.debug("(initTasks@SJIP) Logged to job history for " + jobId);
    }

//    checkTaskLimits();

    if (loggingEnabled) {
      LOG.debug("(initTasks@SJIP) Checked task limits for " + jobId);
    }

    final String jobFile = "default";
    splits = getRawSplits(jobStory.getInputSplits());
    if (loggingEnabled) {
      LOG.debug("(initTasks@SJIP) Created splits for job = " + jobId
          + " number of splits = " + splits.length);
    }

//    createMapTasks(jobFile, splits);

    numMapTasks = splits.length;
    maps = new TaskInProgress[numMapTasks];
    for (int i=0; i < numMapTasks; ++i) {
      inputLength += splits[i].getDataLength();
      maps[i] = new TaskInProgress(jobId, jobFile,
                                   splits[i],
                                   conf, this, i, numSlotsPerMap);
    }    
    if (numMapTasks > 0) {
      nonRunningMapCache = createCache(splits, maxLevel);
      if (loggingEnabled) {
        LOG.debug("initTasks:numMaps=" + numMapTasks
            + " Size of nonRunningMapCache=" + nonRunningMapCache.size()
            + " for " + jobId);
      }
    }

    // set the launch time
    this.launchTime = JobTracker.getClock().getTime();

//    createReduceTasks(jobFile);

    //   
    // Create reduce tasks
    //   
    this.reduces = new TaskInProgress[numReduceTasks];
    for (int i = 0; i < numReduceTasks; i++) {
      reduces[i] = new TaskInProgress(jobId, jobFile,
                                      numMapTasks, i,
                                      conf, this, numSlotsPerReduce);
      nonRunningReduces.add(reduces[i]);
    }    

    // Calculate the minimum number of maps to be complete before
    // we should start scheduling reduces
    completedMapsForReduceSlowstart = (int) Math.ceil((conf.getFloat(
        "mapred.reduce.slowstart." + "completed.maps",
        DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART) * numMapTasks));

    tasksInited.set(true);
    if (loggingEnabled) {
      LOG.debug("Initializing job, nowstatus = "
          + JobStatus.getJobRunState(getStatus().getRunState()));
    }
    setupComplete();

    if (loggingEnabled) {
      LOG.debug("Initializing job, inited-status = "
          + JobStatus.getJobRunState(getStatus().getRunState()));
    }
  }

  RawSplit[] getRawSplits(InputSplit[] splits) throws IOException {
    if (splits == null || splits.length != numMapTasks) {
      throw new IllegalArgumentException("Input split size mismatch: expected="
          + numMapTasks + ", actual=" + ((splits == null) ? -1 : splits.length));
    }

    RawSplit rawSplits[] = new RawSplit[splits.length];
    for (int i = 0; i < splits.length; i++) {
      try {
        rawSplits[i] = new RawSplit();
        rawSplits[i].setClassName(splits[i].getClass().getName());
        rawSplits[i].setDataLength(splits[i].getLength());
        rawSplits[i].setLocations(splits[i].getLocations());
      } catch (InterruptedException ie) {
        throw new IOException(ie);
      }
    }

    return rawSplits;
  }

  /**
   * Given the map taskAttemptID, returns the TaskAttemptInfo. Deconstructs the
   * map's taskAttemptID and looks up the jobStory with the parts taskType, id
   * of task, id of task attempt.
   * 
   * @param taskTracker
   *          tasktracker
   * @param taskAttemptID
   *          task-attempt
   * @return TaskAttemptInfo for the map task-attempt
   */
  @SuppressWarnings("deprecation")
  private synchronized TaskAttemptInfo getMapTaskAttemptInfo(
      TaskTracker taskTracker, TaskAttemptID taskAttemptID) {
    assert (taskAttemptID.isMap());

    JobID jobid = (JobID) taskAttemptID.getJobID();
    assert (jobid == getJobID());

    // Get splits for the TaskAttempt
    RawSplit split = splits[taskAttemptID.getTaskID().getId()];
    int locality = getClosestLocality(taskTracker, split);

    TaskID taskId = taskAttemptID.getTaskID();
    if (!taskId.isMap()) {
      assert false : "Task " + taskId + " is not MAP :"; 
    }
    
    TaskAttemptInfo taskAttemptInfo = jobStory.getMapTaskAttemptInfoAdjusted(
        taskId.getId(), taskAttemptID.getId(), locality);

    if (LOG.isDebugEnabled()) {
      LOG.debug("get an attempt: "
          + taskAttemptID.toString()
          + ", state="
          + taskAttemptInfo.getRunState()
          + ", runtime="
          + ((taskId.isMap()) ? taskAttemptInfo.getRuntime()
              : ((ReduceTaskAttemptInfo) taskAttemptInfo).getReduceRuntime()));
    }
    return taskAttemptInfo;
  }

  private int getClosestLocality(TaskTracker taskTracker, RawSplit split) {
    int locality = 2;

    Node taskTrackerNode = jobtracker
        .getNode(taskTracker.getStatus().getHost());
    if (taskTrackerNode == null) {
      throw new IllegalArgumentException(
          "Cannot determine network topology node for TaskTracker "
              + taskTracker.getTrackerName());
    }
    for (String location : split.getLocations()) {
      Node dataNode = jobtracker.getNode(location);
      if (dataNode == null) {
        throw new IllegalArgumentException(
            "Cannot determine network topology node for split location "
                + location);
      }
      locality = Math.min(locality, jobtracker.clusterMap.getDistance(
          taskTrackerNode, dataNode));
    }
    return locality;
  }

  @SuppressWarnings("deprecation")
  public TaskAttemptInfo getTaskAttemptInfo(TaskTracker taskTracker,
      TaskAttemptID taskAttemptId) {
    JobID jobid = (JobID) taskAttemptId.getJobID();
    assert (jobid == getJobID());

    return (taskAttemptId.isMap()) ? getMapTaskAttemptInfo(
        taskTracker, taskAttemptId)
        : getReduceTaskAttemptInfo(taskTracker, taskAttemptId);
  }

  /**
   * Given the reduce taskAttemptID, returns the TaskAttemptInfo. Deconstructs
   * the reduce taskAttemptID and looks up the jobStory with the parts taskType,
   * id of task, id of task attempt.
   * 
   * @param taskTracker
   *          tasktracker
   * @param taskAttemptID
   *          task-attempt
   * @return TaskAttemptInfo for the reduce task-attempt
   */
  private TaskAttemptInfo getReduceTaskAttemptInfo(TaskTracker taskTracker,
      TaskAttemptID taskAttemptID) {
    assert (!taskAttemptID.isMap());
    TaskID taskId = taskAttemptID.getTaskID();
    TaskType taskType;
    if (taskAttemptID.isMap()) {
      taskType = TaskType.MAP;
    } else {
      taskType = TaskType.REDUCE;
    }

    TaskAttemptInfo taskAttemptInfo = jobStory.getTaskAttemptInfo(taskType,
        taskId.getId(), taskAttemptID.getId());
    if (LOG.isDebugEnabled()) {
      LOG.debug("get an attempt: "
          + taskAttemptID.toString()
          + ", state="
          + taskAttemptInfo.getRunState()
          + ", runtime="
          + ((taskAttemptID.isMap()) ? taskAttemptInfo.getRuntime()
              : ((ReduceTaskAttemptInfo) taskAttemptInfo).getReduceRuntime()));
    }
    return taskAttemptInfo;
  }
}
