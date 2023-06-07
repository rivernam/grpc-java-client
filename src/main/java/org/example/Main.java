package org.example;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.example.helloservice.HelloRequest;
import org.example.helloservice.HelloResponse;
import org.example.helloservice.HelloServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static picocli.CommandLine.Option;

public class Main implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    @Option(names = {"-h", "--host"}, description = "gRPC server address", required = true)
    String host;
    @Option(names = {"-p", "--port"}, description = "grpc server port", required = true)
    int port;
    @Option(names = {"-l", "--loop-count"}, description = "request loop count", defaultValue = "1")
    int loop;
    @Option(names = {"-ps", "--proxy-server"}, description = "proxy server address")
    String proxyServer;
    @Option(names = {"-pp", "--proxy-port"}, description = "proxy server port")
    int proxyPort;
    @Option(names = {"-m", "--mode"}, description = "grpc client request mode. You have to choose between blocking, async and future", required = true)
    String grpcMode;
    @Option(names={"-s", "--sleep"}, defaultValue = "1")
    int sleep;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        logger.info("host : {}, port : {}, loop: {}", host, port, loop);
        logger.info("client start");
        for (int i = 0; i < loop; i++) {
            ManagedChannel channel = buildGrpcChannel();

            logger.info("{} say hello before", i);
            if (grpcMode.equals("block")) {
                grpcBlocking(i, channel);
            } else if (grpcMode.equals("async")) {
                grpcAsync(i, channel);
            } else if (grpcMode.equals("future")){
                grpcFuture(i, channel);
            }
            else{
                throw new IllegalArgumentException("argument \"" + grpcMode + "\" is invalid");
            }
            logger.info("{} say hello after", i);

            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void grpcBlocking(int i, ManagedChannel channel) {
        HelloServiceGrpc.HelloServiceBlockingStub blockingStub = HelloServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        HelloRequest request = HelloRequest.newBuilder().setIndex(i).setRequestTime(LocalDateTime.now().toString()).build();

        HelloResponse response = blockingStub.sayHello(request);
        logger.info(response.getMessage());

        channel.shutdown();
        logger.info("{} channel shutdown", i);
    }

    private void grpcAsync(int i, ManagedChannel channel) {
        HelloServiceGrpc.HelloServiceStub asyncStub = HelloServiceGrpc.newStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        HelloRequest request = HelloRequest.newBuilder().setIndex(i).setRequestTime(LocalDateTime.now().toString()).build();

        asyncStub.sayHello(request, new StreamObserver<HelloResponse>() {
            @Override
            public void onNext(HelloResponse helloResponse) {
                logger.info("on next, {}", helloResponse.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                logger.error("async error", throwable);
            }

            @Override
            public void onCompleted() {
                logger.info("on complete");
                channel.shutdown();
                logger.info("{} channel shutdown", i);
            }
        });
    }

    private void grpcFuture(int i, ManagedChannel channel) {
        HelloServiceGrpc.HelloServiceFutureStub futureStub = HelloServiceGrpc.newFutureStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        HelloRequest request = HelloRequest.newBuilder().setIndex(i).setRequestTime(LocalDateTime.now().toString()).build();
        HelloResponse response;
        try {
            response = futureStub.sayHello(request).get(3, TimeUnit.SECONDS);
            logger.info(response.getMessage());
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("future get exception", e);
        }
        channel.shutdown();
        logger.info("{} channel shutdown", i);
    }

    private ManagedChannel buildGrpcChannel() {
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext();
        if (Objects.nonNull(proxyServer) && !proxyServer.isEmpty() && proxyPort != 0) {
            configureProxyServer(channelBuilder);
        }
        return channelBuilder.build();
    }

    private void configureProxyServer(ManagedChannelBuilder<?> channelBuilder) {
        try {
            InetSocketAddress proxyAddress = new InetSocketAddress(proxyServer, proxyPort);
            channelBuilder.proxyDetector(new ProxyDetector() {
                @Nullable
                @Override
                public ProxiedSocketAddress proxyFor(SocketAddress targetServerAddress) {
                    return HttpConnectProxiedSocketAddress.newBuilder()
                            .setTargetAddress((InetSocketAddress) targetServerAddress)
                            .setProxyAddress(proxyAddress)
                            .build();
                }
            });
        } catch (Exception e) {
            logger.error("proxy configure fail!!!!", e);
        }
    }
}