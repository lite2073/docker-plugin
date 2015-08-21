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
package com.nirima.jenkins.plugins.docker.ec2;

import java.io.IOException;

import org.jenkinsci.remoting.RoleChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.Plugin;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;

public class EC2DockerHostProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(EC2DockerHostProvisioner.class);

    public static boolean isEC2PluginInstalled() {
        Plugin plugin = Jenkins.getActiveInstance().getPlugin("ec2");
        if (plugin != null) {
            LOG.info("Found ec2 plugin version={} displayName=\"{}\"", plugin.getWrapper().getVersion(),
                     plugin.getWrapper().getDisplayName());
        }
        return plugin != null;
    }

    public static EC2Computer getEC2Computer() {
        LabelAtom labelAtom = Jenkins.getActiveInstance().getLabelAtom("azlinux.docker1.6");
        for (Node node : labelAtom.getNodes()) {
            if (node instanceof EC2AbstractSlave) {
                EC2AbstractSlave ec2Slave = (EC2AbstractSlave) node;
                VirtualChannel channel = ec2Slave.getChannel();
                try {
                    channel.call(new Callable<Object, IOException>() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public void checkRoles(RoleChecker checker) throws SecurityException {}

                        @Override
                        public Object call() throws IOException {
                            return null;
                        }

                    });
                } catch (IOException | InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

}
