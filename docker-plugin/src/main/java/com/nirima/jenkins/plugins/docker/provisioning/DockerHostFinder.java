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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nirima.jenkins.plugins.docker.utils.PortUtils;

import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.model.Label;
import hudson.model.Node;
import jenkins.model.Jenkins;
import shaded.com.google.common.base.Preconditions;

/**
 * An abstract component for finding provisioned docker hosts with a fallback.
 * 
 * @author tli
 *
 */
public abstract class DockerHostFinder {

    private static final Logger LOG = LoggerFactory.getLogger(DockerHostFinder.class);

    private final Label dockerHostLabel;
    private final int dockerHostPort;
    private final String dockerHostProtocol;
    private final String fallbackHost;

    /**
     * Constructor
     * 
     * @param dockerHostLabel
     *            can be null
     * @param fallbackHostUrl
     *            cannot be null
     */
    public DockerHostFinder(String dockerHostLabel, String fallbackHostUrl) {
        this(dockerHostLabel != null ? Jenkins.getInstance().getLabelAtom(dockerHostLabel) : null, fallbackHostUrl);
    }

    /**
     * Constructor
     * 
     * @param dockerHostLabel
     *            can be null
     * @param fallbackHostUrl
     *            cannot be null
     */
    private DockerHostFinder(Label dockerHostLabel, String fallbackHostUrl) {
        Preconditions.checkNotNull(fallbackHostUrl, "'fallbackHostUrl' must not be null");

        this.dockerHostLabel = dockerHostLabel;
        URI fallbackHostUri = URI.create(fallbackHostUrl);
        this.fallbackHost = fallbackHostUri.getHost();
        this.dockerHostPort = fallbackHostUri.getPort();
        this.dockerHostProtocol = fallbackHostUri.getScheme();
    }

    /**
     * Finds the first ready docker host. Wait is added for securing a ready host. If no dynamically
     * provisioned host is available, returns the fallback host assuming that's ready.
     */
    public String findDockerHost() {
        if (dockerHostLabel == null) {
            return fallbackHost;
        }

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

        // TODO: should test fallback host is online too?
        return hostReady ? dockerHost : fallbackHost;
    }

    /**
     * Finds URLs for all ready docker hosts
     */
    public Collection<String> findDockerHostUrls() {
        List<String> readyDockerHostUrls = new ArrayList<>();
        for (String dockerHost : getAllProvisionedDockerHosts()) {
            if (tryConnect(dockerHost)) {
                readyDockerHostUrls.add(createDockerHostUrl(dockerHost));
            }
        }
        return readyDockerHostUrls;
    }

    /**
     * Resolves the hostname of a node, returns null if not possible.
     */
    protected abstract String resolveNodeHostname(Node node);

    public static boolean isEC2PluginInstalled() {
        Plugin plugin = Jenkins.getInstance().getPlugin("ec2");
        PluginWrapper pluginWrapper = plugin != null ? plugin.getWrapper() : null;
        if (pluginWrapper != null) {
            LOG.info("Found ec2 plugin version={} displayName=\"{}\"", pluginWrapper.getVersion(),
                     pluginWrapper.getDisplayName());
        }
        return plugin != null;
    }

    private String createDockerHostUrl(String dockerHost) {
        return String.format("%s://%s:%d", dockerHostProtocol, dockerHost, dockerHostPort);
    }

    private boolean tryConnect(String host) {
        LOG.info("Trying to connect to {}:{}...", host, dockerHostPort);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, dockerHostPort), 10000);
            LOG.info("Able to connect to {}:{}", host, dockerHostPort);
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to connect to {}:{}", host, dockerHostPort);
            return false;
        }
    }

    private String getFirstProvisionedDockerHost() {
        if (dockerHostLabel != null) {
            for (Node node : dockerHostLabel.getNodes()) {
                String hostname = resolveNodeHostname(node);
                if (hostname != null) {
                    return hostname;
                }
            }
        }
        return null;
    }

    // Returns all provisioned docker hosts including fallback host at the end of the collection
    private Collection<String> getAllProvisionedDockerHosts() {
        List<String> dockerHosts = new ArrayList<>();
        if (dockerHostLabel != null) {
            for (Node node : dockerHostLabel.getNodes()) {
                String hostname = resolveNodeHostname(node);
                if (hostname != null) {
                    dockerHosts.add(hostname);
                }
            }
        }
        // add fallback host to the last
        dockerHosts.add(fallbackHost);
        return dockerHosts;
    }

}
