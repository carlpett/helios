/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.master;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spotify.helios.common.HeliosRuntimeException;
import com.spotify.helios.common.HostNotFoundException;
import com.spotify.helios.common.JobAlreadyDeployedException;
import com.spotify.helios.common.JobDoesNotExistException;
import com.spotify.helios.common.JobExistsException;
import com.spotify.helios.common.JobNotDeployedException;
import com.spotify.helios.common.JobPortAllocationConflictException;
import com.spotify.helios.common.JobStillDeployedException;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.AgentInfo;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.Goal;
import com.spotify.helios.common.descriptors.HostInfo;
import com.spotify.helios.common.descriptors.HostStatus;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.PortMapping;
import com.spotify.helios.common.descriptors.Task;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.protocol.JobStatus;
import com.spotify.helios.common.protocol.TaskStatusEvent;
import com.spotify.helios.servicescommon.coordination.Node;
import com.spotify.helios.servicescommon.coordination.Paths;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClientProvider;
import com.spotify.helios.servicescommon.coordination.ZooKeeperOperation;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.google.common.base.Optional.fromNullable;
import static com.google.common.collect.Lists.reverse;
import static com.spotify.helios.common.descriptors.Descriptor.parse;
import static com.spotify.helios.common.descriptors.Goal.UNDEPLOY;
import static com.spotify.helios.common.descriptors.HostStatus.Status.DOWN;
import static com.spotify.helios.common.descriptors.HostStatus.Status.UP;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.check;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.create;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.delete;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.set;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.apache.zookeeper.KeeperException.NotEmptyException;

public class ZooKeeperMasterModel implements MasterModel {

  public static final class EventComparator implements Comparator<TaskStatusEvent> {

    @Override
    public int compare(TaskStatusEvent arg0, TaskStatusEvent arg1) {
      if (arg1.getTimestamp() > arg0.getTimestamp()) {
        return -1;
      } else if (arg1.getTimestamp() == arg0.getTimestamp()) {
        return 0;
      } else {
        return 1;
      }
    }
  }

  private static final EventComparator EVENT_COMPARATOR = new EventComparator();

  private static final Logger log = LoggerFactory.getLogger(ZooKeeperMasterModel.class);

  public static final Map<JobId, TaskStatus> EMPTY_STATUSES = emptyMap();

  private final ZooKeeperClientProvider provider;

  public ZooKeeperMasterModel(final ZooKeeperClientProvider provider) {
    this.provider = provider;
  }

  @Override
  public void registerHost(final String host) {
    log.debug("registering host: {}", host);
    final ZooKeeperClient client = provider.get("registerHost");
    try {
      // TODO (dano): this code is replicated in AgentRegistrar

      // This would've been nice to do in a transaction but PathChildrenCache ensures paths
      // so we can't know what paths already exist so assembling a suitable transaction is too
      // painful.
      client.ensurePath(Paths.configHost(host));
      client.ensurePath(Paths.configHost(host));
      client.ensurePath(Paths.configHostJobs(host));
      client.ensurePath(Paths.configHostPorts(host));
      client.ensurePath(Paths.statusHost(host));
      client.ensurePath(Paths.statusHostJobs(host));

      // Finish registration by creating the id node last
      client.ensurePath(Paths.configHostId(host));
    } catch (Exception e) {
      throw new HeliosRuntimeException("registering host " + host + " failed", e);
    }
  }

  @Override
  public List<String> listHosts() {
    try {
      // TODO (dano): only return hosts whose agents completed registration (i.e. has agent id nodes)
      return provider.get("listHosts").getChildren(Paths.configHosts());
    } catch (KeeperException.NoNodeException e) {
      return emptyList();
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("listing hosts failed", e);
    }
  }

  @Override
  public List<String> getRunningMasters() {
    final ZooKeeperClient client = provider.get("getRunningMasters");
    try {
      final List<String> masters = client.getChildren(Paths.statusMaster());
      final ImmutableList.Builder<String> upMasters = ImmutableList.builder();
      for (final String master : masters) {
        if (client.exists(Paths.statusMasterUp(master)) != null) {
          upMasters.add(master);
        }
      }
      return upMasters.build();
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("listing masters failed", e);
    }
  }

  @Override
  public void deregisterHost(final String host)
      throws HostNotFoundException, HostStillInUseException {
    try {
      final ZooKeeperClient client = provider.get("deregisterHost");
      final List<String> nodes = reverse(client.listRecursive(Paths.configHost(host)));
      client.transaction(delete(nodes));
    } catch (NotEmptyException e) {
      final HostStatus hostStatus = getHostStatus(host);
      final List<JobId> jobs = hostStatus != null
                               ? ImmutableList.copyOf(hostStatus.getJobs().keySet())
                               : Collections.<JobId>emptyList();
      throw new HostStillInUseException(host, jobs);
    } catch (NoNodeException e) {
      throw new HostNotFoundException(host);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException(e);
    }
  }

  @Override
  public void addJob(final Job job) throws JobExistsException {
    log.debug("adding job: {}", job);
    final JobId id = job.getId();
    try {
      final ZooKeeperClient client = provider.get("addJob");
      client.ensurePath(Paths.historyJob(id));
      client.transaction(create(Paths.configJob(id), job),
                         create(Paths.configJobRefShort(id), id),
                         create(Paths.configJobHosts(id)));
    } catch (final NodeExistsException e) {
      throw new JobExistsException(id.toString());
    } catch (final KeeperException e) {
      throw new HeliosRuntimeException("adding job " + job + " failed", e);
    }
  }

  @Override
  public List<TaskStatusEvent> getJobHistory(final JobId jobId) throws JobDoesNotExistException {
    final Job descriptor = getJob(jobId);
    if (descriptor == null) {
      throw new JobDoesNotExistException(jobId);
    }
    final ZooKeeperClient client = provider.get("getJobHistory");
    final List<String> hosts;
    try {
      hosts = client.getChildren(Paths.historyJobHosts(jobId));
    } catch (NoNodeException e) {
      return emptyList();
    } catch (KeeperException e) {
      throw Throwables.propagate(e);
    }

    final List<TaskStatusEvent> jsEvents = Lists.newArrayList();

    for (String host : hosts) {
      final List<String> events;
      try {
        events = client.getChildren(Paths.historyJobHostEvents(jobId, host));
      } catch (KeeperException e) {
        throw Throwables.propagate(e);
      }

      for (String event : events) {
        try {
          byte[] data = client.getData(Paths.historyJobHostEventsTimestamp(
              jobId, host, Long.valueOf(event)));
          final TaskStatus status = Json.read(data, TaskStatus.class);
          jsEvents.add(new TaskStatusEvent(status, Long.valueOf(event), host));
        } catch (KeeperException | IOException e) {
          throw Throwables.propagate(e);
        }
      }
    }

    return Ordering.from(EVENT_COMPARATOR).sortedCopy(jsEvents);
  }

  @Override
  public Job getJob(final JobId id) {
    log.debug("getting job: {}", id);
    final ZooKeeperClient client = provider.get("getJob");
    return getJob(client, id);
  }

  private Job getJob(final ZooKeeperClient client, final JobId id) {
    final String path = Paths.configJob(id);
    try {
      final byte[] data = client.getData(path);
      return Json.read(data, Job.class);
    } catch (NoNodeException e) {
      // Return null to indicate that the job does not exist
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("getting job " + id + " failed", e);
    }
  }

  @Override
  public Map<JobId, Job> getJobs() {
    log.debug("getting jobs");
    final String folder = Paths.configJobs();
    final ZooKeeperClient client = provider.get("getJobs");
    try {
      final List<String> ids;
      try {
        ids = client.getChildren(folder);
      } catch (NoNodeException e) {
        return Maps.newHashMap();
      }
      final Map<JobId, Job> descriptors = Maps.newHashMap();
      for (final String id : ids) {
        final JobId jobId = JobId.fromString(id);
        final String path = Paths.configJob(jobId);
        final byte[] data = client.getData(path);
        final Job descriptor = parse(data, Job.class);
        descriptors.put(descriptor.getId(), descriptor);
      }
      return descriptors;
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("getting jobs failed", e);
    }
  }

  @Override
  public JobStatus getJobStatus(final JobId jobId) {
    final ZooKeeperClient client = provider.get("getJobStatus");

    final Job job = getJob(client, jobId);
    if (job == null) {
      return null;
    }

    final List<String> hosts;
    try {
      // TODO (dano): this will list all hosts that the job is deployed to, maybe we should list all hosts that are reporting that they are running this job
      hosts = listJobHosts(client, jobId);
    } catch (JobDoesNotExistException e) {
      return null;
    }

    final ImmutableMap.Builder<String, TaskStatus> taskStatuses = ImmutableMap.builder();
    for (final String host : hosts) {
      final TaskStatus taskStatus = getTaskStatus(client, host, jobId);
      if (taskStatus != null) {
        taskStatuses.put(host, taskStatus);
      }
    }

    return JobStatus.newBuilder()
        .setJob(job)
        .setDeployedHosts(ImmutableSet.copyOf(hosts))
        .setTaskStatuses(taskStatuses.build())
        .build();
  }

  private List<String> listJobHosts(final ZooKeeperClient client, final JobId jobId)
      throws JobDoesNotExistException {
    final List<String> hosts;
    try {
      hosts = client.getChildren(Paths.configJobHosts(jobId));
    } catch (NoNodeException e) {
      throw new JobDoesNotExistException(jobId);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("failed to list hosts for job: " + jobId, e);
    }
    return hosts;
  }

  @Override
  public Job removeJob(final JobId id) throws JobDoesNotExistException, JobStillDeployedException {
    log.debug("removing job: id={}", id);
    final Job old = getJob(id);
    final ZooKeeperClient client = provider.get("removeJob");
    try {
      client.transaction(delete(Paths.configJobHosts(id)),
                         delete(Paths.configJobRefShort(id)),
                         delete(Paths.configJob(id)));
    } catch (final NoNodeException e) {
      throw new JobDoesNotExistException(id);
    } catch (final NotEmptyException e) {
      throw new JobStillDeployedException(id, listJobHosts(client, id));
    } catch (final KeeperException e) {
      throw new HeliosRuntimeException("removing job " + id + " failed", e);
    }

    return old;
  }

  @Override
  public void deployJob(final String host, final Deployment deployment)
      throws JobDoesNotExistException, JobAlreadyDeployedException, HostNotFoundException,
             JobPortAllocationConflictException {
    final ZooKeeperClient client = provider.get("deployJob");
    deployJobRetry(client, host, deployment, 0);
  }

  private void deployJobRetry(final ZooKeeperClient client, final String host,
                              final Deployment deployment, int count)
  throws JobDoesNotExistException, JobAlreadyDeployedException, HostNotFoundException,
             JobPortAllocationConflictException {
    if (count == 3) {
      throw new HeliosRuntimeException("3 failures (possibly concurrent modifications) while " +
                                       "deploying. Giving up.");
    }
    log.debug("deploying {}: {}", deployment, host);

    final JobId id = deployment.getJobId();
    final Job job = getJob(id);

    if (job == null) {
      throw new JobDoesNotExistException(id);
    }

    final String jobPath = Paths.configJob(id);
    final String taskPath = Paths.configHostJob(host, id);

    final List<Integer> staticPorts = staticPorts(job);
    final Map<String, byte[]> portNodes = Maps.newHashMap();
    final byte[] idJson = id.toJsonBytes();
    for (final int port : staticPorts) {
      final String path = Paths.configHostPort(host, port);
      portNodes.put(path, idJson);
    }

    final Task task = new Task(job, deployment.getGoal());
    List<ZooKeeperOperation> operations = Lists.newArrayList(
        check(jobPath), create(portNodes), create(Paths.configJobHost(id, host)));

   // Attempt to read a task here.  If it's goal is UNDEPLOY, it's as good as not existing
    try {
      Node existing = client.getNode(taskPath);
      byte[] bytes = existing.getBytes();
      Task readTask = Json.read(bytes, Task.class);
      if (readTask.getGoal() != Goal.UNDEPLOY) {
        throw new JobAlreadyDeployedException(host, id);
      }
      operations.add(check(taskPath, existing.getStat().getVersion()));
      operations.add(set(taskPath, task));
    } catch (NoNodeException e) {
      operations.add(create(taskPath, task));
    } catch (IOException | KeeperException e) {
      throw new HeliosRuntimeException("reading existing task description failed", e);
    }

    // TODO (dano): Failure handling is racy wrt agent and job modifications. Probably rare, but still.
    try {
      client.transaction(operations);
    } catch (NoNodeException e) {
      // Either the job or the host went away
      assertJobExists(client, id);
      assertHostExists(client, host);
      // Retry
      deployJobRetry(client, host, deployment, count + 1);
    } catch (NodeExistsException e) {
      try {
        // Check if the job was already deployed
        if (client.stat(taskPath) != null) {
          throw new JobAlreadyDeployedException(host, id);
        }
      } catch (KeeperException ex) {
        throw new HeliosRuntimeException("checking job deployment failed", e);
      }

      // Check for static port collisions
      for (final int port : staticPorts) {
        final String path = Paths.configHostPort(host, port);
        try {
          if (client.stat(path) == null) {
            continue;
          }
          final byte[] b = client.getData(path);
          final JobId existingJobId = parse(b, JobId.class);
          throw new JobPortAllocationConflictException(id, existingJobId, host, port);
        } catch (KeeperException | IOException ex) {
          throw new HeliosRuntimeException("checking port allocations failed", e);
        }
      }

      // Catch all for logic and ephemeral issues
      throw new HeliosRuntimeException("deploying job failed", e);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("deploying job failed", e);
    }
  }

  private void assertJobExists(final ZooKeeperClient client, final JobId id)
      throws JobDoesNotExistException {
    try {
      final String path = Paths.configJob(id);
      if (client.stat(path) == null) {
        throw new JobDoesNotExistException(id);
      }
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("checking job existence failed", e);
    }
  }

  private List<Integer> staticPorts(final Job job) {
    final List<Integer> staticPorts = Lists.newArrayList();
    for (final PortMapping portMapping : job.getPorts().values()) {
      if (portMapping.getExternalPort() != null) {
        staticPorts.add(portMapping.getExternalPort());
      }
    }
    return staticPorts;
  }

  @Override
  public void updateDeployment(final String host, final Deployment deployment)
      throws HostNotFoundException, JobNotDeployedException {
    log.debug("updating deployment {}: {}", deployment, host);

    final ZooKeeperClient client = provider.get("updateDeployment");

    final JobId jobId = deployment.getJobId();
    final Job descriptor = getJob(client, jobId);

    if (descriptor == null) {
      throw new JobNotDeployedException(host, jobId);
    }

    assertHostExists(client, host);
    assertTaskExists(client, host, deployment.getJobId());

    final String path = Paths.configHostJob(host, jobId);
    final Task task = new Task(descriptor, deployment.getGoal());
    try {
      client.setData(path, task.toJsonBytes());
    } catch (Exception e) {
      throw new HeliosRuntimeException("updating deployment " + deployment +
                                       " on host " + host + " failed", e);
    }
  }

  private void assertHostExists(final ZooKeeperClient client, final String host)
  throws HostNotFoundException {
    try {
      client.getData(Paths.configHost(host));
    } catch (NoNodeException e) {
      throw new HostNotFoundException(host, e);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException(e);
    }
  }

  private void assertTaskExists(final ZooKeeperClient client, final String host, final JobId jobId)
  throws JobNotDeployedException {
    try {
      client.getData(Paths.configHostJob(host, jobId));
    } catch (NoNodeException e) {
      throw new JobNotDeployedException(host, jobId);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException(e);
    }
  }

  @Override
  public Deployment getDeployment(final String host, final JobId jobId) {
    final String path = Paths.configHostJob(host, jobId);
    final ZooKeeperClient client = provider.get("getDeployment");
    try {
      final byte[] data = client.getData(path);
      final Task task = parse(data, Task.class);
      if (task.getGoal() == UNDEPLOY) {
        return null;
      }
      return Deployment.of(jobId, task.getGoal());
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("getting deployment failed", e);
    }
  }

  @Override
  public HostStatus getHostStatus(final String host) {
    final Stat stat;
    final ZooKeeperClient client = provider.get("getHostStatus");

    try {
      stat = client.exists(Paths.configHostId(host));
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("Failed to check host status", e);
    }

    if (stat == null) {
      return null;
    }

    final boolean up = checkHostUp(client, host);
    final HostInfo hostInfo = getHostInfo(client, host);
    final AgentInfo agentInfo = getAgentInfo(client, host);
    final Map<JobId, Deployment> tasks = getTasks(client, host);

    final Map<JobId, Deployment> jobs = Maps.filterEntries(tasks,
        new Predicate<Entry<JobId, Deployment>>() {
          @Override public boolean apply(Entry<JobId, Deployment> entry) {
            return entry.getValue().getGoal() != UNDEPLOY;
          }
        });
    final Map<JobId, TaskStatus> statuses = getTaskStatuses(client, host);
    final Map<String, String> environment = getEnvironment(client, host);

    return HostStatus.newBuilder()
        .setJobs(jobs)
        .setStatuses(fromNullable(statuses).or(EMPTY_STATUSES))
        .setHostInfo(hostInfo)
        .setAgentInfo(agentInfo)
        .setStatus(up ? UP : DOWN)
        .setEnvironment(environment)
        .build();
  }

  private <T> T tryGetEntity(final ZooKeeperClient client, String path, TypeReference<T> type,
                             String name) {
    try {
      final byte[] data = client.getData(path);
      return Json.read(data, type);
    } catch (NoNodeException e) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("reading " + name + " info failed", e);
    }
  }

  private Map<String, String> getEnvironment(final ZooKeeperClient client, final String host) {
    return tryGetEntity(client, Paths.statusHostEnvVars(host),
                        new TypeReference<Map<String, String>>() {},
                        "environment");
  }

  private AgentInfo getAgentInfo(final ZooKeeperClient client, final String host) {
    return tryGetEntity(client, Paths.statusHostAgentInfo(host),
                        new TypeReference<AgentInfo>() {},
                        "agent info");
  }

  private HostInfo getHostInfo(final ZooKeeperClient client, final String host) {
    return tryGetEntity(client, Paths.statusHostInfo(host),
                        new TypeReference<HostInfo>() {},
                        "host info");
  }

  private boolean checkHostUp(final ZooKeeperClient client, final String host) {
    try {
      final Stat stat = client.exists(Paths.statusHostUp(host));
      return stat != null;
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("getting host " + host + " up status failed", e);
    }
  }

  private Map<JobId, TaskStatus> getTaskStatuses(final ZooKeeperClient client, final String host) {
    final Map<JobId, TaskStatus> statuses = Maps.newHashMap();
    final List<JobId> jobIds = listHostJobs(client, host);
    for (final JobId jobId : jobIds) {
      final TaskStatus status = getTaskStatus(client, host, jobId);
      if (status != null) {
        statuses.put(jobId, status);
      } else {
        log.debug("Task {} status missing for host {}", jobId, host);
      }
    }

    return statuses;
  }

  private List<JobId> listHostJobs(final ZooKeeperClient client, final String host) {
    final List<String> jobIdStrings;
    final String folder = Paths.statusHostJobs(host);
    try {
      jobIdStrings = client.getChildren(folder);
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("List tasks for host failed: " + host, e);
    }
    final ImmutableList.Builder<JobId> jobIds = ImmutableList.builder();
    for (String jobIdString : jobIdStrings) {
      jobIds.add(JobId.fromString(jobIdString));
    }
    return jobIds.build();
  }

  @Nullable
  private TaskStatus getTaskStatus(final ZooKeeperClient client, final String host,
                                   final JobId jobId) {
    final String containerPath = Paths.statusHostJob(host, jobId);
    try {
      final byte[] data = client.getData(containerPath);
      return parse(data, TaskStatus.class);
    } catch (NoNodeException ignored) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("Getting task " + jobId + " status " +
                                       "for host " + host + " failed", e);
    }
  }

  private Map<JobId, Deployment> getTasks(final ZooKeeperClient client, final String host) {
    final Map<JobId, Deployment> jobs = Maps.newHashMap();
    try {
      final String folder = Paths.configHostJobs(host);
      final List<String> jobIds;
      try {
        jobIds = client.getChildren(folder);
      } catch (KeeperException.NoNodeException e) {
        return null;
      }

      for (final String jobIdString : jobIds) {
        final JobId jobId = JobId.fromString(jobIdString);
        final String containerPath = Paths.configHostJob(host, jobId);
        try {
          final byte[] data = client.getData(containerPath);
          final Task task = parse(data, Task.class);
          if (task.getGoal() == UNDEPLOY) {
            continue;
          }
          jobs.put(jobId, Deployment.of(jobId, task.getGoal()));
        } catch (KeeperException.NoNodeException ignored) {
          log.debug("deployment config node disappeared: {}", jobIdString);
        }
      }
    } catch (KeeperException | IOException e) {
      throw new HeliosRuntimeException("getting deployment config failed", e);
    }

    return jobs;
  }

  @Override
  public Deployment undeployJob(final String host, final JobId jobId)
      throws HostNotFoundException, JobNotDeployedException {
    log.debug("undeploying {}: {}", jobId, host);
    final ZooKeeperClient client = provider.get("undeployJob");

    assertHostExists(client, host);

    final Deployment deployment = getDeployment(host, jobId);
    if (deployment == null) {
      throw new JobNotDeployedException(host, jobId);
    }

    // TODO (dano): is this safe? can e.g. the ports of an undeployed job collide with a new deployment?

    updateDeployment(host, Deployment.of(jobId, Goal.UNDEPLOY));
    final List<ZooKeeperOperation> operations = Lists.newArrayList(
        delete(Paths.configJobHost(jobId, host)));

    final Job job = getJob(jobId);
    final List<Integer> staticPorts = staticPorts(job);
    for (int port : staticPorts) {
        operations.add(delete(Paths.configHostPort(host, port)));
    }
    try {
      client.transaction(operations);
    } catch (KeeperException e) {
      throw new HeliosRuntimeException("Removing deployment failed", e);
    }
    return deployment;
  }
}
