package org.csanchez.jenkins.plugins.kubernetes;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ClientPodResource;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(KubernetesSlave.class.getName());

    private static final long serialVersionUID = -8642936855413034232L;

    /**
     * The resource bundle reference
     */
    private final static ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);

    private final String cloudName;

    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr)
            throws Descriptor.FormException, IOException {

        this(template, nodeDescription, cloud, labelStr, new OnceRetentionStrategy(cloud.getRetentionTimeout()));
    }

    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, Label label)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud, label.toString(), new OnceRetentionStrategy(cloud.getRetentionTimeout())) ;
    }

    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud.name, labelStr, rs);
    }

    @DataBoundConstructor
    public KubernetesSlave(PodTemplate template, String nodeDescription, String cloudName, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {

        super(getSlaveName(template),
                nodeDescription,
                template.getRemoteFs(),
                1,
                Node.Mode.NORMAL,
                labelStr == null ? null : labelStr,
                new JNLPLauncher(),
                rs,
                template.getNodeProperties());

        // this.pod = pod;
        this.cloudName = cloudName;
    }

    static String getSlaveName(PodTemplate template) {
        String hex = Long.toHexString(System.nanoTime());
        String name = template.getName();
        if (StringUtils.isEmpty(name)) {
            return hex;
        }
        // no spaces
        name = template.getName().replace(" ", "-").toLowerCase();
        // keep it under 256 chars
        name = name.substring(0, Math.min(name.length(), 256 - hex.length()));
        return String.format("%s-%s", name, hex);
    }

    @Override
    public KubernetesComputer createComputer() {
        return new KubernetesComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Kubernetes instance for slave {0}", name);

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for slave is null: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        LOGGER.log(Level.INFO, "Killing slave");
        computer.getChannel().callAsync(new Killer());

        if (cloudName == null) {
            String msg = String.format("Cloud name is not set for slave, can't terminate: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        try {
            Cloud cloud = Jenkins.getInstance().getCloud(cloudName);
            if (cloud == null) {
                String msg = String.format("Slave cloud no longer exists: %s", cloudName);
                LOGGER.log(Level.WARNING, msg);
                listener.fatalError(msg);
                return;
            }
            if (!(cloud instanceof KubernetesCloud)) {
                String msg = String.format("Slave cloud is not a KubernetesCloud, something is very wrong: %s",
                        cloudName);
                LOGGER.log(Level.SEVERE, msg);
                listener.fatalError(msg);
                return;
            }
            KubernetesClient client = ((KubernetesCloud) cloud).connect();
            ClientPodResource<Pod, DoneablePod> pods = client.pods().withName(name);

            Pod p = null;
            LOGGER.log(Level.INFO, "Waiting up to 60 seconds for pod to terminate");
            for (int i = 0; i < 60; i++) {
              p = pods.get();
              if (p == null)
                return;

              if (!p.getStatus().getPhase().equals("Running"))
                break;

              Thread.sleep(1000);
            }

            for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
              ContainerStateTerminated t = cs.getState().getTerminated();
              if (t != null && t.getReason().equals("OOMKilled")) {
                String msg = String.format("Container %s of pod %s was OOMKilled, not deleting pod", cs.getContainerID(), p.getMetadata().getName());
                LOGGER.log(Level.WARNING, msg);
                return;
              }
            }

            pods.delete();
            String msg = String.format("Terminated Kubernetes instance for slave %s", name);
            LOGGER.log(Level.INFO, msg);
            listener.getLogger().println(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            LOGGER.log(Level.INFO, "Disconnected computer {0}", name);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to terminate pod for slave " + name, e);
        }
    }

    @Override
    public String toString() {
        return String.format("KubernetesSlave name: %s", name);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Slave";
        };

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }
}
