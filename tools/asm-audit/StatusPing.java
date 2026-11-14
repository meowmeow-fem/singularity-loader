import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server list ping as a client of the given protocol version would send it: prints the status JSON.
 * Usage: java StatusPing.java host port protocolVersion
 */
public class StatusPing {
    public static void main(String[] a) throws IOException {
        String host = a[0];
        int port = Integer.parseInt(a[1]);
        int protocol = Integer.parseInt(a[2]);
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);
            writeVarInt(hs, protocol);
            byte[] h = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, h.length);
            hs.write(h);
            hs.write(port >> 8);
            hs.write(port);
            writeVarInt(hs, 1); // status
            send(out, hs.toByteArray());
            send(out, new byte[] {0x00}); // status request
            DataInputStream in = new DataInputStream(s.getInputStream());
            readVarInt(in); // length
            readVarInt(in); // packet id
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            System.out.println(new String(json, StandardCharsets.UTF_8));
        }
    }

    private static void send(DataOutputStream out, byte[] packet) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, packet.length);
        b.write(packet);
        out.write(b.toByteArray());
        out.flush();
    }

    private static void writeVarInt(ByteArrayOutputStream b, int v) {
        while ((v & ~0x7F) != 0) {
            b.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b.write(v);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            int k = in.read();
            if (k < 0) throw new IOException("closed");
            v |= (k & 0x7F) << 7 * i;
            if ((k & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }
}
