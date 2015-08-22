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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nirima.jenkins.plugins.docker.utils.PortUtils;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.slaves.SlaveComputer;

/**
 * A utility class for finding a good provisioned docker host with a fallback.
 * 
 * @author tli
 *
 */
public class DockerHostFinder {

    private static final Logger LOG = LoggerFactory.getLogger(DockerHostFinder.class);

    private final Label dockerHostLabel;
    private final int dockerHostPort;
    private final String fallbackHost;

    public DockerHostFinder(Label dockerHostLabel, String fallbackHostUrl) {
        this.dockerHostLabel = dockerHostLabel;
        URI fallbackHostUri = URI.create(fallbackHostUrl);
        this.fallbackHost = fallbackHostUri.getHost();
        this.dockerHostPort = fallbackHostUri.getPort();
    }

    public String findDockerHost() {
        // wait until host is ready or timeout
        long startTime = System.currentTimeMillis();
        boolean hostReady = false;
        String dockerHost = null;
        while (!hostReady) {
            if (System.currentTimeMillis() - startTime > 2 * 60 * 1000) {
                LOG.info("Provisioning of docker host took too long, will use fallback host. dockerHostLabel=\"{}\"",
                         dockerHostLabel.getDisplayName());
                break;
            }

            if (dockerHost == null) {
                dockerHost = getFirstProvisionedDockerHost();
            }
            if (dockerHost != null) {
                hostReady = tryConnect(dockerHost);
            } else {
                PortUtils.sleepFor(10, TimeUnit.SECONDS);
            }
        }

        return hostReady ? dockerHost : fallbackHost;
    }

    private boolean tryConnect(String host) {
        LOG.info("Trying to connect to {}:{}...", host, dockerHostPort);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, dockerHostPort), 10000);
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to connect to {}:{}", host, dockerHostPort);
            return false;
        }
    }

    // public static boolean isEC2PluginInstalled() {
    // Plugin plugin = Jenkins.getActiveInstance().getPlugin("ec2");
    // if (plugin != null) {
    // LOG.info("Found ec2 plugin version={} displayName=\"{}\"", plugin.getWrapper().getVersion(),
    // plugin.getWrapper().getDisplayName());
    // }
    // return plugin != null;
    // }

    // TODO: return a list of docker hosts and choose docker hosts properly according to
    // containerCap per host
    private String getFirstProvisionedDockerHost() {
        for (Node node : dockerHostLabel.getNodes()) {
            if (node instanceof EC2AbstractSlave) {
                EC2AbstractSlave ec2Slave = (EC2AbstractSlave) node;
                SlaveComputer computer = ec2Slave.getComputer();
                if (computer != null && computer.isOnline()) {
                    return ec2Slave.getPrivateDNS();
                }
            }
        }
        return null;
    }

}
