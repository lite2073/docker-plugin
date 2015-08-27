package com.nirima.jenkins.plugins.docker.builder;

import shaded.com.google.common.base.Strings;

import com.github.dockerjava.api.DockerClient;
import com.nirima.jenkins.plugins.docker.DockerCloud;
import com.nirima.jenkins.plugins.docker.DockerSlave;
import hudson.model.AbstractBuild;
import hudson.model.Node;
import jenkins.model.Jenkins;

/**
 * Created by magnayn on 30/01/2014.
 */
public abstract class DockerBuilderControlCloudOption extends DockerBuilderControlOption {
    public final String cloudName;

    protected DockerBuilderControlCloudOption(String cloudName) {
        this.cloudName = cloudName;
    }
    
    public String getCloudName() {
        return cloudName;
    }

    protected DockerCloud getCloud(AbstractBuild<?, ?> build) {
        DockerSlave slave = getDockerSlave(build);
        DockerCloud cloud = slave != null ? slave.getCloud() : null;

        if (cloud == null && !Strings.isNullOrEmpty(cloudName)) {
            cloud = (DockerCloud) Jenkins.getInstance().getCloud(cloudName);
        }

        if( cloud == null ) {
            throw new RuntimeException("Cannot list cloud for docker action");
        }

        return cloud;
    }

    protected DockerClient getClient(AbstractBuild<?, ?> build) {
        DockerSlave slave = getDockerSlave(build);
        if (slave == null) {
            throw new IllegalStateException("Couldn't find the docker slave associated with the build");
        }
        DockerCloud cloud = slave.getCloud();
        return cloud.getClient(slave.getHostUrl());
    }

    private DockerSlave getDockerSlave(AbstractBuild<?, ?> build) {
        Node node = build.getBuiltOn();
        return node instanceof DockerSlave ? (DockerSlave) node : null;
    }
}
