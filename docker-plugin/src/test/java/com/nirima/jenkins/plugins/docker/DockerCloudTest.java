package com.nirima.jenkins.plugins.docker;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DockerClientConfig;
import com.nirima.jenkins.plugins.docker.client.ClientBuilderForPlugin;
import com.nirima.jenkins.plugins.docker.launcher.DockerComputerLauncher;
import com.nirima.jenkins.plugins.docker.provisioning.DockerHostFinder;

import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.LabelFinder;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProvisioner.PlannedNode;
import jenkins.model.Jenkins;

/**
 * @author Kanstantsin Shautsou
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ Jenkins.class, ClientBuilderForPlugin.class, ExtensionList.class })
public class DockerCloudTest {

    private static final String DOCKER_CLOUD_NAME = "docker";
    private static final String CONTAINER_ID = "containerId1";
    private static final String DOCKER_HOST_URL = "http://docker.host:2375";
    private static final String DOCKER_HOST_URL2 = "https://docker2.host:2376";
    private static final int CONTAINER_CAP = 2;

    private DockerClient dockerClientMock;

    @Before
    @SuppressWarnings("unchecked")
    public void before() {
        Hudson hudsonMock = mock(Hudson.class);
        // called by Label#parse(String)
        when(hudsonMock.getLabelAtom("label1")).thenReturn(new LabelAtom("label1"));
        // self-label for the container
        String slaveName = DockerSlave.makeUniqueName(DOCKER_CLOUD_NAME, DOCKER_HOST_URL, CONTAINER_ID);
        when(hudsonMock.getLabelAtom(slaveName)).thenReturn(new LabelAtom(slaveName));
        // called by ExtensionList#lookup()
        ExtensionList<LabelFinder> extensionListMock = mock(ExtensionList.class);
        when(hudsonMock.getExtensionList(LabelFinder.class)).thenReturn(extensionListMock);
        LabelFinder labelFinder = new LabelFinder() {
            @Override
            public Collection<LabelAtom> findLabels(Node node) {
                return Collections.emptyList();
            }
        };
        when(extensionListMock.iterator()).thenReturn(Collections.singletonList(labelFinder).iterator());

        // mock Jenkins#getInstance()
        mockStatic(Jenkins.class);
        when(Jenkins.getInstance()).thenReturn(hudsonMock);

        // mock ClientBuilderForPlugin#getInstance()
        mockStatic(ClientBuilderForPlugin.class);
        ClientBuilderForPlugin dockerClientBuilder = mock(ClientBuilderForPlugin.class);
        when(ClientBuilderForPlugin.getInstance(any(DockerClientConfig.class))).thenReturn(dockerClientBuilder);
        dockerClientMock = mock(DockerClient.class);
        when(dockerClientBuilder.build()).thenReturn(dockerClientMock);

        // mock Computer#threadPoolForRemoting
        ExecutorService executorServiceMock = mock(ExecutorService.class);
        setFinalStatic(Computer.class, "threadPoolForRemoting", executorServiceMock);
        when(executorServiceMock.submit(any(Callable.class))).thenAnswer(new Answer<Future<Node>>() {
            @Override
            public Future<Node> answer(InvocationOnMock invocation) throws Throwable {
                Callable<Node> callable = invocation.getArgumentAt(0, Callable.class);
                return new FutureTask<Node>(callable);
            }
        });
    }

    @Test
    public void provision_templateExistsForLabel() throws Exception {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.<Container>emptyList());

        DockerCloud cloud = newCloud("label1");
        Label label = new LabelAtom("label1");
        Collection<PlannedNode> nodes = cloud.provision(label, 1);
        assertEquals(1, nodes.size());
        assertEquals(cloud.getTemplates().get(0).getDockerTemplateBase().getDisplayName(),
                     nodes.iterator().next().displayName);
    }

    @Test
    public void provision_oneDockerHost_containersFull() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        List<Container> containers = new ArrayList<>();
        for (int i = 0; i < CONTAINER_CAP; i++) {
            containers.add(new Container());
        }
        when(listContainersCmd.exec()).thenReturn(containers);

        DockerCloud cloud = newCloud("label1");
        DockerHostFinder dockerHostFinder = mock(DockerHostFinder.class);
        when(dockerHostFinder.findDockerHostUrls()).thenReturn(Collections.singleton(DOCKER_HOST_URL));
        Whitebox.setInternalState(cloud, DockerHostFinder.class, dockerHostFinder);

        Label label = new LabelAtom("label1");
        Collection<PlannedNode> nodes = cloud.provision(label, 1);
        assertEquals(0, nodes.size());
    }

    @Test
    public void provision_oneDockerHost_overProvisioned() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.<Container>emptyList());

        DockerCloud cloud = newCloud("label1");
        DockerHostFinder dockerHostFinder = mock(DockerHostFinder.class);
        when(dockerHostFinder.findDockerHostUrls()).thenReturn(Collections.singleton(DOCKER_HOST_URL));
        Whitebox.setInternalState(cloud, DockerHostFinder.class, dockerHostFinder);
        Map<String, Integer> privisionedContainerCounts = Whitebox.getInternalState(DockerCloud.class,
                                                                                    "PROVISIONED_CONTAINER_COUNTS");
        privisionedContainerCounts.put(DOCKER_HOST_URL, CONTAINER_CAP);

        try {
            Label label = new LabelAtom("label1");
            Collection<PlannedNode> nodes = cloud.provision(label, 1);
            assertEquals(0, nodes.size());
        } finally {
            // ensure the static field is reset for other tests
            privisionedContainerCounts.clear();
        }
    }

    @Test
    public void provision_twoDockerHosts_firstOverProvisioned_secondUnderprovisioned() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.<Container>emptyList());

        DockerCloud cloud = newCloud("label1");
        DockerHostFinder dockerHostFinder = mock(DockerHostFinder.class);
        List<String> dockerHostUrls = new ArrayList<>();
        dockerHostUrls.add(DOCKER_HOST_URL);
        dockerHostUrls.add(DOCKER_HOST_URL2);
        when(dockerHostFinder.findDockerHostUrls()).thenReturn(dockerHostUrls);
        Whitebox.setInternalState(cloud, DockerHostFinder.class, dockerHostFinder);
        Map<String, Integer> privisionedContainerCounts = Whitebox.getInternalState(DockerCloud.class,
                                                                                    "PROVISIONED_CONTAINER_COUNTS");
        privisionedContainerCounts.put(DOCKER_HOST_URL, CONTAINER_CAP);

        try {
            Label label = new LabelAtom("label1");
            Collection<PlannedNode> nodes = cloud.provision(label, 1);
            assertEquals(1, nodes.size());
            assertEquals(cloud.getTemplates().get(0).getDockerTemplateBase().getDisplayName(),
                         nodes.iterator().next().displayName);
        } finally {
            // ensure the static field is reset for other tests
            privisionedContainerCounts.clear();
        }
    }

    @Test
    public void provision_twoDockerHosts_firstOverProvisioned_secondOverprovisioned() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.<Container>emptyList());

        DockerCloud cloud = newCloud("label1");
        DockerHostFinder dockerHostFinder = mock(DockerHostFinder.class);
        List<String> dockerHostUrls = new ArrayList<>();
        dockerHostUrls.add(DOCKER_HOST_URL);
        dockerHostUrls.add(DOCKER_HOST_URL2);
        when(dockerHostFinder.findDockerHostUrls()).thenReturn(dockerHostUrls);
        Whitebox.setInternalState(cloud, DockerHostFinder.class, dockerHostFinder);
        Map<String, Integer> privisionedContainerCounts = Whitebox.getInternalState(DockerCloud.class,
                                                                                    "PROVISIONED_CONTAINER_COUNTS");
        privisionedContainerCounts.put(DOCKER_HOST_URL, CONTAINER_CAP);
        privisionedContainerCounts.put(DOCKER_HOST_URL2, CONTAINER_CAP);

        try {
            Label label = new LabelAtom("label1");
            Collection<PlannedNode> nodes = cloud.provision(label, 1);
            assertEquals(0, nodes.size());
        } finally {
            // ensure the static field is reset for other tests
            privisionedContainerCounts.clear();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void provision_templateNotExistForLabel() throws Exception {
        DockerCloud cloud = newCloud("label1");
        Label label = new LabelAtom("label2");
        cloud.provision(label, 1);
    }

    @Test
    public void provisionWithWait_sunnyCase() throws Exception {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(dockerClientMock.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.<Container>emptyList());

        // called by #provisionWithWait()
        ListImagesCmd listImagesCmd = mock(ListImagesCmd.class);
        when(dockerClientMock.listImagesCmd()).thenReturn(listImagesCmd);
        when(listImagesCmd.exec()).thenReturn(Collections.<Image>emptyList());

        // called by #pullImage()
        PullImageCmd pullImageCmd = mock(PullImageCmd.class);
        when(dockerClientMock.pullImageCmd(any(String.class))).thenReturn(pullImageCmd);
        when(pullImageCmd.exec()).thenReturn(new ByteArrayInputStream(new byte[0]));

        // called by #runContainer()
        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class);
        when(dockerClientMock.createContainerCmd(any(String.class))).thenReturn(createContainerCmd);
        CreateContainerResponse createContainerResponse = new CreateContainerResponse();
        createContainerResponse.setId(CONTAINER_ID);
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);

        // called by #runContainer()
        StartContainerCmd startContainerCmd = mock(StartContainerCmd.class);
        when(dockerClientMock.startContainerCmd(eq(CONTAINER_ID))).thenReturn(startContainerCmd);
        when(startContainerCmd.exec()).thenReturn(null);

        // called by #provisionWithWait()
        InspectContainerCmd inspectContainerCmd = mock(InspectContainerCmd.class);
        when(dockerClientMock.inspectContainerCmd(eq(CONTAINER_ID))).thenReturn(inspectContainerCmd);
        InspectContainerResponse inspectContainerResponse = new InspectContainerResponse();
        ContainerState containerState = inspectContainerResponse.new ContainerState();
        Whitebox.setInternalState(containerState, "running", true);
        Whitebox.setInternalState(inspectContainerResponse, "state", containerState);
        when(inspectContainerCmd.exec()).thenReturn(inspectContainerResponse);

        DockerCloud cloud = newCloud("label1");
        Label label = new LabelAtom("label1");
        Collection<PlannedNode> nodes = cloud.provision(label, 1);
        assertEquals(1, nodes.size());
        assertEquals(cloud.getTemplates().get(0).getDockerTemplateBase().getDisplayName(),
                     nodes.iterator().next().displayName);
        FutureTask<Node> future = (FutureTask<Node>) nodes.iterator().next().future;
        future.run();
        DockerSlave slave = (DockerSlave) future.get();
        assertEquals(cloud.getTemplates().get(0), slave.dockerTemplate);
    }

    @Test
    public void getTemplates_existingLabel() throws Exception {
        DockerCloud cloud = newCloud("label1");
        Label label = new LabelAtom("label1");
        List<DockerTemplate> templatesForLabel = cloud.getTemplates(label);
        assertEquals(1, templatesForLabel.size());
        assertEquals(cloud.getTemplates().get(0), templatesForLabel.get(0));
    }

    @Test
    public void getTemplates_noExistingLabel() throws Exception {
        List<DockerTemplate> templates = new ArrayList<>();
        DockerCloud cloud = new DockerCloud(DOCKER_CLOUD_NAME, templates, "docker-host-label", "http://localhost", 100,
            10, 10, null, null);
        Label label = new LabelAtom("label1");
        List<DockerTemplate> templatesForLabel = cloud.getTemplates(label);
        assertEquals(0, templatesForLabel.size());
    }

    private DockerCloud newCloud(String templateLabel) {
        DockerTemplate template1 = newTemplate(templateLabel);
        List<DockerTemplate> templates = Collections.singletonList(template1);
        return new DockerCloud(DOCKER_CLOUD_NAME, templates, "docker-host-label", DOCKER_HOST_URL, CONTAINER_CAP, 10,
            10, null, null);
    }

    private DockerTemplate newTemplate(String label) {
        DockerTemplateBase base = new DockerTemplateBase("image-" + label, null, null, null, null, null, null, null,
            null, null, null, false, false, false, null);
        DockerTemplate template = new DockerTemplate(base, label, null, null);
        template.setLauncher(new DockerComputerLauncher() {

            @Override
            public ComputerLauncher getPreparedLauncher(String dockerHostUrl, DockerTemplate dockerTemplate,
                InspectContainerResponse ir) {
                return this;
            }

            @Override
            public void appendContainerConfig(DockerTemplate dockerTemplate, CreateContainerCmd createContainerCmd)
                throws IOException {}

        });
        return template;
    }

    private static void setFinalStatic(Class<?> clazz, String fieldName, Object newValue) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);

            // remove final modifier from field
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, newValue);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to set final static field '" + fieldName + "' for class '" + clazz.getName() + "'", e);
        }
    }
}
