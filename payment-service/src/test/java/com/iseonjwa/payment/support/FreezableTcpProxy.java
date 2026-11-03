package com.iseonjwa.payment.support;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 DB 앞에 두는 TCP 중계기. {@link #freeze()}하면 연결을 닫지 않고 양방향 전달만 멈춘다. DB가 요청을 받지도 답하지도
 * 않는 {@code docker pause}나 네트워크 단절과 같은 모양이다(연결은 열려 있는데 아무것도 오가지 않는다).
 *
 * <p>Note: 멈춘 동안 이미 읽은 바이트는 버리지 않고 쥐고 있다가 {@link #thaw()}에서 전달한다. 클라이언트가 시한을 넘겨 먼저 끊은
 * 연결이면 전달 뒤 끊김을 읽어 양쪽을 닫는다. 이 동작이 실제 일시 정지에서 풀린 서버가 밀린 요청을 받고 끊긴 연결을
 * 정리하는 모습과 같다.
 */
public final class FreezableTcpProxy implements Closeable {
    private static final int BUFFER_SIZE = 8192;

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Object gate = new Object();
    private volatile boolean frozen;
    private volatile boolean closed;

    public FreezableTcpProxy(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        try {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("프록시 포트를 열지 못했다", e);
        }
        daemon("proxy-accept", this::acceptLoop);
    }

    /** 프록시가 듣는 포트. 테스트 DB URL의 호스트·포트 자리에 쓴다. */
    public int port() {
        return server.getLocalPort();
    }

    /** 연결은 두고 양방향 전달을 멈춘다. */
    public void freeze() {
        frozen = true;
    }

    /** 멈췄던 전달을 다시 시작한다. */
    public void thaw() {
        synchronized (gate) {
            frozen = false;
            gate.notifyAll();
        }
    }

    @Override
    public void close() {
        closed = true;
        thaw();
        closeQuietly(server);
        sockets.forEach(FreezableTcpProxy::closeQuietly);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var client = server.accept();
                var upstream = new Socket(targetHost, targetPort);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                sockets.add(client);
                sockets.add(upstream);
                daemon("proxy-up", () -> pump(client, upstream));
                daemon("proxy-down", () -> pump(upstream, client));
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        var buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                awaitThaw();
                if (closed) return;
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // 한쪽이 끊겼다. 아래에서 양쪽을 정리한다
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            sockets.remove(from);
            sockets.remove(to);
        }
    }

    private void awaitThaw() {
        synchronized (gate) {
            while (frozen && !closed) {
                try {
                    gate.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void daemon(String name, Runnable task) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 이미 닫혔다
        }
    }
}
