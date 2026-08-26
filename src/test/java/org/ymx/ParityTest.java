package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The three trees against each other, through {@code ymx/parity.sh}: one
 * command line, and the same bytes, the same text and the same exit status in
 * Java, C# and Go.
 *
 * <p>The trees agreed on the files they wrote long before they agreed on
 * anything else, and every sweep written until now compared only those files.
 * What drifted was around them: a flag one tree read and another took for a
 * file name, a value that packed a file in one tree and stopped the run in the
 * next, a fault named in two trees and thrown as a stack trace in the third.
 * This runs the whole command and compares everything it leaves.</p>
 *
 * <p>It needs all three trees built and the YM collection to hand, so it is
 * skipped unless {@code YM_CORPUS} names the collection. Build the other two
 * first: {@code dotnet build -c Release} in {@code dotnet/}, and
 * {@code go build -o bin/<command> ./cmd/<command>} in {@code go/}.</p>
 */
final class ParityTest {

    private static final Path REPO = Path.of(System.getProperty("ymx.repo", "."));

    @Test
    void theThreeTreesAnswerOneCommandLineTheSameWay()
            throws IOException, InterruptedException {
        String corpus = System.getenv("YM_CORPUS");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)),
                "set YM_CORPUS to the directory holding the YM collection");
        assumeTrue(Files.isRegularFile(REPO.resolve(
                        "dotnet/bin/Release/net10.0/ymx.dll")),
                "build the C# tree first: dotnet build -c Release in dotnet/");
        for (String command : new String[] {"ymx", "ym-to-ymx", "play",
                "ymsndh", "mksndh", "mkprg", "st4", "dst4"}) {
            assumeTrue(Files.isExecutable(REPO.resolve("go/bin").resolve(command)),
                    "build the Go tree first: go build -o bin/" + command
                            + " ./cmd/" + command + " in go/");
        }

        Path script = REPO.resolve("ymx/parity.sh");
        Process run = new ProcessBuilder("sh", script.toString(), "-quick")
                .directory(REPO.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var stream = run.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assumeTrue(run.waitFor(10, TimeUnit.MINUTES),
                "parity.sh did not finish inside ten minutes");
        assertEquals(0, run.exitValue(),
                "the trees do not agree. " + script + " prints each case and\n"
                        + "the first lines that differ:\n\n" + output);
    }
}
