package com.nirima.jenkins.plugins.docker.launcher;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.nirima.jenkins.plugins.docker.DockerCloud;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import com.nirima.jenkins.plugins.docker.DockerTemplateBase;
import com.nirima.jenkins.plugins.docker.utils.PortUtils;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.plugins.sshslaves.SSHConnector;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.util.ListBoxModel;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import shaded.com.google.common.annotations.Beta;
import shaded.com.google.common.base.Preconditions;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configurable SSH launcher that expected ssh port to be exposed from docker container.
 */
@Beta
public class DockerComputerSSHLauncher extends DockerComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(DockerComputerSSHLauncher.class.getName());
    // store real UI configuration
    protected final SSHConnector sshConnector;

    @DataBoundConstructor
    public DockerComputerSSHLauncher(SSHConnector sshConnector) {
        this.sshConnector = sshConnector;
    }

    public SSHConnector getSshConnector() {
        return sshConnector;
    }

    public ComputerLauncher getPreparedLauncher(String dockerHostUrl, DockerTemplate dockerTemplate, InspectContainerResponse inspect) {
        final DockerComputerSSHLauncher prepLauncher = new DockerComputerSSHLauncher(null); // don't care, we need only launcher

        prepLauncher.setLauncher(getSSHLauncher(dockerHostUrl, dockerTemplate, inspect));

        return prepLauncher;
    }

    @Override
    public void appendContainerConfig(DockerTemplate dockerTemplate, CreateContainerCmd createCmd) {
        final int sshPort = getSshConnector().port;

        createCmd.withPortSpecs(sshPort + "/tcp");

        String[] cmd = dockerTemplate.getDockerTemplateBase().getDockerCommandArray();
        if (cmd.length == 0) {
            //default value to preserve compatibility
            createCmd.withCmd("bash", "-c", "/usr/sbin/sshd -D -p " + sshPort);
        }

        createCmd.getPortBindings().add(PortBinding.parse("0.0.0.0::" + sshPort));
    }

    @Override
    public boolean waitUp(String dockerHostUrl, DockerTemplate dockerTemplate, InspectContainerResponse containerInspect) {
        super.waitUp(dockerHostUrl, dockerTemplate, containerInspect);

        final PortUtils portUtils = getPortUtils(dockerHostUrl, dockerTemplate, containerInspect);
        if (!portUtils.withEveryRetryWaitFor(10, TimeUnit.SECONDS)) {
            return false;
        }
        try {
            portUtils.bySshWithEveryRetryWaitFor(10, TimeUnit.SECONDS);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Can't connect to ssh", ex);
            return false;
        }

        return true;
    }

    private SSHLauncher getSSHLauncher(String dockerHostUrl, DockerTemplate template, InspectContainerResponse inspect) {
        Preconditions.checkNotNull(template);
        Preconditions.checkNotNull(inspect);

        try {
            final PortUtils portUtils = getPortUtils(dockerHostUrl, template, inspect);
            LOGGER.log(Level.INFO, "Creating slave SSH launcher for " + portUtils.host + ":" + portUtils.port);
            return new SSHLauncher(portUtils.host, portUtils.port, sshConnector.getCredentials(),
                    sshConnector.jvmOptions,
                    sshConnector.javaPath, sshConnector.prefixStartSlaveCmd,
                    sshConnector.suffixStartSlaveCmd, sshConnector.launchTimeoutSeconds);
        } catch (NullPointerException ex) {
            throw new RuntimeException("No mapped port 22 in host for SSL. Config=" + inspect, ex);
        }
    }

    public PortUtils getPortUtils(String dockerHostUrl, DockerTemplate dockerTemplate, InspectContainerResponse ir) {
        // get exposed port
        ExposedPort sshPort = new ExposedPort(sshConnector.port);
        String host = null;
        Integer port = 22;

        final InspectContainerResponse.NetworkSettings networkSettings = ir.getNetworkSettings();
        final Ports ports = networkSettings.getPorts();
        final Map<ExposedPort, Ports.Binding[]> bindings = ports.getBindings();
        final Ports.Binding[] sshBindings = bindings.get(sshPort);

        for (Ports.Binding b : sshBindings) {
            port = b.getHostPort();
            host = b.getHostIp();
        }

        //get address, if docker on localhost, then use local?
        if (host == null || host.equals("0.0.0.0")) {
            host = URI.create(dockerHostUrl).getHost();
        }

        return PortUtils.canConnect(host, port);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<ComputerLauncher> {

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            return DockerTemplateBase.DescriptorImpl.doFillCredentialsIdItems(context);
        }

        public Class getSshConnectorClass() {
            return SSHConnector.class;
        }

        @Override
        public String getDisplayName() {
            return "Docker SSH computer launcher";
        }
    }

}
