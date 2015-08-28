package com.nirima.jenkins.plugins.docker;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;

import javax.servlet.ServletException;

import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.springframework.util.StringUtils;

import com.nirima.jenkins.plugins.docker.DockerManagement.DockerHost;
import com.nirima.jenkins.plugins.docker.utils.Consts;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Created by magnayn on 22/02/2014.
 */
public class DockerManagementServer  implements Describable<DockerManagementServer> {
    final String cloudName;
    final String hostUrl;
    final DockerCloud theCloud;

    public Descriptor<DockerManagementServer> getDescriptor() {
        return Jenkins.getInstance().getDescriptorByType(DescriptorImpl.class);
    }

    public String getUrl() {
        return DockerManagement.get().getUrlName() + "/server/" + DockerHost.encodeHostId(cloudName, hostUrl);
    }

    public DockerManagementServer(String encodedHostId) {
        String decodedHostId = new String(Base64.decodeBase64(encodedHostId), Charsets.UTF_8);
        JSONObject hostIdJson = JSONObject.fromObject(decodedHostId);
        this.cloudName = hostIdJson.getString("cloudName");
        this.hostUrl = hostIdJson.getString("hostUrl");
        this.theCloud = PluginImpl.getInstance().getServer(cloudName);
    }

    public String getHostUrl() {
        return hostUrl;
    }

    public Collection getImages(){
        return theCloud.getClient(hostUrl).listImagesCmd().exec();
    }

    public Collection getProcesses() {
        return theCloud.getClient(hostUrl).listContainersCmd().exec();
    }

    public String asTime(Long time) {
        if( time == null )
            return "";

        long when = System.currentTimeMillis() - time;

        Date dt = new Date(when);
        return dt.toString();
    }

    public String asTags(String[] tags) {
        return StringUtils.arrayToCommaDelimitedString(tags);
    }

    public String getJsUrl(String jsName) {
        return Consts.PLUGIN_JS_URL + jsName;
    }

    public void doControlSubmit(@QueryParameter("stopId") String stopId, StaplerRequest req, StaplerResponse rsp) throws ServletException,
            IOException,
            InterruptedException {

        theCloud.getClient(hostUrl)
            .stopContainerCmd(stopId).exec();

        rsp.sendRedirect(".");
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<DockerManagementServer> {

        @Override
        public String getDisplayName() {
            return "server ";
        }


    }
}
