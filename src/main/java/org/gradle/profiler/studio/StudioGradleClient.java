package org.gradle.profiler.studio;

import com.google.common.base.Joiner;
import org.gradle.profiler.BuildAction.BuildActionResult;
import org.gradle.profiler.CommandExec;
import org.gradle.profiler.GradleBuildConfiguration;
import org.gradle.profiler.GradleClient;
import org.gradle.profiler.InvocationSettings;
import org.gradle.profiler.Logging;
import org.gradle.profiler.OperatingSystem;
import org.gradle.profiler.client.protocol.Server;
import org.gradle.profiler.client.protocol.ServerConnection;
import org.gradle.profiler.client.protocol.messages.GradleInvocationCompleted;
import org.gradle.profiler.client.protocol.messages.GradleInvocationParameters;
import org.gradle.profiler.client.protocol.messages.StudioAgentConnectionParameters;
import org.gradle.profiler.client.protocol.messages.StudioRequest;
import org.gradle.profiler.client.protocol.messages.StudioSyncRequestCompleted;
import org.gradle.profiler.studio.tools.StudioPluginInstaller;
import org.gradle.profiler.studio.tools.StudioSandboxCreator;
import org.gradle.profiler.studio.tools.StudioSandboxCreator.StudioSandbox;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.gradle.profiler.client.protocol.messages.StudioRequest.StudioRequestType.EXIT_IDE;
import static org.gradle.profiler.client.protocol.messages.StudioRequest.StudioRequestType.SYNC;

public class StudioGradleClient implements GradleClient {

    private static final Duration AGENT_CONNECT_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration SYNC_STARTED_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration GRADLE_INVOCATION_COMPLETED_TIMEOUT = Duration.ofMinutes(60);
    private static final Duration SYNC_REQUEST_COMPLETED_TIMEOUT = Duration.ofMinutes(60);
    private static final long STUDIO_EXIT_TIMEOUT_SECONDS = 60;

    private final Server studioAgentServer;
    private final Server studioPluginServer;
    private final CommandExec.RunHandle studioProcess;
    private final ServerConnection studioAgentConnection;
    private final ServerConnection studioPluginConnection;
    private final StudioPluginInstaller studioPluginInstaller;

    public StudioGradleClient(GradleBuildConfiguration buildConfiguration, InvocationSettings invocationSettings) {
        if (!OperatingSystem.isMacOS()) {
            throw new IllegalArgumentException("Support for Android studio is currently only implemented on macOS.");
        }
        Path studioInstallDir = invocationSettings.getStudioInstallDir().toPath();
        Optional<File> studioSandboxDir = invocationSettings.getStudioSandboxDir();
        Logging.startOperation("Starting Android Studio at " + studioInstallDir);
        studioPluginServer = new Server("plugin");
        studioAgentServer = new Server("agent");
        StudioSandbox sandbox = StudioSandboxCreator.createSandbox(studioSandboxDir.map(File::toPath).orElse(null));
        LaunchConfiguration launchConfiguration = new LauncherConfigurationParser().calculate(studioInstallDir, sandbox, studioPluginServer.getPort());
        System.out.println();
        System.out.println("* Java command: " + launchConfiguration.getJavaCommand());
        System.out.println("* Classpath:");
        for (Path entry : launchConfiguration.getClassPath()) {
            System.out.println("  " + entry);
        }
        System.out.println("* System properties:");
        for (Map.Entry<String, String> entry : launchConfiguration.getSystemProperties().entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }
        System.out.println("* Main class: " + launchConfiguration.getMainClass());

        studioPluginInstaller = new StudioPluginInstaller(launchConfiguration.getStudioPluginsDir());
        studioProcess = startStudio(launchConfiguration, studioInstallDir, invocationSettings, studioAgentServer);
        studioPluginConnection = studioPluginServer.waitForIncoming(AGENT_CONNECT_TIMEOUT);
        studioAgentConnection = studioAgentServer.waitForIncoming(AGENT_CONNECT_TIMEOUT);
        studioAgentConnection.send(new StudioAgentConnectionParameters(buildConfiguration.getGradleHome()));
    }

    private CommandExec.RunHandle startStudio(LaunchConfiguration launchConfiguration, Path studioInstallDir, InvocationSettings invocationSettings, Server server) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(launchConfiguration.getJavaCommand().toString());
        commandLine.add("-cp");
        commandLine.add(Joiner.on(File.pathSeparator).join(launchConfiguration.getClassPath()));
        for (Map.Entry<String, String> systemProperty : launchConfiguration.getSystemProperties().entrySet()) {
            commandLine.add("-D" + systemProperty.getKey() + "=" + systemProperty.getValue());
        }
        commandLine.add("-javaagent:" + launchConfiguration.getAgentJar() + "=" + server.getPort() + "," + launchConfiguration.getSupportJar());
        commandLine.add("--add-exports");
        commandLine.add("java.base/jdk.internal.misc=ALL-UNNAMED");
        commandLine.add("-Xbootclasspath/a:" + Joiner.on(File.pathSeparator).join(launchConfiguration.getSharedJars()));
        commandLine.add(launchConfiguration.getMainClass());
        commandLine.add(invocationSettings.getProjectDir().getAbsolutePath());
        System.out.println("* Android Studio logs can be found at: " + Paths.get(launchConfiguration.getStudioLogsDir().toString(), "idea.log"));
        System.out.println("* Using command line: " + commandLine);

        studioPluginInstaller.installPlugin(launchConfiguration.getStudioPluginJars());
        return new CommandExec().inDir(studioInstallDir.toFile()).start(commandLine);
    }

    @Override
    public void close() {
        try (Server studioPluginServer = this.studioPluginServer;
             Server studioAgentServer = this.studioAgentServer;
             Closeable uninstallPlugin = studioPluginInstaller::uninstallPlugin) {
            System.out.println("* Stopping Android Studio....");
            studioPluginConnection.send(new StudioRequest(EXIT_IDE));
            studioProcess.waitForSuccess(STUDIO_EXIT_TIMEOUT_SECONDS, SECONDS);
            System.out.println("* Android Studio stopped.");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("* Android Studio did not finish successfully, you will have to close it manually.");
        }
    }

    public BuildActionResult sync(List<String> gradleArgs, List<String> jvmArgs) {
        System.out.println("* Running sync in Android Studio...");
        studioPluginConnection.send(new StudioRequest(SYNC));
        System.out.println("* Sent sync request");
        // Use a long time out because it can take quite some time
        // between the tapi action completing and studio finishing the sync
        studioAgentConnection.receiveSyncStarted(SYNC_STARTED_TIMEOUT);
        studioAgentConnection.send(new GradleInvocationParameters(gradleArgs, jvmArgs));
        System.out.println("* Sync has started, waiting for it to complete...");
        GradleInvocationCompleted agentCompleted = studioAgentConnection.receiveGradleInvocationCompleted(GRADLE_INVOCATION_COMPLETED_TIMEOUT);
        System.out.println("* Gradle invocation has completed in: " + agentCompleted.getDurationMillis() + "ms");
        StudioSyncRequestCompleted syncRequestCompleted = studioPluginConnection.receiveSyncRequestCompleted(SYNC_REQUEST_COMPLETED_TIMEOUT);
        System.out.println("* Full sync has completed in: " + syncRequestCompleted.getDurationMillis() + "ms and it " + syncRequestCompleted.getResult().name().toLowerCase());
        return BuildActionResult.withIdeTimings(
            Duration.ofMillis(syncRequestCompleted.getDurationMillis()),
            Duration.ofMillis(agentCompleted.getDurationMillis()),
            Duration.ofMillis(syncRequestCompleted.getDurationMillis() - agentCompleted.getDurationMillis())
        );
    }
}
