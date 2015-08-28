package com.nirima.jenkins.plugins.docker.provisioning;

import java.io.IOException;
import java.util.List;

import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nirima.jenkins.plugins.docker.DockerCloud;

import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Task;
import hudson.model.queue.AbstractQueueTask;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;

/**
 * A task for provisioning docker hosts via a label which could be tied to some cloud
 * 
 * @author tli
 *
 */
public class DockerHostProvisioningTask extends AbstractQueueTask implements ContinuedTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerHostProvisioningTask.class);

    // for persistence
    private final String dockerContainerLabel;
    private final String dockerHostLabelString;
    private final transient Task dockerTask;
    private transient Label dockerHostLabel;
    private transient DockerCloud dockerCloud;

    public DockerHostProvisioningTask(Task dockerTask) {
        this.dockerTask = dockerTask;
        this.dockerCloud = findMatchingDockerCloud(dockerTask.getAssignedLabel());
        this.dockerHostLabelString = this.dockerCloud.dockerHostLabel;
        this.dockerHostLabel = Jenkins.getInstance().getLabelAtom(this.dockerHostLabelString);
        this.dockerContainerLabel = dockerTask.getAssignedLabel().getDisplayName();
    }

    @Override
    public boolean isBuildBlocked() {
        return false;
    }

    @Override
    public String getWhyBlocked() {
        return "";
    }

    @Override
    public String getName() {
        return getDisplayName();
    }

    @Override
    public String getFullDisplayName() {
        return getDisplayName();
    }

    @Override
    public void checkAbortPermission() {
        getACL().checkPermission(Item.CANCEL);
    }

    @Override
    public boolean hasAbortPermission() {
        return getACL().hasPermission(Item.CANCEL);
    }

    @Override
    public String getUrl() {
        return "/docker/host-provisioning/";
    }

    @Override
    public String getDisplayName() {
        return "Provisioning Docker Host (" + dockerHostLabelString + ")";
    }

    @Override
    public Label getAssignedLabel() {
        return dockerHostLabel;
    }

    @Override
    public Node getLastBuiltOn() {
        return null;
    }

    @Override
    public long getEstimatedDuration() {
        return -1;
    }

    @Override
    public Executable createExecutable() throws IOException {
        return new Executable() {

            @Override
            public SubTask getParent() {
                return DockerHostProvisioningTask.this;
            }

            @Override
            public void run() throws AsynchronousExecution {
                LOGGER.info("Docker host ready for dockerHostLabel={}", dockerHostLabelString);
                if (dockerTask != null) {
                    LOGGER.info("Rescheduling docker task and cancelling docker host provisioning task... dockerTaskName=\"{}\" dockerTaskLabel=\"{}\" dockerHostLabel=\"{}\"",
                                dockerTask.getDisplayName(), dockerTask.getAssignedLabel(), dockerHostLabelString);
                    Queue queue = Jenkins.getInstance().getQueue();
                    // reschedule docker task
                    queue.schedule(dockerTask, 0);
                    // cancel docker host provisioning task
                    queue.cancel(DockerHostProvisioningTask.this);
                }
            }

            @Override
            public long getEstimatedDuration() {
                return 1;
            }
        };
    }

    @Override
    public ResourceList getResourceList() {
        return ResourceList.EMPTY;
    }

    @Override
    public boolean isContinued() {
        return true;
    }

    private ACL getACL() {
        return Jenkins.getInstance().getACL();
    }

    private DockerCloud findMatchingDockerCloud(Label dockerImageLabel) {
        List<DockerCloud> dockerClouds = Jenkins.getInstance().clouds.getAll(DockerCloud.class);
        for (DockerCloud dockerCloud : dockerClouds) {
            if (dockerCloud.getTemplate(dockerImageLabel) != null) {
                return dockerCloud;
            }
        }
        throw new IllegalStateException(
            "Couldn't find a matching DockerTemplate for label " + dockerImageLabel.getDisplayName());
    }

    // deserialization
    public Object readResolve() {
        // restore transient fields
        this.dockerHostLabel = Jenkins.getInstance().getLabelAtom(this.dockerHostLabelString);
        this.dockerCloud = findMatchingDockerCloud(Jenkins.getInstance().getLabelAtom(this.dockerContainerLabel));
        return this;
    }

}
