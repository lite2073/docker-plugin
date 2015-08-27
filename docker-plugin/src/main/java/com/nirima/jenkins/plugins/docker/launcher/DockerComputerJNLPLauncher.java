package com.nirima.jenkins.plugins.docker.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.nirima.jenkins.plugins.docker.DockerComputer;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shaded.com.google.common.annotations.Beta;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * JNLP launcher. Doesn't require open ports on docker host.
 * <p/>
 * Steps:
 * - runs container with nop command
 * - as launch action executes jnlp connection to master
 *
 * @author Kanstantsin Shautsou
 */
@Beta
public class DockerComputerJNLPLauncher extends DockerComputerLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerComputerJNLPLauncher.class);

    /**
     * Configured from UI
     */
    protected JNLPLauncher jnlpLauncher;

    protected long launchTimeout = 120; //seconds

    protected String user;

    @DataBoundConstructor
    public DockerComputerJNLPLauncher(JNLPLauncher jnlpLauncher) {
        this.jnlpLauncher = jnlpLauncher;
    }

    public JNLPLauncher getJnlpLauncher() {
        return jnlpLauncher;
    }

    @DataBoundSetter
    public void setUser(String user) {
        this.user = user;
    }

    public String getUser() {
        return user;
    }

    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final DockerComputer dockerComputer = (DockerComputer) computer;
        final String containerId = dockerComputer.getContainerId();
        final String rootUrl = Jenkins.getInstance().getRootUrl();
        final DockerClient connect = dockerComputer.getCloud().getClient(dockerComputer.getHostUrl());
        final DockerTemplate dockerTemplate = dockerComputer.getNode().getDockerTemplate();

        // exec jnlp connection in running container
        // TODO implement PID 1 replacement

//        String cdCmd = "cd " + dockerTemplate.getRemoteFs();
//        String wgetSlaveCmd = "wget " + rootUrl + "jnlpJars/slave.jar -O slave.jar";
//        String jnlpConnectCmd = "java -jar slave.jar "
//                + "-jnlpUrl " + rootUrl + dockerComputer.getUrl() + "slave-agent.jnlp ";
////                + "-secret " + dockerComputer.getJnlpMac();
//
//        String[] connectCmd = {
//                "bash", "-c", cdCmd + " && " + wgetSlaveCmd + " && " + jnlpConnectCmd
//        };

        String startCmd =
                "cat << EOF > /tmp/config.sh.tmp && cd /tmp && mv config.sh.tmp config.sh\n" +
                        "JENKINS_URL=\"" + rootUrl + "\"\n" +
                        "JENKINS_USER=\"" + getUser() + "\"\n" +
                        "JENKINS_HOME=\"" + dockerTemplate.getRemoteFs() + "\"\n" +
                        "COMPUTER_URL=\"" + dockerComputer.getUrl() + "\"\n" +
                        "COMPUTER_SECRET=\"" + dockerComputer.getJnlpMac() + "\"\n" +
                        "EOF" + "\n";

        try {
//            LOGGER.info("Creating jnlp connection command '{}' for '{}'", Arrays.toString(connectCmd), containerId);
//            logger.println("Creating jnlp connection command '" + Arrays.toString(connectCmd) + "' for '" + containerId + "'");

            final ExecCreateCmdResponse response = connect.execCreateCmd(containerId)
                    .withTty()
                    .withAttachStdin()
                    .withAttachStderr()
                    .withAttachStdout()
//                    .withCmd(connectCmd)
                    .withCmd("/bin/bash", "-cxe", startCmd.replace("$", "\\$"))
                    .exec();

            LOGGER.info("Starting connection command for {}", containerId);
            logger.println("Starting connection command for " + containerId);

            try (InputStream exec = connect
                    .execStartCmd(response.getId())
                    .withDetach()
                    .withTty()
                    .exec()
            ) {
                //nothing, just want to be closed
            } catch (NotFoundException ex) {
                listener.error("Can't execute command: " + ex.getMessage());
                LOGGER.error("Can't execute jnlp connection command: '{}'", ex.getMessage());
            }

        } catch (Exception ex) {
            listener.error("Can't execute command: " + ex.getMessage());
            LOGGER.error("Can't execute jnlp connection command: '{}'", ex.getMessage());
            throw ex;
        }

        LOGGER.info("Successfully executed jnlp connection for '{}'", containerId);
        logger.println("Successfully executed jnlp connection for " + containerId);

        final long launchTime = System.currentTimeMillis();
        while (!dockerComputer.isOnline() &&
                TimeUnit2.SECONDS.toMillis(launchTimeout) > System.currentTimeMillis() - launchTime) {
            logger.println("Waiting slave connection...");
            Thread.sleep(1000);
        }

        if (!dockerComputer.isOnline()) {
            LOGGER.info("Launch timeout, termintaing slave based on '{}'", containerId);
            logger.println("Launch timeout, termintaing slave.");
            dockerComputer.getNode().terminate();
            throw new IOException("Can't connect slave to jenkins");
        }

        LOGGER.info("Launched slave '{}' based on '{}'", dockerComputer.getName(), containerId);
        logger.println("Launched slave for " + containerId);
    }

    @Override
    public ComputerLauncher getPreparedLauncher(String dockerHostUrl, DockerTemplate template, InspectContainerResponse containerInspectResponse) {
        return new DockerComputerJNLPLauncher(getJnlpLauncher());
    }

    @Override
    public void appendContainerConfig(DockerTemplate dockerTemplate, CreateContainerCmd createContainerCmd) throws IOException {

        try (InputStream istream = DockerComputerJNLPLauncher.class.getResourceAsStream("DockerComputerJNLPLauncher/init.sh")) {
            final String initCmd = IOUtils.toString(istream, Charsets.UTF_8);
            if (initCmd == null) {
                throw new IllegalStateException("Resource file 'init.sh' not found");
            }
//            createContainerCmd.withCmd("/bin/sh"); // nop
            // wait for params
            createContainerCmd.withCmd("/bin/bash",
                    "-cxe",
                    "cat << EOF >> /tmp/init.sh && chmod +x /tmp/init.sh && exec /tmp/init.sh\n" +
                            initCmd.replace("$", "\\$") + "\n" +
                            "EOF" + "\n"
            );
        }

//        final String homeDir = dockerTemplate.getRemoteFs();
//        if (isNotBlank(homeDir)) {
//            createContainerCmd.withWorkingDir(homeDir);
//        }
//
//        final String user = dockerTemplate.getUser();
//        if (isNotBlank(user)) {
//            createContainerCmd.withUser(user);
//        }

        createContainerCmd.withTty(true);
        createContainerCmd.withStdinOpen(true);

    }

    @Override
    public boolean waitUp(String dockerHostUrl, DockerTemplate dockerTemplate, InspectContainerResponse ir) {
        return super.waitUp(dockerHostUrl, dockerTemplate, ir);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        public Class getJNLPLauncher() {
            return JNLPLauncher.class;
        }

        @Override
        public String getDisplayName() {
            return "(Experimental) Docker JNLP launcher";
        }
    }
}
