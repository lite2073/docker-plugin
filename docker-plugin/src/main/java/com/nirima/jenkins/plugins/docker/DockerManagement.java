package com.nirima.jenkins.plugins.docker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.kohsuke.stapler.StaplerProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.model.Container;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ManagementLink;
import hudson.model.Saveable;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;



/**
 * Manage the docker images.
 * Docker page under "Manage Jenkins" page.
 */
@Extension
public class DockerManagement extends ManagementLink implements StaplerProxy, Describable<DockerManagement>, Saveable {

    private static final Logger logger = LoggerFactory.getLogger(DockerManagement.class);

    @Override
    public String getIconFileName() {
        return com.nirima.jenkins.plugins.docker.utils.Consts.PLUGIN_IMAGES_URL + "/48x48/docker.png";
    }

    @Override
    public String getUrlName() {
        return "docker-plugin";
    }

    public String getDisplayName() {
        return Messages.DisplayName();
    }

    @Override
    public String getDescription() {
        return Messages.PluginDescription();
    }

    public static DockerManagement get() {
        return ManagementLink.all().get(DockerManagement.class);
    }


    public DescriptorImpl getDescriptor() {
        return Jenkins.getInstance().getDescriptorByType(DescriptorImpl.class);
    }

    public void save() throws IOException {

    }

    /**
     * Descriptor is only used for UI form bindings.
     */
    @Extension
    public static final class DescriptorImpl extends Descriptor<DockerManagement> {

        @Override
        public String getDisplayName() {
            return null; // unused
        }
    }

    public DockerManagementServer getServer(String encodedHostId) {
        return new DockerManagementServer(encodedHostId);
    }

        public Object getTarget() {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            return this;
        }

    public static class DockerHost {
        final String cloudName;
        final String hostUrl;
        final DockerCloud cloud;

        public DockerHost(String cloudName, String hostUrl, DockerCloud cloud) {
            this.cloudName = cloudName;
            this.hostUrl = hostUrl;
            this.cloud = cloud;
        }

        public String getCloudName() {
            return cloudName;
        }

        public String getHostUrl() {
            return hostUrl;
        }

        public String getEncodedHostId() {
            return encodeHostId(cloudName, hostUrl);
        }

        public String getActiveContainerCount() {
            try {
                List<Container> containers = cloud.getClient(hostUrl).listContainersCmd().exec();
                return "(" + containers.size() + ")";
            } catch (Exception ex) {
                return "Error";
            }
        }

        public static String encodeHostId(String cloudName, String hostUrl) {
            Map<String, Object> map = new HashMap<>();
            map.put("cloudName", cloudName);
            map.put("hostUrl", hostUrl);
            return Base64.encodeBase64URLSafeString(JSONObject.fromObject(map).toString().getBytes());
        }
    }

    public Collection<DockerHost> getDockerHosts() {
        List<DockerHost> dockerHosts = new ArrayList<>();
        for (DockerCloud cloud : PluginImpl.getInstance().getServers()) {
            for (String dockerHostUrl : cloud.findDockerHostUrls()) {
                dockerHosts.add(new DockerHost(cloud.getDisplayName(), dockerHostUrl, cloud));
            }
        }
        return dockerHosts;
    }

}
