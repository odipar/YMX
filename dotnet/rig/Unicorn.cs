using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace Rig
{
    /// <summary>
    /// libunicorn as a plain 68000, ported from the Java rig's FFM binding:
    /// the slice the rig uses - open, map, read and write memory and
    /// registers, run to an address, and watch memory writes. The library is
    /// found through UNICORN_LIB, the usual install paths, or the wheel pip
    /// install unicorn places under Homebrew's Python.
    /// </summary>
    public sealed class Unicorn
    {
        // The m68k register ids libunicorn assigns.
        public const int A0 = 1;
        public const int A1 = 2;
        public const int A2 = 3;
        public const int A3 = 4;
        public const int A4 = 5;
        public const int A5 = 6;
        public const int A6 = 7;
        public const int A7 = 8;
        public const int D0 = 9;
        public const int D1 = 10;
        public const int D2 = 11;
        public const int D3 = 12;
        public const int D4 = 13;
        public const int D5 = 14;
        public const int D6 = 15;
        public const int D7 = 16;
        public const int SR = 17;
        public const int PC = 18;

        private const int ArchM68k = 7;
        private const int ModeBigEndian = 1 << 30;
        private const int HookMemWrite = 2048;
        private const int ProtAll = 7;
        private const int CpuM68000 = 1;
        // uc_ctl's code word: the write op, one argument, control 7
        // (CPU_MODEL).
        private const int CtlWriteCpuModel = (1 << 30) | (1 << 26) | 7;

        static Unicorn()
        {
            NativeLibrary.SetDllImportResolver(Assembly.GetExecutingAssembly(),
                    Resolve);
        }

        private static IntPtr Resolve(string name, Assembly assembly,
                DllImportSearchPath? path)
        {
            if (name != "unicorn")
            {
                return IntPtr.Zero;
            }
            foreach (string candidate in Candidates())
            {
                if (File.Exists(candidate)
                        && NativeLibrary.TryLoad(candidate, out IntPtr handle))
                {
                    return handle;
                }
            }
            return IntPtr.Zero;
        }

        private static List<string> Candidates()
        {
            var paths = new List<string>();
            string? named = Environment.GetEnvironmentVariable("UNICORN_LIB");
            if (named != null)
            {
                paths.Add(named);
            }
            paths.AddRange(new[] {"/opt/homebrew/lib/libunicorn.dylib",
                    "/opt/homebrew/lib/libunicorn.2.dylib",
                    "/usr/local/lib/libunicorn.dylib",
                    "/usr/local/lib/libunicorn.so",
                    "/usr/lib/libunicorn.so.2", "/usr/lib/libunicorn.so"});
            // the wheel's own copy, under any Homebrew Python
            if (Directory.Exists("/opt/homebrew/lib"))
            {
                foreach (string version in Directory.GetDirectories(
                        "/opt/homebrew/lib", "python3*"))
                {
                    string lib = Path.Combine(version, "site-packages",
                            "unicorn", "lib");
                    if (Directory.Exists(lib))
                    {
                        paths.AddRange(Directory.GetFiles(lib,
                                "libunicorn*.dylib"));
                    }
                }
            }
            return paths;
        }

        [DllImport("unicorn")]
        private static extern int uc_open(int arch, int mode, out IntPtr uc);

        // uc_ctl is variadic. On Apple arm64 the variadic arguments go on
        // the stack, so the model rides as the ninth named argument - past
        // the eight register slots - and the six pads fill the registers the
        // callee ignores. Everywhere else the plain three-argument form
        // matches the ABI.
        [DllImport("unicorn", EntryPoint = "uc_ctl")]
        private static extern int uc_ctl_stack(IntPtr uc, int control,
                long pad2, long pad3, long pad4, long pad5, long pad6, long pad7,
                int model);

        [DllImport("unicorn", EntryPoint = "uc_ctl")]
        private static extern int uc_ctl_registers(IntPtr uc, int control,
                int model);

        [DllImport("unicorn")]
        private static extern int uc_mem_map(IntPtr uc, ulong address,
                nuint size, uint perms);

        [DllImport("unicorn")]
        private static extern int uc_mem_write(IntPtr uc, ulong address,
                byte[] bytes, nuint size);

        [DllImport("unicorn")]
        private static extern int uc_mem_read(IntPtr uc, ulong address,
                byte[] bytes, nuint size);

        [DllImport("unicorn")]
        private static extern int uc_reg_write(IntPtr uc, int regid,
                ref ulong value);

        [DllImport("unicorn")]
        private static extern int uc_reg_read(IntPtr uc, int regid,
                ref ulong value);

        private delegate void HookMem(IntPtr uc, int type, ulong address,
                int size, long value, IntPtr user);

        [DllImport("unicorn")]
        private static extern int uc_hook_add(IntPtr uc, out IntPtr hook,
                int type, HookMem callback, IntPtr user, ulong begin, ulong end);

        [DllImport("unicorn")]
        private static extern int uc_emu_start(IntPtr uc, ulong begin,
                ulong until, ulong timeout, nuint count);

        [DllImport("unicorn")]
        private static extern IntPtr uc_strerror(int code);

        /// <summary>One write the emulated program made: where, how wide,
        /// and what.</summary>
        public delegate void WriteHook(ulong address, int size, long value);

        private readonly IntPtr engine;
        private WriteHook? hook;
        private HookMem? pinned;        // keeps the callback delegate alive

        public Unicorn()
        {
            if (!Available())
            {
                throw new InvalidOperationException(
                        "libunicorn is not on this machine");
            }
            Check(uc_open(ArchM68k, ModeBigEndian, out engine), "uc_open");
            int code = RuntimeInformation.ProcessArchitecture == Architecture.Arm64
                    && OperatingSystem.IsMacOS()
                    ? uc_ctl_stack(engine, CtlWriteCpuModel, 0, 0, 0, 0, 0, 0,
                            CpuM68000)
                    : uc_ctl_registers(engine, CtlWriteCpuModel, CpuM68000);
            Check(code, "uc_ctl");
        }

        public static bool Available()
        {
            foreach (string candidate in Candidates())
            {
                if (File.Exists(candidate))
                {
                    return true;
                }
            }
            return false;
        }

        public void Map(ulong address, ulong size)
        {
            Check(uc_mem_map(engine, address, (nuint) size, ProtAll), "uc_mem_map");
        }

        public void Write(ulong address, byte[] bytes)
        {
            Check(uc_mem_write(engine, address, bytes, (nuint) bytes.Length),
                    "uc_mem_write");
        }

        public byte[] Read(ulong address, int size)
        {
            byte[] bytes = new byte[size];
            Check(uc_mem_read(engine, address, bytes, (nuint) size), "uc_mem_read");
            return bytes;
        }

        /// <summary>One big-endian unsigned value out of memory, up to eight
        /// bytes.</summary>
        public long Value(ulong address, int size)
        {
            long value = 0;
            foreach (byte b in Read(address, size))
            {
                value = (value << 8) | b;
            }
            return value;
        }

        public void Set(int register, long value)
        {
            ulong raw = (ulong) value & 0xFFFFFFFFUL;
            Check(uc_reg_write(engine, register, ref raw), "uc_reg_write");
        }

        public long Register(int register)
        {
            ulong raw = 0;
            Check(uc_reg_read(engine, register, ref raw), "uc_reg_read");
            return (long) (raw & 0xFFFFFFFFUL);
        }

        /// <summary>Watches every memory write; one hook per engine is all
        /// the rig needs.</summary>
        public void OnWrite(WriteHook watcher)
        {
            OnWrite(watcher, 1, 0);
        }

        /// <summary>The same, scoped to [begin, end].</summary>
        public void OnWrite(WriteHook watcher, ulong begin, ulong end)
        {
            hook = watcher;
            pinned = (uc, type, address, size, value, user) =>
            {
                WriteHook? current = hook;
                if (current != null)
                {
                    current(address & 0xFFFFFFFFUL, size, value);
                }
            };
            Check(uc_hook_add(engine, out IntPtr _, HookMemWrite, pinned,
                    IntPtr.Zero, begin, end), "uc_hook_add");
        }

        /// <summary>Runs from begin until the PC reaches until or the
        /// instruction budget runs out; returns libunicorn's error code, 0
        /// for a clean stop - a tick handler's fault at its rte comes back
        /// as data.</summary>
        public int Start(ulong begin, ulong until, long count)
        {
            return uc_emu_start(engine, begin, until, 0, (nuint) count);
        }

        public static string Error(int code)
        {
            IntPtr text = uc_strerror(code);
            return Marshal.PtrToStringAnsi(text) ?? ("error " + code);
        }

        private static void Check(int code, string what)
        {
            if (code != 0)
            {
                throw new InvalidOperationException(what + ": " + Error(code));
            }
        }
    }
}
