package org.ymx.rig;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * libunicorn as a plain 68000, through the foreign-function API: the slice
 * the rig uses - open, map, read and write memory and registers, run to an
 * address, and watch memory writes.
 *
 * <p>The library is found through {@code UNICORN_LIB}, a list of the usual
 * install paths, or the wheel {@code pip install unicorn} places under
 * Homebrew's Python; {@link #available()} says whether any of those held it.
 */
final class Unicorn {

    // The m68k register ids libunicorn assigns.
    static final int A0 = 1;
    static final int A1 = 2;
    static final int A2 = 3;
    static final int A3 = 4;
    static final int A4 = 5;
    static final int A5 = 6;
    static final int A6 = 7;
    static final int A7 = 8;
    static final int D0 = 9;
    static final int D1 = 10;
    static final int D2 = 11;
    static final int D3 = 12;
    static final int D4 = 13;
    static final int D5 = 14;
    static final int D6 = 15;
    static final int D7 = 16;
    static final int SR = 17;
    static final int PC = 18;

    private static final int ARCH_M68K = 7;
    private static final int MODE_BIG_ENDIAN = 1 << 30;
    private static final int HOOK_MEM_WRITE = 2048;
    private static final int PROT_ALL = 7;
    private static final int CPU_M68000 = 1;
    // uc_ctl's code word: the write op, one argument, control 7 (CPU_MODEL).
    private static final int CTL_WRITE_CPU_MODEL = (1 << 30) | (1 << 26) | 7;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final @Nullable SymbolLookup LIBRARY = find();

    private static final @Nullable MethodHandle OPEN = handle("uc_open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final @Nullable MethodHandle CTL = LIBRARY == null ? null
            : LINKER.downcallHandle(LIBRARY.find("uc_ctl").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.firstVariadicArg(2));
    private static final @Nullable MethodHandle MAP = handle("uc_mem_map",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    private static final @Nullable MethodHandle WRITE = handle("uc_mem_write",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final @Nullable MethodHandle READ = handle("uc_mem_read",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final @Nullable MethodHandle REG_WRITE = handle("uc_reg_write",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final @Nullable MethodHandle REG_READ = handle("uc_reg_read",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final @Nullable MethodHandle HOOK_ADD = handle("uc_hook_add",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static final @Nullable MethodHandle START = handle("uc_emu_start",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static final @Nullable MethodHandle STRERROR = handle("uc_strerror",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** One write the emulated program made: where, how wide, and what. */
    interface WriteHook {
        void write(long address, int size, long value);
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment engine;
    private final MemorySegment scratch;
    private @Nullable WriteHook hook;

    Unicorn() {
        if (!available()) {
            throw new IllegalStateException("libunicorn is not on this machine");
        }
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        check(call(OPEN, ARCH_M68K, MODE_BIG_ENDIAN, out), "uc_open");
        engine = out.get(ValueLayout.ADDRESS, 0);
        scratch = arena.allocate(8);
        check(call(CTL, engine, CTL_WRITE_CPU_MODEL, CPU_M68000), "uc_ctl");
    }

    static boolean available() {
        return LIBRARY != null;
    }

    void map(long address, long size) {
        check(call(MAP, engine, address, size, PROT_ALL), "uc_mem_map");
    }

    void write(long address, byte[] bytes) {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment data = local.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            check(call(WRITE, engine, address, data, (long) bytes.length),
                    "uc_mem_write");
        }
    }

    byte[] read(long address, int size) {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment data = local.allocate(size);
            check(call(READ, engine, address, data, (long) size), "uc_mem_read");
            byte[] bytes = new byte[size];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0, bytes, 0, size);
            return bytes;
        }
    }

    /** One big-endian unsigned value out of memory, up to eight bytes. */
    long value(long address, int size) {
        long value = 0;
        for (byte b : read(address, size)) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    void set(int register, long value) {
        scratch.set(ValueLayout.JAVA_LONG, 0, value & 0xFFFFFFFFL);
        check(call(REG_WRITE, engine, register, scratch), "uc_reg_write");
    }

    long register(int register) {
        scratch.set(ValueLayout.JAVA_LONG, 0, 0);
        check(call(REG_READ, engine, register, scratch), "uc_reg_read");
        return scratch.get(ValueLayout.JAVA_LONG, 0) & 0xFFFFFFFFL;
    }

    /** Watches every memory write; one hook per engine is all the rig needs. */
    void onWrite(WriteHook watcher) {
        onWrite(watcher, 1L, 0L);
    }

    /** The same, scoped to [begin, end]. */
    void onWrite(WriteHook watcher, long begin, long end) {
        hook = watcher;
        try {
            MethodHandle target = MethodHandles.lookup().bind(this, "hooked",
                    MethodType.methodType(void.class, MemorySegment.class,
                            int.class, long.class, int.class, long.class,
                            MemorySegment.class));
            MemorySegment stub = LINKER.upcallStub(target,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), arena);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            check(call(HOOK_ADD, engine, out, HOOK_MEM_WRITE, stub,
                    MemorySegment.NULL, begin, end), "uc_hook_add");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the hook target is right here", e);
        }
    }

    @SuppressWarnings("unused")     // the upcall stub is the caller
    private void hooked(MemorySegment uc, int type, long address, int size,
            long value, MemorySegment user) {
        WriteHook watcher = hook;
        if (watcher != null) {
            watcher.write(address & 0xFFFFFFFFL, size, value);
        }
    }

    /** Runs from {@code begin} until the PC reaches {@code until} or the
     * instruction budget runs out; returns libunicorn's error code, 0 for a
     * clean stop - a tick handler's fault at its rte comes back as data. */
    int start(long begin, long until, long count) {
        try {
            return (int) requireHandle(START).invokeExact(engine, begin, until,
                    0L, count);
        } catch (Throwable t) {
            throw new AssertionError("uc_emu_start does not throw", t);
        }
    }

    static String error(int code) {
        try {
            MemorySegment text = (MemorySegment) requireHandle(STRERROR)
                    .invokeExact(code);
            return text.reinterpret(256).getString(0);
        } catch (Throwable t) {
            return "error " + code;
        }
    }

    private static int call(@Nullable MethodHandle handle, Object... arguments) {
        try {
            return (int) requireHandle(handle).invokeWithArguments(arguments);
        } catch (Throwable t) {
            throw new AssertionError("libunicorn calls do not throw", t);
        }
    }

    private static MethodHandle requireHandle(@Nullable MethodHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("libunicorn is not on this machine");
        }
        return handle;
    }

    private static void check(int code, String what) {
        if (code != 0) {
            throw new IllegalStateException(what + ": " + error(code));
        }
    }

    private static @Nullable MethodHandle handle(String name,
            FunctionDescriptor descriptor) {
        if (LIBRARY == null) {
            return null;
        }
        return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(), descriptor);
    }

    private static @Nullable SymbolLookup find() {
        for (Path candidate : candidates()) {
            if (Files.exists(candidate)) {
                try {
                    return SymbolLookup.libraryLookup(candidate, Arena.global());
                } catch (IllegalArgumentException e) {
                    // not loadable here; try the next one
                }
            }
        }
        return null;
    }

    private static java.util.List<Path> candidates() {
        java.util.List<Path> paths = new java.util.ArrayList<>();
        String named = System.getenv("UNICORN_LIB");
        if (named != null) {
            paths.add(Path.of(named));
        }
        for (String usual : new String[] {"/opt/homebrew/lib/libunicorn.dylib",
                "/opt/homebrew/lib/libunicorn.2.dylib",
                "/usr/local/lib/libunicorn.dylib", "/usr/local/lib/libunicorn.so",
                "/usr/lib/libunicorn.so.2", "/usr/lib/libunicorn.so"}) {
            paths.add(Path.of(usual));
        }
        // the wheel's own copy, under any Homebrew Python
        Path pythons = Path.of("/opt/homebrew/lib");
        if (Files.isDirectory(pythons)) {
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(
                    pythons, "python3*")) {
                for (Path version : versions) {
                    Path lib = version.resolve("site-packages/unicorn/lib");
                    if (Files.isDirectory(lib)) {
                        try (DirectoryStream<Path> wheels = Files.newDirectoryStream(
                                lib, "libunicorn*.dylib")) {
                            wheels.forEach(paths::add);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // nothing under Homebrew's Pythons; the fixed paths remain
            }
        }
        return paths;
    }
}
