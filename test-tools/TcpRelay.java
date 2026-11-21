import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Task 0037: a plain TCP relay for check scripts, run as a single-file program:
 * {@code java test-tools/TcpRelay.java <listen port> <target host> <target port>}.
 * Test-SDV-CleanInstall.ps1 points Vero at it and stops or starts it to take "the database" away and bring it back,
 * without touching the PostgreSQL container. It listens on 127.0.0.1 only and holds no data.
 */
public final class TcpRelay {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: java TcpRelay.java <listen port> <target host> <target port>");
            System.exit(2);
        }
        int listen = Integer.parseInt(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        try (ServerSocket server = new ServerSocket(listen, 50, InetAddress.getLoopbackAddress())) {
            System.out.println("relay listening on 127.0.0.1:" + listen + " -> " + host + ":" + port);
            while (true) {
                Socket client = server.accept();
                try {
                    Socket target = new Socket(host, port);
                    pipe(client, target);
                    pipe(target, client);
                } catch (IOException e) {
                    client.close();
                }
            }
        }
    }

    private static void pipe(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // the other side closed
            } finally {
                try { from.close(); } catch (IOException ignored) { }
                try { to.close(); } catch (IOException ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
