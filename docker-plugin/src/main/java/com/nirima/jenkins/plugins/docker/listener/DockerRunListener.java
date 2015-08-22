package com.nirima.jenkins.plugins.docker.listener;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nirima.jenkins.plugins.docker.DockerJobProperty;
import com.nirima.jenkins.plugins.docker.action.DockerBuildImageAction;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * Listen for builds being deleted, and optionally clean up resources
 * (docker images) when this happens.
 *
 */
@Extension
public class DockerRunListener extends RunListener<Run<?,?>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerRunListener.class.getName());

    @Override
    @SuppressWarnings("rawtypes")
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        // already too late, slave has been provisioned at this point
        LOGGER.info("Job started: id={}", run.getId());
        Job<?, ?> job = run.getParent();
        DockerJobProperty jobProperty =  job.getProperty(DockerJobProperty.class);
        if (jobProperty != null) { // docker job
            Label dockerImageLabel = ((AbstractProject) job).getAssignedLabel();
        }
    }

    @Override
    public void onDeleted(Run<?, ?> run) {
        super.onDeleted(run);
        List<DockerBuildImageAction> actions = run.getActions(DockerBuildImageAction.class);

        for(DockerBuildImageAction action : actions) {
            if( action.cleanupWithJenkinsJobDelete ) {
                LOGGER.info("Attempting to clean up docker image for " + run);


                if( action.pushOnSuccess ) {

                    // TODO:

                    /*
                    DockerRegistryClient registryClient;

                    try {

                        Identifier identifier = Identifier.fromCompoundString(action.taggedId);

                        registryClient = DockerRegistryClient.builder()
                                .withUrl(identifier.repository.getURL())
                                .build();

                        registryClient.registryApi().deleteRepositoryTag("library",
                                identifier.repository.getPath(),
                                identifier.tag.orNull());



                    } catch (Exception ex) {

                        LOGGER.log(Level.WARNING, "Failed to clean up", ex);
                    }
                          */
                }
            }
        }

    }
}
