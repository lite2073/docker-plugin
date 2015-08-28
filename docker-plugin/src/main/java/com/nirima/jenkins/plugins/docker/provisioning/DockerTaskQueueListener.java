package com.nirima.jenkins.plugins.docker.provisioning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nirima.jenkins.plugins.docker.DockerJobProperty;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Queue.BuildableItem;
import hudson.model.Queue.Task;
import hudson.model.Queue.WaitingItem;
import hudson.model.queue.QueueListener;
import jenkins.model.Jenkins;

/**
 * A {@link QueueListener} for docker task scheduling. Docker tasks are swapped with docker
 * provisioning tasks upon entering waiting state in the queue.
 * 
 * At the end of execution of docker provisioning task, the original docker task is swapped back to
 * the queue.
 * 
 * @author tli
 * @see {@link DockerHostProvisioningTask}
 *
 */
// TODO: clean up stuck/failed tasks?
@Extension
public class DockerTaskQueueListener extends QueueListener {

    private static final Logger LOG = LoggerFactory.getLogger(DockerTaskQueueListener.class);

    private final transient Queue queue;
    // TODO: use Guava cache
    // host provisioning task -> docker task
    private final Map<Task, Task> dockerProvisioningTaskMap;

    public DockerTaskQueueListener() {
        this.queue = Jenkins.getInstance().getQueue();
        this.dockerProvisioningTaskMap = new ConcurrentHashMap<>();
    }

    @Override
    public void onEnterWaiting(WaitingItem item) {
        if (isDockerTask(item.task)) {
            if (dockerProvisioningTaskMap.containsValue(item.task)) {
                return; // rescheduled docker task, no change here
            }
            LOG.info("Cancelling docker task and scheduling docker host provisioning task... dockerTaskName=\"{}\" dockerTaskLabel=\"{}\"",
                     item.task.getDisplayName(), item.task.getAssignedLabel());
            // cancel docker task
            queue.cancel(item.task);
            // schedule docker host provisioning task
            DockerHostProvisioningTask provisioningTask = new DockerHostProvisioningTask(item.task);
            queue.schedule(provisioningTask, 0);
            dockerProvisioningTaskMap.put(provisioningTask, item.task);
            // provision if necessary
            provisioningTask.getAssignedLabel().nodeProvisioner.suggestReviewNow();
        }
    }

    @Override
    public void onEnterBuildable(BuildableItem item) {
        if (isDockerTask(item.task)) {
            LOG.info("Going to run docker task... dockerTaskLabel=\"{}\"", item.task.getAssignedLabel());
        } else if (dockerProvisioningTaskMap.containsKey(item.task)) {
            LOG.info("Going to run docker host provisioning task... dockerHostProvisioningTaskLabel=\"{}\"",
                     item.task.getAssignedLabel());
        }
    }

    // @Override
    // public void onLeft(LeftItem item) {
    // Task dockerTask = dockerProvisioningTaskMap.get(item.task);
    // if (dockerTask != null) {
    //// LOG.info("Rescheduling docker task and cancelling docker host provisioning task");
    //// // reschedule docker task
    //// queue.schedule(dockerTask, 0);
    //// // cancel docker host provisioning task
    //// queue.cancel(item.task);
    //
    // // if it's a remembered host provisioning task, mapping is no longer needed
    // // else no-op
    // // dockerProvisioningTaskMap.remove(item.task);
    // // LOG.info("Removed docker host provisioning task mapping. dockerTaskName=\"{}\"
    // dockerTaskLabel=\"{}\"",
    // // dockerTask.getDisplayName(), dockerTask.getAssignedLabel());
    // }
    // }

    private boolean isDockerTask(Task task) {
        return task instanceof Job && ((Job<?, ?>) task).getProperty(DockerJobProperty.class) != null;
    }

}
