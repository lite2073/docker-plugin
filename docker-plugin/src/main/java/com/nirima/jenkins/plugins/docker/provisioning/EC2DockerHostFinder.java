package com.nirima.jenkins.plugins.docker.provisioning;

import hudson.model.Node;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.slaves.SlaveComputer;

/**
 * A {@link DockerHostFinder} for finding docker hosts as EC2 instances.
 */
public class EC2DockerHostFinder extends DockerHostFinder {

    public EC2DockerHostFinder(String dockerHostLabel, String fallbackHostUrl) {
        super(dockerHostLabel, fallbackHostUrl);
    }

    @Override
    protected String resolveNodeHostname(Node node) {
        if (node instanceof EC2AbstractSlave) {
            EC2AbstractSlave ec2Slave = (EC2AbstractSlave) node;
            SlaveComputer computer = ec2Slave.getComputer();
            if (computer != null && computer.isOnline()) {
                return ec2Slave.getPrivateDNS();
            }
        }

        return null;
    }

}
