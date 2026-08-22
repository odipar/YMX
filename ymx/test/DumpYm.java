import org.ym6.Ym6Reader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dumps a YM tune's registers as flat binary for {@code sweep.py}: format
 * (5 or 6), frame count, drum count and player Hz as big-endian ints, then
 * the sixteen register vectors, then one int length per drum sample - the
 * sweep's ownership model computes drum durations from them. The reader
 * unpacks LHA archives by itself.
 */
public class DumpYm {
    public static void main(String[] args) throws Exception {
        var song = Ym6Reader.read(Files.readAllBytes(Path.of(args[0])));
        var out = new BufferedOutputStream(System.out);
        var data = new DataOutputStream(out);
        data.writeInt(song.format().startsWith("YM6") ? 6 : 5);
        data.writeInt(song.frames());
        data.writeInt(song.drums().length);
        data.writeInt(song.playerHz());
        for (int r = 0; r < 16; r++) {
            out.write(song.registers()[r], 0, song.frames());
        }
        for (byte[] drum : song.drums()) {
            data.writeInt(drum.length);
        }
        data.flush();
        out.flush();
    }
}
