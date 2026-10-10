package dev.uas.netty;

import dev.uas.netty.http.PackHttpRouter;
import dev.uas.netty.http.SniffStats;
import dev.uas.netty.http.UasHttpConfig;
import dev.uas.netty.memory.NettyAllocatorTuning;
import dev.uas.netty.pipeline.MultiplexerInstaller;
import dev.uas.netty.pipeline.ServerChannelHook;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A complete Netty server with the UAS multiplexer installed.
 *
 * <p>Two uses:
 *
 * <ul>
 *   <li><strong>Verification.</strong> The integration tests drive this over real
 *       loopback sockets, so the protocol sniffing, the HTTP switch and the chunked
 *       file streaming are exercised through the same code path production uses --
 *       {@code ServerBootstrap} -> acceptor -> child pipeline -- rather than against
 *       a hand-built pipeline that could not prove the hook timing.</li>
 *   <li><strong>Diagnostics.</strong> Operators can start one standalone to check
 *       that a compiled pack is actually streamable without touching the server.</li>
 * </ul>
 *
 * <p>The vanilla pipeline is supplied by the caller, because that is exactly the
 * thing that differs between Paper versions: the hook must work against whatever
 * handler set the server installs, and the tests assert that by varying it.
 */
public final class AssetStreamServer implements AutoCloseable {

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;
    private final MultiplexerInstaller installer;
    private final Consumer<ChannelPipeline> vanillaPipeline;

    private AssetStreamServer(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Channel serverChannel,
        MultiplexerInstaller installer,
        Consumer<ChannelPipeline> vanillaPipeline) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.serverChannel = serverChannel;
        this.installer = installer;
        this.vanillaPipeline = vanillaPipeline;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public String bindAddress() {
        return ((InetSocketAddress) serverChannel.localAddress()).toString();
    }

    public MultiplexerInstaller installer() {
        return installer;
    }

    public SniffStats stats() {
        return installer.stats();
    }

    /** The vanilla handler consumer, for assertions about what the server installed. */
    public Consumer<ChannelPipeline> vanillaPipeline() {
        return vanillaPipeline;
    }

    /** The listening channel, so tests can inspect the hooked pipeline. */
    public Channel serverChannel() {
        return serverChannel;
    }

    /** True when the child-channel hook is live on the listening channel. */
    public boolean isHooked() {
        return ServerChannelHook.isInstalled(serverChannel);
    }

    /** Detaches the hook; new connections then behave as if UAS were not installed. */
    public boolean unhook() {
        return ServerChannelHook.uninstall(serverChannel);
    }

    @Override
    public void close() {
        serverChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    /** Binds a server; throws if the hook could not be installed, so a silent no-op is impossible. */
    public static final class Builder {
        private String host = "127.0.0.1";
        private int port;
        private UasHttpConfig config = UasHttpConfig.defaults();
        private PackHttpRouter router = PackHttpRouter.empty();
        private Consumer<ChannelPipeline> vanillaPipeline;
        private NettyAllocatorTuning allocatorTuning;
        private Duration hookTimeout = Duration.ofSeconds(5);

        public Builder host(String value) {
            this.host = Objects.requireNonNull(value, "host");
            return this;
        }

        /** Port 0 binds an ephemeral port, which is what tests use. */
        public Builder port(int value) {
            this.port = value;
            return this;
        }

        public Builder config(UasHttpConfig value) {
            this.config = Objects.requireNonNull(value, "config");
            return this;
        }

        public Builder router(PackHttpRouter value) {
            this.router = Objects.requireNonNull(value, "router");
            return this;
        }

        /** The vanilla handlers the server would install for a new connection. */
        public Builder vanillaPipeline(Consumer<ChannelPipeline> value) {
            this.vanillaPipeline = Objects.requireNonNull(value, "vanillaPipeline");
            return this;
        }

        /** When set, child channels are allocated through this tuning's pooled allocator. */
        public Builder allocatorTuning(NettyAllocatorTuning value) {
            this.allocatorTuning = value;
            return this;
        }

        public Builder hookTimeout(Duration value) {
            this.hookTimeout = Objects.requireNonNull(value, "hookTimeout");
            return this;
        }

        public AssetStreamServer bind() throws InterruptedException {
            if (vanillaPipeline == null) {
                throw new IllegalStateException(
                    "vanillaPipeline must be supplied: it is what the hook is verified against");
            }
            MultiplexerInstaller installer = new MultiplexerInstaller(config, router);
            EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            try {
                ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 64)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            vanillaPipeline.accept(channel.pipeline());
                        }
                    });
                if (allocatorTuning != null) {
                    bootstrap.childOption(ChannelOption.ALLOCATOR, allocatorTuning.newAllocator());
                }
                Channel serverChannel = bootstrap
                    .bind(new InetSocketAddress(host, port))
                    .sync()
                    .channel();
                boolean installed =
                    ServerChannelHook.install(serverChannel, installer, hookTimeout);
                if (!installed) {
                    serverChannel.close().syncUninterruptibly();
                    throw new IllegalStateException(
                        "could not install the UAS child-channel hook on " + serverChannel);
                }
                return new AssetStreamServer(boss, worker, serverChannel, installer, vanillaPipeline);
            } catch (InterruptedException | IllegalStateException e) {
                boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
                worker.shutdownGracefully(0, 1, TimeUnit.SECONDS);
                throw e;
            }
        }
    }
}
