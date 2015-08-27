/*
 * Copyright (c) 1998-2015 Citrix Online LLC
 * All Rights Reserved Worldwide.
 *
 * THIS PROGRAM IS CONFIDENTIAL AND PROPRIETARY TO CITRIX ONLINE
 * AND CONSTITUTES A VALUABLE TRADE SECRET.  Any unauthorized use,
 * reproduction, modification, or disclosure of this program is
 * strictly prohibited.  Any use of this program by an authorized
 * licensee is strictly subject to the terms and conditions,
 * including confidentiality obligations, set forth in the applicable
 * License and Co-Branding Agreement between Citrix Online LLC and
 * the licensee.
 */
package com.nirima.jenkins.plugins.docker.provisioning;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
                LOGGER.info("Provisioning docker hosts if necessary... dockerHostLabel={}", dockerHostLabelString);
//                DockerHostFinder dockerHostFinder = new DockerHostFinder(dockerHostLabel, dockerCloud.serverUrl);
//                String host = dockerHostFinder.findDockerHost();
//                URI old = URI.create(dockerCloud.serverUrl);
//                try {
                    // TODO: make setting serverUrl thread-safe and support multiple dynamic docker
                    // hosts
//                    dockerCloud.serverUrl = new URI(old.getScheme(), old.getUserInfo(), host, old.getPort(),
//                        old.getPath(), old.getQuery(), old.getFragment()).toString();
//                    LOGGER.info("Will provision container on host with serverUrl={}", dockerCloud.serverUrl);

                    // dockerTask wont' be restored from deserialization as task serialization
                    // doesn't seem to work properly
                    if (dockerTask != null) {
                        LOGGER.info("Rescheduling docker task and cancelling docker host provisioning task... dockerTaskName=\"{}\" dockerTaskLabel=\"{}\" dockerHostLabel=\"{}\"",
                                    dockerTask.getDisplayName(), dockerTask.getAssignedLabel(), dockerHostLabelString);
                        Queue queue = Jenkins.getInstance().getQueue();
                        // reschedule docker task
                        queue.schedule(dockerTask, 0);
                        // cancel docker host provisioning task
                        queue.cancel(DockerHostProvisioningTask.this);
                    }
//                } catch (URISyntaxException e) {
//                    LOGGER.error("Unexpected error", e);
//                }
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
