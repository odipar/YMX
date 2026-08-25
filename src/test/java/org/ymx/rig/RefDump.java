package org.ymx.rig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * What the real player writes to the sound chip, frame by frame, as JSON.
 * The reference an independent implementation is measured against.
 *
 * <p>The -1 entry is emitted rather than broken on: a tune that plays once
 * reports it from the call after its last frame, and that call is the one
 * three implementers got wrong when nothing in the harness could see it.
 */
final class RefDump {

    private RefDump() {}

    public static void main(String[] args) throws Exception {
        System.out.println(dump(Files.readAllBytes(Path.of(args[0])),
                Integer.parseInt(args[1]),
                args.length > 2 ? Integer.parseInt(args[2]) : 2));
    }

    /** One tune's calls, as the JSON doc/conformance holds a digest of. */
    static String dump(byte[] packed, int budget, int unit) {
        Player player = new Player(packed, unit);
        if (player.init() != 0) {
            return "{\"error\":\"init rejected\"}";
        }
        StringBuilder out = new StringBuilder("{\"frames\":[");
        for (int f = 0; f < budget; f++) {
            Player.Frame frame = player.frame();
            if (f > 0) {
                out.append(',');
            }
            out.append("{\"result\":").append(frame.result()).append(",\"w\":{");
            TreeMap<Integer, Integer> map = new TreeMap<>();
            for (Player.Pair pair : frame.writes()) {
                map.put(pair.register(), pair.value());
            }
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            out.append("}}");
            if (frame.result() == -1) {
                break;              // the entry is written, then the run ends
            }
        }
        out.append("]}");
        return out.toString();
    }
}
