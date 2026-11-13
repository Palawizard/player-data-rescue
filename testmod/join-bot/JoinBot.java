import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Dev-only headless client for the smoke tests: logs in to an offline-mode server as a vanilla
 * client, joins the world, answers keep-alives and teleports, and reports how the join ended.
 * It never ships in a mod JAR. Packet ids and layouts come from each version's decompiled
 * sources and data reports (see the profiles below and docs/TESTING.md).
 *
 * <p>Run with any JDK 17 or later: {@code java JoinBot.java --port 25566 --protocol 763 --name Bot}.
 * Exit codes: 0 joined and stayed, 2 usage error, 3 disconnected by the server, 4 network error
 * or timeout.
 */
public final class JoinBot {
    private static final int EXIT_STAYED = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_DISCONNECTED = 3;
    private static final int EXIT_ERROR = 4;
    private static final int MAX_PACKET = 8 * 1024 * 1024;

    private enum Phase {
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    /** Packet ids of one protocol version. -1 means the packet does not exist in that version. */
    private record Profile(
            String minecraft,
            int protocol,
            boolean configurationPhase,
            boolean helloHasOptionalUuid,
            boolean particleStatusInClientInfo,
            int loginDisconnect,
            int loginSuccess,
            int loginCompression,
            int loginCustomQuery,
            int loginCookieRequest,
            int loginAcknowledged,
            int loginCustomQueryAnswer,
            int loginCookieResponse,
            int cfgCookieRequest,
            int cfgDisconnect,
            int cfgFinish,
            int cfgKeepAlive,
            int cfgPing,
            int cfgSelectKnownPacks,
            int cfgCodeOfConduct,
            int cfgClientInformationOut,
            int cfgCookieResponseOut,
            int cfgFinishOut,
            int cfgKeepAliveOut,
            int cfgPongOut,
            int cfgSelectKnownPacksOut,
            int cfgAcceptCodeOfConductOut,
            int playChunkBatchFinished,
            int playDisconnect,
            int playKeepAlive,
            int playLogin,
            int playPing,
            int playPlayerPosition,
            int playStartConfiguration,
            int playAcceptTeleportationOut,
            int playChunkBatchReceivedOut,
            int playClientInformationOut,
            int playConfigurationAcknowledgedOut,
            int playKeepAliveOut,
            int playPlayerLoadedOut,
            int playPongOut,
            boolean teleportIdFirst) {}

    // 1.20.1 (protocol 763): ids are the registration order in ConnectionProtocol.
    private static final Profile MC_1_20_1 = new Profile(
            "1.20.1", 763, false, true, false, 0, 2, 3, 4, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, 26, 35, 40, 50, 60, -1, 0, -1, 8, -1, 18, -1, 32, false);

    // 1.21.1 (protocol 767) and 26.3 (protocol 777): ids from the server's generated packets.json report.
    private static final Profile MC_1_21_1 = new Profile(
            "1.21.1", 767, true, false, false, 0, 2, 3, 4, 5, 3, 2, 4, 0, 2, 3, 4, 5, 14, -1, 0, 1, 3, 4, 5, 7, -1, 12,
            29, 38, 43, 53, 64, 105, 0, 8, 10, 12, 24, -1, 39, false);

    private static final Profile MC_26_3 = new Profile(
            "26.3", 777, true, false, true, 0, 2, 3, 4, 5, 3, 2, 4, 0, 2, 3, 4, 5, 15, 20, 0, 1, 3, 4, 5, 7, 9, 11, 32,
            45, 50, 62, 73, 120, 0, 11, 14, 16, 28, 44, 45, true);

    private static final List<Profile> PROFILES = List.of(MC_1_20_1, MC_1_21_1, MC_26_3);

    private final Profile profile;
    private final String host;
    private final int port;
    private final String name;
    private final int viewDistance;
    private final long staySeconds;
    private final long timeoutSeconds;

    private DataInputStream in;
    private OutputStream out;
    private int compressionThreshold = -1;
    private Phase phase = Phase.LOGIN;
    private boolean joined;
    private boolean loadedSent;
    private double x;
    private double y;
    private double z;
    private float yRot;
    private float xRot;

    private JoinBot(Profile profile, String host, int port, String name, int viewDistance, long stay, long timeout) {
        this.profile = profile;
        this.host = host;
        this.port = port;
        this.name = name;
        this.viewDistance = viewDistance;
        this.staySeconds = stay;
        this.timeoutSeconds = timeout;
    }

    public static void main(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                System.err.println("Usage: java JoinBot.java --port <port> --protocol <763|767|777> --name <name>"
                        + " [--host 127.0.0.1] [--stay-seconds 5] [--timeout-seconds 120] [--view-distance 8]");
                System.exit(EXIT_USAGE);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        Profile profile = null;
        for (Profile candidate : PROFILES) {
            if (String.valueOf(candidate.protocol()).equals(options.get("protocol"))) {
                profile = candidate;
            }
        }
        if (profile == null || !options.containsKey("port") || !options.containsKey("name")) {
            System.err.println("--port, --name and a known --protocol (763, 767 or 777) are required.");
            System.exit(EXIT_USAGE);
        }
        JoinBot bot = new JoinBot(
                profile,
                options.getOrDefault("host", "127.0.0.1"),
                Integer.parseInt(options.get("port")),
                options.get("name"),
                Integer.parseInt(options.getOrDefault("view-distance", "8")),
                Long.parseLong(options.getOrDefault("stay-seconds", "5")),
                Long.parseLong(options.getOrDefault("timeout-seconds", "120")));
        System.exit(bot.run());
    }

    private int run() {
        log("connecting to " + host + ":" + port + " as " + name + " (Minecraft " + profile.minecraft() + ", protocol "
                + profile.protocol() + ")");
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        long leaveAt = Long.MAX_VALUE;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(1000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            sendHandshakeAndHello();
            while (true) {
                long now = System.currentTimeMillis();
                if (joined && now >= leaveAt) {
                    log("left after staying " + staySeconds + " s");
                    return EXIT_STAYED;
                }
                if (!joined && now >= deadline) {
                    log("timeout: not joined after " + timeoutSeconds + " s (phase " + phase + ")");
                    return EXIT_ERROR;
                }
                byte[] packet;
                try {
                    packet = readPacket();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Integer result = handle(new DataInputStream(new ByteArrayInputStream(packet)));
                if (result != null) {
                    return result;
                }
                if (joined && leaveAt == Long.MAX_VALUE) {
                    leaveAt = System.currentTimeMillis() + staySeconds * 1000;
                }
            }
        } catch (EOFException e) {
            log("connection closed by the server (phase " + phase + ", joined " + joined + ")");
            return joined ? EXIT_DISCONNECTED : EXIT_ERROR;
        } catch (IOException | DataFormatException e) {
            log("network error: " + e);
            return EXIT_ERROR;
        }
    }

    private void sendHandshakeAndHello() throws IOException {
        Buf handshake = new Buf(0);
        handshake.varInt(profile.protocol()).string(host).u16(port).varInt(2);
        send(handshake);
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        Buf hello = new Buf(0).string(name);
        if (profile.helloHasOptionalUuid()) {
            hello.bool(true);
        }
        hello.uuid(offlineId);
        send(hello);
    }

    private Integer handle(DataInputStream packet) throws IOException {
        int id = readVarInt(packet);
        switch (phase) {
            case LOGIN -> {
                return handleLogin(id, packet);
            }
            case CONFIGURATION -> {
                return handleConfiguration(id, packet);
            }
            default -> {
                return handlePlay(id, packet);
            }
        }
    }

    private Integer handleLogin(int id, DataInputStream packet) throws IOException {
        if (id == profile.loginDisconnect()) {
            return disconnected(readString(packet));
        } else if (id == profile.loginCompression()) {
            compressionThreshold = readVarInt(packet);
            log("compression threshold " + compressionThreshold);
        } else if (id == profile.loginCustomQuery()) {
            int transaction = readVarInt(packet);
            log("login query " + readString(packet) + " answered as not understood");
            send(new Buf(profile.loginCustomQueryAnswer()).varInt(transaction).bool(false));
        } else if (id == profile.loginCookieRequest()) {
            send(new Buf(profile.loginCookieResponse())
                    .string(readString(packet))
                    .bool(false));
        } else if (id == profile.loginSuccess()) {
            log("login accepted");
            if (profile.configurationPhase()) {
                send(new Buf(profile.loginAcknowledged()));
                phase = Phase.CONFIGURATION;
                send(clientInformation(profile.cfgClientInformationOut()));
            } else {
                phase = Phase.PLAY;
            }
        } else {
            log("unexpected login packet " + id);
        }
        return null;
    }

    private Integer handleConfiguration(int id, DataInputStream packet) throws IOException {
        if (id == profile.cfgDisconnect()) {
            return disconnected(readComponent(packet));
        } else if (id == profile.cfgKeepAlive()) {
            send(new Buf(profile.cfgKeepAliveOut()).i64(packet.readLong()));
        } else if (id == profile.cfgPing()) {
            send(new Buf(profile.cfgPongOut()).i32(packet.readInt()));
        } else if (id == profile.cfgSelectKnownPacks()) {
            send(new Buf(profile.cfgSelectKnownPacksOut()).varInt(0));
        } else if (id == profile.cfgCodeOfConduct()) {
            send(new Buf(profile.cfgAcceptCodeOfConductOut()));
        } else if (id == profile.cfgCookieRequest()) {
            send(new Buf(profile.cfgCookieResponseOut())
                    .string(readString(packet))
                    .bool(false));
        } else if (id == profile.cfgFinish()) {
            log("configuration finished");
            send(new Buf(profile.cfgFinishOut()));
            phase = Phase.PLAY;
        }
        return null;
    }

    private Integer handlePlay(int id, DataInputStream packet) throws IOException {
        if (id == profile.playDisconnect()) {
            return disconnected(profile.configurationPhase() ? readComponent(packet) : readString(packet));
        } else if (id == profile.playKeepAlive()) {
            send(new Buf(profile.playKeepAliveOut()).i64(packet.readLong()));
        } else if (id == profile.playPing()) {
            send(new Buf(profile.playPongOut()).i32(packet.readInt()));
        } else if (id == profile.playLogin()) {
            joined = true;
            log("joined the world");
            if (!profile.configurationPhase()) {
                send(clientInformation(profile.playClientInformationOut()));
            }
        } else if (id == profile.playPlayerPosition()) {
            acceptTeleport(packet);
        } else if (id == profile.playChunkBatchFinished()) {
            send(new Buf(profile.playChunkBatchReceivedOut()).f32(64.0f));
        } else if (id == profile.playStartConfiguration()) {
            send(new Buf(profile.playConfigurationAcknowledgedOut()));
            phase = Phase.CONFIGURATION;
        }
        return null;
    }

    private void acceptTeleport(DataInputStream packet) throws IOException {
        int teleportId;
        int relatives;
        double nx;
        double ny;
        double nz;
        float nyRot;
        float nxRot;
        if (profile.teleportIdFirst()) {
            teleportId = readVarInt(packet);
            nx = packet.readDouble();
            ny = packet.readDouble();
            nz = packet.readDouble();
            packet.readDouble();
            packet.readDouble();
            packet.readDouble();
            nyRot = packet.readFloat();
            nxRot = packet.readFloat();
            relatives = packet.readInt();
        } else {
            nx = packet.readDouble();
            ny = packet.readDouble();
            nz = packet.readDouble();
            nyRot = packet.readFloat();
            nxRot = packet.readFloat();
            relatives = packet.readUnsignedByte();
            teleportId = readVarInt(packet);
        }
        // Bits 0 to 4 mark x, y, z, yaw and pitch as relative, on every supported version.
        x = (relatives & 1) != 0 ? x + nx : nx;
        y = (relatives & 2) != 0 ? y + ny : ny;
        z = (relatives & 4) != 0 ? z + nz : nz;
        yRot = (relatives & 8) != 0 ? yRot + nyRot : nyRot;
        xRot = (relatives & 16) != 0 ? xRot + nxRot : nxRot;
        Buf accept = new Buf(profile.playAcceptTeleportationOut()).varInt(teleportId);
        if (profile.teleportIdFirst()) {
            accept.f64(x).f64(y).f64(z).f32(yRot).f32(xRot);
        }
        send(accept);
        log(String.format(java.util.Locale.ROOT, "at %.1f %.1f %.1f", x, y, z));
        if (profile.playPlayerLoadedOut() >= 0 && joined && !loadedSent) {
            send(new Buf(profile.playPlayerLoadedOut()));
            loadedSent = true;
        }
    }

    private Buf clientInformation(int packetId) {
        Buf info = new Buf(packetId)
                .string("en_us")
                .u8(viewDistance)
                .varInt(0)
                .bool(true)
                .u8(0x7f)
                .varInt(1)
                .bool(false)
                .bool(true);
        if (profile.particleStatusInClientInfo()) {
            info.varInt(0);
        }
        return info;
    }

    private int disconnected(String reason) {
        log("disconnected by the server, joined " + joined + ", reason: " + reason);
        return EXIT_DISCONNECTED;
    }

    private void send(Buf buf) throws IOException {
        byte[] body = buf.bytes();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compressionThreshold >= 0) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            if (body.length >= compressionThreshold) {
                writeVarInt(inner, body.length);
                Deflater deflater = new Deflater();
                deflater.setInput(body);
                deflater.finish();
                byte[] chunk = new byte[8192];
                while (!deflater.finished()) {
                    inner.write(chunk, 0, deflater.deflate(chunk));
                }
                deflater.end();
            } else {
                writeVarInt(inner, 0);
                inner.write(body);
            }
            body = inner.toByteArray();
        }
        writeVarInt(frame, body.length);
        frame.write(body);
        out.write(frame.toByteArray());
        out.flush();
    }

    private byte[] readPacket() throws IOException, DataFormatException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_PACKET) {
            throw new IOException("bad packet length " + length);
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        if (compressionThreshold < 0) {
            return frame;
        }
        DataInputStream frameIn = new DataInputStream(new ByteArrayInputStream(frame));
        int dataLength = readVarInt(frameIn);
        byte[] rest = frameIn.readAllBytes();
        if (dataLength == 0) {
            return rest;
        }
        if (dataLength > MAX_PACKET) {
            throw new IOException("bad uncompressed length " + dataLength);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        byte[] data = new byte[dataLength];
        int done = 0;
        while (done < dataLength && !inflater.finished()) {
            int read = inflater.inflate(data, done, dataLength - done);
            if (read == 0 && inflater.needsInput()) {
                break;
            }
            done += read;
        }
        inflater.end();
        return data;
    }

    /** Text components are JSON strings before 1.20.3 and network NBT from 1.20.3. */
    private static String readComponent(DataInputStream packet) throws IOException {
        int type = packet.readUnsignedByte();
        return type == 8 ? packet.readUTF() : String.valueOf(readNbt(packet, type, 0));
    }

    private static Object readNbt(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("NBT too deep");
        }
        switch (type) {
            case 1:
                return input.readByte();
            case 2:
                return input.readShort();
            case 3:
                return input.readInt();
            case 4:
                return input.readLong();
            case 5:
                return input.readFloat();
            case 6:
                return input.readDouble();
            case 7:
                return "byte[" + skip(input, input.readInt()) + "]";
            case 8:
                return input.readUTF();
            case 9: {
                int elementType = input.readUnsignedByte();
                int size = input.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    list.add(readNbt(input, elementType, depth + 1));
                }
                return list;
            }
            case 10: {
                Map<String, Object> compound = new LinkedHashMap<>();
                for (int child = input.readUnsignedByte(); child != 0; child = input.readUnsignedByte()) {
                    String key = input.readUTF();
                    compound.put(key, readNbt(input, child, depth + 1));
                }
                return compound;
            }
            case 11:
                return "int[" + skip(input, input.readInt() * 4L) + "]";
            case 12:
                return "long[" + skip(input, input.readInt() * 8L) + "]";
            default:
                throw new IOException("unknown NBT type " + type);
        }
    }

    private static long skip(DataInputStream input, long bytes) throws IOException {
        input.readNBytes((int) bytes);
        return bytes;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_PACKET) {
            throw new IOException("bad string length " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = input.read();
            if (b < 0) {
                throw new EOFException();
            }
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static void log(String message) {
        System.out.println("[join-bot] " + message);
        System.out.flush();
    }

    /** A serverbound packet being built: its id, then its fields in protocol order. */
    private static final class Buf {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Buf(int packetId) {
            if (packetId < 0) {
                throw new IllegalStateException("packet not available in this protocol version");
            }
            varInt(packetId);
        }

        Buf varInt(int value) {
            try {
                writeVarInt(bytes, value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Buf string(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            varInt(utf8.length);
            return raw(utf8);
        }

        Buf raw(byte[] value) {
            bytes.write(value, 0, value.length);
            return this;
        }

        Buf bool(boolean value) {
            return u8(value ? 1 : 0);
        }

        Buf u8(int value) {
            bytes.write(value);
            return this;
        }

        Buf u16(int value) {
            return u8(value >>> 8).u8(value & 0xff);
        }

        Buf i32(int value) {
            return i64High(value, 4);
        }

        Buf i64(long value) {
            return i64High(value, 8);
        }

        Buf f32(float value) {
            return i32(Float.floatToIntBits(value));
        }

        Buf f64(double value) {
            return i64(Double.doubleToLongBits(value));
        }

        Buf uuid(UUID value) {
            return i64(value.getMostSignificantBits()).i64(value.getLeastSignificantBits());
        }

        private Buf i64High(long value, int size) {
            for (int i = size - 1; i >= 0; i--) {
                bytes.write((int) (value >>> (8 * i)) & 0xff);
            }
            return this;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
