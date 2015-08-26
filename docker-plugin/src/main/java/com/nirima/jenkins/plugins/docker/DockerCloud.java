package com.nirima.jenkins.plugins.docker;

import static com.nirima.jenkins.plugins.docker.client.ClientConfigBuilderForPlugin.dockerClientConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import javax.ws.rs.ProcessingException;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.NameParser;
import com.nirima.jenkins.plugins.docker.launcher.DockerComputerLauncher;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import shaded.com.google.common.base.MoreObjects;
import shaded.com.google.common.base.Preconditions;
import shaded.com.google.common.base.Predicate;
import shaded.com.google.common.base.Throwables;
import shaded.com.google.common.collect.Iterables;

/**
 * Docker Cloud configuration. Contains connection configuration,
 * {@link DockerTemplate} contains configuration for running docker image.
 *
 * @author magnayn
 */
public class DockerCloud extends Cloud {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerCloud.class);

    private List<DockerTemplate> templates;
    public final String dockerHostLabel;
    //TODO: keep track of provisioned hosts and their usage
    public String serverUrl;
    private int connectTimeout;
    public final int readTimeout;
    public final String version;
    public final String credentialsId;

    private transient DockerClient connection;

    /**
     * Total max allowed number of containers
     */
    private int containerCap = 100;

    /**
     * Track the count per image name for images currently being
     * provisioned, but not necessarily reported yet by docker.
     */
    private static final HashMap<String, Integer> provisionedImages = new HashMap<>();

    @Deprecated
    public DockerCloud(String name,
                       List<? extends DockerTemplate> templates,
                       String dockerHostLabel,
                       String serverUrl,
                       String containerCapStr,
                       int connectTimeout,
                       int readTimeout,
                       String credentialsId,
                       String version) {
        super(name);
        Preconditions.checkNotNull(serverUrl);
        this.version = version;
        this.credentialsId = credentialsId;
        // TODO: validate docker host label against list of registered labels
        this.dockerHostLabel = dockerHostLabel;
        this.serverUrl = serverUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;

        if (templates != null) {
            this.templates = new ArrayList<>(templates);
        } else {
            this.templates = Collections.emptyList();
        }

        if (containerCapStr.equals("")) {
            setContainerCap(Integer.MAX_VALUE);
        } else {
            setContainerCap(Integer.parseInt(containerCapStr));
        }
    }

    @DataBoundConstructor
    public DockerCloud(String name,
                       List<? extends DockerTemplate> templates,
                       String dockerHostLabel,
                       String serverUrl,
                       int containerCap,
                       int connectTimeout,
                       int readTimeout,
                       String credentialsId,
                       String version) {
        super(name);
        Preconditions.checkNotNull(serverUrl);
        this.version = version;
        this.credentialsId = credentialsId;
        // TODO: validate docker host label against list of registered labels
        this.dockerHostLabel = dockerHostLabel;
        this.serverUrl = serverUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;

        if (templates != null) {
            this.templates = new ArrayList<>(templates);
        } else {
            this.templates = Collections.emptyList();
        }

        setContainerCap(containerCap);
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @deprecated use {@link #getContainerCap()}
     */
    @Deprecated
    public String getContainerCapStr() {
        if (containerCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    public int getContainerCap() {
        return containerCap;
    }

    public void setContainerCap(int containerCap) {
        this.containerCap = containerCap;
    }

    /**
     * Connects to Docker.
     *
     * @return Docker client.
     */
    public synchronized DockerClient getClient() {
        if (connection == null) {
            connection = dockerClientConfig().forCloud(this).buildClient();
        }

        return connection;
    }

    /**
     * Decrease the count of slaves being "provisioned".
     */
    private void decrementAmiSlaveProvision(String ami) {
        synchronized (provisionedImages) {
            int currentProvisioning;
            try {
                currentProvisioning = provisionedImages.get(ami);
            } catch (NullPointerException npe) {
                return;
            }
            provisionedImages.put(ami, Math.max(currentProvisioning - 1, 0));
        }
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {

        try {
            LOGGER.info("Asked to provision {} slave(s) for: {}", new Object[]{excessWorkload, label});

            List<NodeProvisioner.PlannedNode> r = new ArrayList<>();

            final List<DockerTemplate> templates = getTemplates(label);

            while (excessWorkload > 0 && !templates.isEmpty()) {
                final DockerTemplate t = templates.get(0); // get first

                LOGGER.info("Will provision '{}', for label: '{}', in cloud: '{}'",
                        t.getDockerTemplateBase().getImage(), label, getDisplayName());

                try {
                    if (!addProvisionedSlave(t)) {
                        templates.remove(t);
                        continue;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Bad template '{}' in cloud '{}': '{}'. Trying next template...",
                            t.getDockerTemplateBase().getImage(), getDisplayName(), e.getMessage());
                    templates.remove(t);
                    continue;
                }

                r.add(new NodeProvisioner.PlannedNode(
                                t.getDockerTemplateBase().getDisplayName(),
                                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                                    public Node call() throws Exception {
                                        try {
                                            return provisionWithWait(t);
                                        } catch (Exception ex) {
                                            LOGGER.error("Error in provisioning; template='{}' for cloud='{}'",
                                                    t, getDisplayName(), ex);
                                            throw Throwables.propagate(ex);
                                        } finally {
                                            decrementAmiSlaveProvision(t.getDockerTemplateBase().getImage());
                                        }
                                    }
                                }),
                                t.getNumExecutors())
                );

                excessWorkload -= t.getNumExecutors();
            }

            return r;
        } catch (Exception e) {
            LOGGER.error("Exception while provisioning for label: '{}', cloud='{}'", label, getDisplayName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Run docker container
     */
    public static String runContainer(DockerTemplate dockerTemplate,
                                      DockerClient dockerClient,
                                      DockerComputerLauncher launcher)
            throws DockerException, IOException {
        final DockerTemplateBase dockerTemplateBase = dockerTemplate.getDockerTemplateBase();
        CreateContainerCmd containerConfig = dockerClient.createContainerCmd(dockerTemplateBase.getImage());

        dockerTemplateBase.fillContainerConfig(containerConfig);

        // contribute launcher specific options
        if (launcher != null) {
            launcher.appendContainerConfig(dockerTemplate, containerConfig);
        }

        // create
        CreateContainerResponse response = containerConfig.exec();
        String containerId = response.getId();

        // start
        StartContainerCmd startCommand = dockerClient.startContainerCmd(containerId);
        startCommand.exec();

        return containerId;
    }

    /**
     * for publishers/builders
     */
    public static String runContainer(DockerTemplateBase dockerTemplateBase,
                                      DockerClient dockerClient,
                                      DockerComputerLauncher launcher) {
        CreateContainerCmd containerConfig = dockerClient.createContainerCmd(dockerTemplateBase.getImage());

        dockerTemplateBase.fillContainerConfig(containerConfig);

//        // contribute launcher specific options
//        if (launcher != null) {
//            launcher.appendContainerConfig(dockerTemplateBase, containerConfig);
//        }

        // create
        CreateContainerResponse response = containerConfig.exec();
        String containerId = response.getId();

        // start
        StartContainerCmd startCommand = dockerClient.startContainerCmd(containerId);
        startCommand.exec();

        return containerId;
    }

    private void pullImage(DockerTemplate dockerTemplate)  throws IOException {
        final String imageName = dockerTemplate.getDockerTemplateBase().getImage();

        List<Image> images = getClient().listImagesCmd().exec();

        NameParser.ReposTag repostag = NameParser.parseRepositoryTag(imageName);
        // if image was specified without tag, then treat as latest
        final String fullImageName = repostag.repos + ":" + (repostag.tag.isEmpty() ? "latest" : repostag.tag);

        boolean imageExists = Iterables.any(images, new Predicate<Image>() {
            @Override
            public boolean apply(Image image) {
                return Arrays.asList(image.getRepoTags()).contains(fullImageName);
            }
        });

        boolean pull = imageExists ?
                dockerTemplate.getPullStrategy().pullIfExists(imageName) :
                dockerTemplate.getPullStrategy().pullIfNotExists(imageName);

        if (pull) {
            LOGGER.info("Pulling image '{}' {}. This may take awhile...", imageName,
                    imageExists ? "again" : "since one was not found");

            long startTime = System.currentTimeMillis();
            //Identifier amiId = Identifier.fromCompoundString(ami);
            try (InputStream imageStream = getClient().pullImageCmd(imageName).exec()) {
                int streamValue = 0;
                while (streamValue != -1) {
                    streamValue = imageStream.read();
                }
            }
            long pullTime = System.currentTimeMillis() - startTime;
            LOGGER.info("Finished pulling image '{}', took {} ms", imageName, pullTime);
        }
    }

    private DockerSlave provisionWithWait(DockerTemplate dockerTemplate) throws IOException, Descriptor.FormException {
        pullImage(dockerTemplate);

        LOGGER.info("Trying to run container for {}", dockerTemplate.getDockerTemplateBase().getImage());
        final String containerId = runContainer(dockerTemplate, getClient(), dockerTemplate.getLauncher());

        InspectContainerResponse ir;
        try {
            ir = getClient().inspectContainerCmd(containerId).exec();
        } catch (ProcessingException ex) {
            getClient().removeContainerCmd(containerId).withForce(true).exec();
            throw ex;
        }

        // Build a description up:
        String nodeDescription = "Docker Node [" + dockerTemplate.getDockerTemplateBase().getImage() + " on ";
        try {
            nodeDescription += getDisplayName();
        } catch (Exception ex) {
            nodeDescription += "???";
        }
        nodeDescription += "]";

        String slaveName = containerId.substring(0, 12);

        try {
            slaveName = getDisplayName() + "-" + slaveName;
        } catch (Exception ex) {
            LOGGER.warn("Error fetching cloud name");
        }

        dockerTemplate.getLauncher().waitUp(getDisplayName(), dockerTemplate, ir);

        final ComputerLauncher launcher = dockerTemplate.getLauncher().getPreparedLauncher(getDisplayName(), dockerTemplate, ir);

        return new DockerSlave(slaveName, nodeDescription, launcher, containerId, dockerTemplate, getDisplayName());
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    @CheckForNull
    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if (t.getDockerTemplateBase().getImage().equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets first {@link DockerTemplate} that has the matching {@link Label}.
     */
    @CheckForNull
    public DockerTemplate getTemplate(Label label) {
        List<DockerTemplate> templates = getTemplates(label);
        if (!templates.isEmpty()) {
            return templates.get(0);
        }

        return null;
    }

    /**
     * Add a new template to the cloud
     */
    public synchronized void addTemplate(DockerTemplate t) {
        templates.add(t);
    }

    public List<DockerTemplate> getTemplates() {
        return templates;
    }

    /**
     * Multiple amis can have the same label.
     *
     * @return Templates matched to requested label assuming slave Mode
     */
    public List<DockerTemplate> getTemplates(Label label) {
        ArrayList<DockerTemplate> dockerTemplates = new ArrayList<>();

        for (DockerTemplate t : templates) {
            if (label == null && t.getMode() == Node.Mode.NORMAL) {
                dockerTemplates.add(t);
            }

            if (label != null && label.matches(t.getLabelSet())) {
                dockerTemplates.add(t);
            }
        }

        return dockerTemplates;
    }

    /**
     * Remove Docker template
     */
    public synchronized void removeTemplate(DockerTemplate t) {
        templates.remove(t);
    }

    /**
     * Counts the number of instances in Docker currently running that are using the specified image.
     *
     * @param imageName If null, then all instances are counted.
     *            <p/>
     *            This includes those instances that may be started outside Hudson.
     */
    public int countCurrentDockerSlaves(final String imageName) throws Exception {
        int count = 0;
        List<Container> containers = getClient().listContainersCmd().exec();

        if (imageName == null) {
            count = containers.size();
        } else {
            for (Container container : containers) {
                String containerImage = container.getImage();
                if (containerImage.equals(imageName)) {
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Check not too many already running.
     */
    private synchronized boolean addProvisionedSlave(DockerTemplate t) throws Exception {
        String ami = t.getDockerTemplateBase().getImage();
        int amiCap = t.instanceCap;

        int estimatedTotalSlaves = countCurrentDockerSlaves(null);
        int estimatedAmiSlaves = countCurrentDockerSlaves(ami);

        synchronized (provisionedImages) {
            int currentProvisioning = 0;
            if (provisionedImages.containsKey(ami)) {
                currentProvisioning = provisionedImages.get(ami);
            }

            for (int amiCount : provisionedImages.values()) {
                estimatedTotalSlaves += amiCount;
            }

            estimatedAmiSlaves += currentProvisioning;

            if (estimatedTotalSlaves >= getContainerCap()) {
                LOGGER.info("Not Provisioning '{}'; Server '{}' full with '{}' container(s)", ami, getContainerCap(), name);
                return false;      // maxed out
            }

            if (amiCap != 0 && estimatedAmiSlaves >= amiCap) {
                LOGGER.info("Not Provisioning '{}'. Instance limit of '{}' reached on server '{}'", ami, amiCap, name);
                return false;      // maxed out
            }

            LOGGER.info("Provisioning '{}' number '{}' on '{}'; Total containers: '{}'",
                    ami, estimatedAmiSlaves, name, estimatedTotalSlaves);

            provisionedImages.put(ami, currentProvisioning + 1);
            return true;
        }
    }

    public static DockerCloud getCloudByName(String name) {
        return (DockerCloud) Jenkins.getInstance().getCloud(name);
    }

    public Object readResolve() {
        //Xstream is not calling readResolve() for nested Describable's
        for (DockerTemplate template : getTemplates()) {
            template.readResolve();
        }

        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("dockerHostLabel", dockerHostLabel)
                .add("serverUrl", serverUrl)
                .toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((dockerHostLabel == null) ? 0 : dockerHostLabel.hashCode());
        result = prime * result + connectTimeout;
        result = prime * result + containerCap;
        result = prime * result + ((credentialsId == null) ? 0 : credentialsId.hashCode());
        result = prime * result + readTimeout;
        result = prime * result + ((serverUrl == null) ? 0 : serverUrl.hashCode());
        result = prime * result + ((templates == null) ? 0 : templates.hashCode());
        result = prime * result + ((version == null) ? 0 : version.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DockerCloud other = (DockerCloud) obj;
        if (dockerHostLabel == null) {
            if (other.dockerHostLabel != null)
                return false;
        } else if (!dockerHostLabel.equals(other.dockerHostLabel))
            return false;
        if (connectTimeout != other.connectTimeout)
            return false;
        if (containerCap != other.containerCap)
            return false;
        if (credentialsId == null) {
            if (other.credentialsId != null)
                return false;
        } else if (!credentialsId.equals(other.credentialsId))
            return false;
        if (readTimeout != other.readTimeout)
            return false;
        if (serverUrl == null) {
            if (other.serverUrl != null)
                return false;
        } else if (!serverUrl.equals(other.serverUrl))
            return false;
        if (templates == null) {
            if (other.templates != null)
                return false;
        } else if (!templates.equals(other.templates))
            return false;
        if (version == null) {
            if (other.version != null)
                return false;
        } else if (!version.equals(other.version))
            return false;
        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Docker";
        }

        public FormValidation doTestConnection(
                @QueryParameter String dockerHostLabel,
                @QueryParameter String serverUrl,
                @QueryParameter String credentialsId,
                @QueryParameter String version
        ) throws IOException, ServletException, DockerException {
            //TODO: if there is no computer for the 'dockerHostLabel', show a warning
            // otherwise, perform connectivity test as usual
            try {
                DockerClient dc = dockerClientConfig()
                        .forServer(serverUrl, version)
                        .withCredentials(credentialsId)
                        .buildClient();

                Version verResult = dc.versionCmd().exec();

                return FormValidation.ok("Version = " + verResult.getVersion());
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup<?> context) {

            @SuppressWarnings("deprecation")
            List<StandardCertificateCredentials> credentials = CredentialsProvider.lookupCredentials(StandardCertificateCredentials.class, context);

            return new CredentialsListBoxModel().withEmptySelection()
                    .withMatching(CredentialsMatchers.always(),
                            credentials);
        }

        public static class CredentialsListBoxModel
                extends AbstractIdCredentialsListBoxModel<CredentialsListBoxModel, StandardCertificateCredentials> {
            private static final long serialVersionUID = 8318969697112130030L;

            @NonNull
            protected String describe(@NonNull StandardCertificateCredentials c) {
                return CredentialsNameProvider.name(c);
            }
        }
    }

}
