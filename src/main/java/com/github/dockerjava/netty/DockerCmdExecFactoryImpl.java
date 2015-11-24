package com.github.dockerjava.netty;

import static com.google.common.base.Preconditions.checkNotNull;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.UnixChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Security;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.command.AttachContainerCmd;
import com.github.dockerjava.api.command.AuthCmd;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.CommitCmd;
import com.github.dockerjava.api.command.ContainerDiffCmd;
import com.github.dockerjava.api.command.CopyFileFromContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateImageCmd;
import com.github.dockerjava.api.command.DockerCmdExecFactory;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PauseContainerCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.SaveImageCmd;
import com.github.dockerjava.api.command.SearchImagesCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.TagImageCmd;
import com.github.dockerjava.api.command.TopContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.ExecCreateCmdImpl;
import com.github.dockerjava.core.command.ExecStartCmdImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.InfoCmdImpl;
import com.github.dockerjava.netty.exec.AuthCmdExec;
import com.github.dockerjava.netty.exec.CopyFileFromContainerCmdExec;
import com.github.dockerjava.netty.exec.CreateContainerCmdExec;
import com.github.dockerjava.netty.exec.ExecCreateCmdExec;
import com.github.dockerjava.netty.exec.ExecStartCmdExec;
import com.github.dockerjava.netty.exec.InfoCmdExec;
import com.github.dockerjava.netty.exec.InspectContainerCmdExec;
import com.github.dockerjava.netty.exec.InspectExecCmdExec;
import com.github.dockerjava.netty.exec.InspectImageCmdExec;
import com.github.dockerjava.netty.exec.KillContainerCmdExec;
import com.github.dockerjava.netty.exec.ListContainersCmdExec;
import com.github.dockerjava.netty.exec.LogContainerCmdExec;
import com.github.dockerjava.netty.exec.RemoveContainerCmdExec;
import com.github.dockerjava.netty.exec.StartContainerCmdExec;
import com.github.dockerjava.netty.exec.StopContainerCmdExec;
import com.github.dockerjava.netty.exec.WaitContainerCmdExec;

/**
 * http://stackoverflow.com/questions/33296749/netty-connect-to-unix-domain- socket-failed
 * http://netty.io/wiki/native-transports.html
 * https://github.com/netty/netty/blob/master/example/src/main/java/io/netty/ example/http/snoop/HttpSnoopClient.java
 *
 * @author marcus
 *
 */
public class DockerCmdExecFactoryImpl implements DockerCmdExecFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerCmdExecFactoryImpl.class.getName());

    private DockerClientConfig dockerClientConfig;

    private Bootstrap bootstrap;

    private EventLoopGroup eventLoopGroup;

    private NettyInitializer nettyInitializer;

    private ChannelProvider channelProvider = new ChannelProvider() {
        @Override
        public Channel getChannel() {
            Channel channel = connect();
            channel.pipeline().addLast(new LoggingHandler(getClass()));
            return channel;
        }
    };

    @Override
    public void init(DockerClientConfig dockerClientConfig) {
        checkNotNull(dockerClientConfig, "config was not specified");
        this.dockerClientConfig = dockerClientConfig;

        bootstrap = new Bootstrap();

        String scheme = dockerClientConfig.getUri().getScheme();

        if ("unix".equals(scheme)) {
            nettyInitializer = new UnixDomainSocketInitializer();
        } else if (scheme.startsWith("http")) {
            nettyInitializer = new InetSocketInitializer();
        }

        nettyInitializer.init(bootstrap, dockerClientConfig);
    }

    private Channel connect() {
        try {
            return connect(bootstrap);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Channel connect(final Bootstrap bootstrap) throws InterruptedException {
        return nettyInitializer.connect(bootstrap);
    }

    private static interface NettyInitializer {
        EventLoopGroup init(final Bootstrap bootstrap, DockerClientConfig dockerClientConfig);

        Channel connect(final Bootstrap bootstrap) throws InterruptedException;
    }

    private class UnixDomainSocketInitializer implements NettyInitializer {
        @Override
        public EventLoopGroup init(Bootstrap bootstrap, DockerClientConfig dockerClientConfig) {
            EventLoopGroup eventLoopGroup = new EpollEventLoopGroup();
            bootstrap.group(eventLoopGroup).channel(EpollDomainSocketChannel.class)
                    .handler(new ChannelInitializer<UnixChannel>() {
                        @Override
                        protected void initChannel(final UnixChannel channel) throws Exception {
                            channel.pipeline().addLast(new HttpClientCodec());
                        }
                    });
            return eventLoopGroup;
        }

        @Override
        public Channel connect(Bootstrap bootstrap) throws InterruptedException {
            return bootstrap.connect(new DomainSocketAddress("/var/run/docker.sock")).sync().channel();
        }
    }

    private class InetSocketInitializer implements NettyInitializer {
        @Override
        public EventLoopGroup init(Bootstrap bootstrap, final DockerClientConfig dockerClientConfig) {
            EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

            InetAddress addr = InetAddress.getLoopbackAddress();

            final SocketAddress proxyAddress = new InetSocketAddress(addr, 8008);

            Security.addProvider(new BouncyCastleProvider());

            bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel channel) throws Exception {
                            // channel.pipeline().addLast(new
                            // HttpProxyHandler(proxyAddress));
                            channel.pipeline().addLast(new HttpClientCodec());
                        }
                    });

            return eventLoopGroup;
        }

        @Override
        public Channel connect(Bootstrap bootstrap) throws InterruptedException {
            String host = dockerClientConfig.getUri().getHost();
            int port = dockerClientConfig.getUri().getPort();

            if (port == -1) {
                throw new RuntimeException("no port configured for " + host);
            }

            Channel channel = bootstrap.connect(host, port).sync().channel();

            if ("https".equals(dockerClientConfig.getUri().getScheme())) {
                final SslHandler ssl = initSsl(dockerClientConfig);

                if (ssl != null) {
                    channel.pipeline().addFirst(ssl);
                }
            }

            return channel;
        }

        private SslHandler initSsl(DockerClientConfig dockerClientConfig) {
            SslHandler ssl = null;

            try {
                String host = dockerClientConfig.getUri().getHost();
                int port = dockerClientConfig.getUri().getPort();

                SSLContext sslContext = dockerClientConfig.getSslConfig().getSSLContext();

                SSLEngine engine = sslContext.createSSLEngine(host, port);
                engine.setUseClientMode(true);
                engine.setSSLParameters(enableHostNameVerification(engine.getSSLParameters()));

                // in the future we may use HostnameVerifier like here:
                // https://github.com/AsyncHttpClient/async-http-client/blob/1.8.x/src/main/java/com/ning/http/client/providers/netty/NettyConnectListener.java#L76

                ssl = new SslHandler(engine);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return ssl;
        }
    }

    protected DockerClientConfig getDockerClientConfig() {
        checkNotNull(dockerClientConfig,
                "Factor not initialized, dockerClientConfig not set. You probably forgot to call init()!");
        return dockerClientConfig;
    }

    public SSLParameters enableHostNameVerification(SSLParameters sslParameters) {
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        return sslParameters;
    }

    @Override
    public AuthCmd.Exec createAuthCmdExec() {
        return new AuthCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public InfoCmd.Exec createInfoCmdExec() {
        return new InfoCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public PingCmd.Exec createPingCmdExec() {
        return null; // new PingCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public VersionCmd.Exec createVersionCmdExec() {
        return null; // new VersionCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public PullImageCmd.Exec createPullImageCmdExec() {
        return null; // new PullImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public PushImageCmd.Exec createPushImageCmdExec() {
        return null; // new PushImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public SaveImageCmd.Exec createSaveImageCmdExec() {
        return null; // new SaveImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public CreateImageCmd.Exec createCreateImageCmdExec() {
        return null; // new CreateImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public SearchImagesCmd.Exec createSearchImagesCmdExec() {
        return null; // new SearchImagesCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public RemoveImageCmd.Exec createRemoveImageCmdExec() {
        return null; // new RemoveImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public ListImagesCmd.Exec createListImagesCmdExec() {
        return null; // new ListImagesCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public InspectImageCmd.Exec createInspectImageCmdExec() {
        return new InspectImageCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public ListContainersCmd.Exec createListContainersCmdExec() {
        return new ListContainersCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public CreateContainerCmd.Exec createCreateContainerCmdExec() {
        return new CreateContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public StartContainerCmd.Exec createStartContainerCmdExec() {
        return new StartContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public InspectContainerCmd.Exec createInspectContainerCmdExec() {
        return new InspectContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public ExecCreateCmd.Exec createExecCmdExec() {
        return new ExecCreateCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public RemoveContainerCmd.Exec createRemoveContainerCmdExec() {
        return new RemoveContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public WaitContainerCmd.Exec createWaitContainerCmdExec() {
        return new WaitContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public AttachContainerCmd.Exec createAttachContainerCmdExec() {
        return null; // new AttachContainerCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public ExecStartCmd.Exec createExecStartCmdExec() {
        return new ExecStartCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public InspectExecCmd.Exec createInspectExecCmdExec() {
        return new InspectExecCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public LogContainerCmd.Exec createLogContainerCmdExec() {
        return new LogContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public CopyFileFromContainerCmd.Exec createCopyFileFromContainerCmdExec() {
        return new CopyFileFromContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public StopContainerCmd.Exec createStopContainerCmdExec() {
        return new StopContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public ContainerDiffCmd.Exec createContainerDiffCmdExec() {
        return null; // new ContainerDiffCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public KillContainerCmd.Exec createKillContainerCmdExec() {
        return new KillContainerCmdExec(getBaseResource(), getDockerClientConfig());
    }

    @Override
    public RestartContainerCmd.Exec createRestartContainerCmdExec() {
        return null; // new RestartContainerCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public CommitCmd.Exec createCommitCmdExec() {
        return null; // new CommitCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public BuildImageCmd.Exec createBuildImageCmdExec() {
        return null; // new BuildImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public TopContainerCmd.Exec createTopContainerCmdExec() {
        return null; // new TopContainerCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public TagImageCmd.Exec createTagImageCmdExec() {
        return null; // new TagImageCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public PauseContainerCmd.Exec createPauseContainerCmdExec() {
        return null; // new PauseContainerCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public UnpauseContainerCmd.Exec createUnpauseContainerCmdExec() {
        return null; // new UnpauseContainerCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public EventsCmd.Exec createEventsCmdExec() {
        return null; // new EventsCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public StatsCmd.Exec createStatsCmdExec() {
        return null; // new StatsCmdExec(getBaseResource(),
                     // getDockerClientConfig());
    }

    @Override
    public void close() throws IOException {
        // checkNotNull(client, "Factory not initialized. You probably forgot to
        // call init()!");
        // client.close();

        eventLoopGroup.shutdownGracefully();
    }

    private WebTarget getBaseResource() {
        return new WebTarget(channelProvider);
    }

    public static void main(String[] args) throws IOException {
        DockerCmdExecFactory execFactory = new DockerCmdExecFactoryImpl();
        // execFactory.init(new
        // DockerClientConfigBuilder().withUri("unix:///var/run/docker.sock").build());
        // execFactory.init(new DockerClientConfigBuilder().withUri("http://localhost:2375").build());

        DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
                .withUri("https://192.168.59.103:2376").build();

        execFactory.init(config);

        InfoCmd.Exec exec = execFactory.createInfoCmdExec();

        InfoCmd infoCmd = new InfoCmdImpl(exec);

        Info info = infoCmd.exec();

        System.out.println("result: " + info);

        infoCmd.close();

        ExecCreateCmd.Exec execCreate = execFactory.createExecCmdExec();

        ExecCreateCmd execCreateCmd = new ExecCreateCmdImpl(execCreate, "test").withCmd("/bin/bash")
                .withAttachStdout(true).withAttachStderr(true).withAttachStdin(true);

        ExecCreateCmdResponse execCreateCmdResponse = execCreate.exec(execCreateCmd);

        System.out.println("result: " + execCreateCmdResponse);

        ExecStartCmd.Exec execStart = execFactory.createExecStartCmdExec();

        ExecStartCmd execStartCmd = new ExecStartCmdImpl(execStart, execCreateCmdResponse.getId()).withDetach(false)
                .withTty(true);

        ExecStartResultCallback callback = new ExecStartResultCallback(System.out, System.err);

        execStart.exec(execStartCmd, callback);

        try {
            callback.awaitCompletion();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        System.err.println("main finished");

        // FrameReader frameReader = new FrameReader(response);
        //
        // System.out.println("read frame");
        // Frame frame = frameReader.readFrame();
        //
        // while(frame != null) {
        // System.out.println("frame: " + frame);
        // frame = frameReader.readFrame();
        // }
        // System.out.println("all frames read");
        //
        // System.out.flush();

        // System.out.println("response: " + IOUtils.toString(response,
        // "UTF-8"));

        // execFactory.close();
    }

}