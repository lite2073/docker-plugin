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
