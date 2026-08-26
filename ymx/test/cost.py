#!/usr/bin/env python3
# Per-call play cost from a -perf run's video_color trace.
#
# The -perf player writes the background red when the frame's work starts
# and yellow when it ends, and each tick handler paints its own colour
# inside that span. This reads a Hatari trace of those palette writes back:
# the red-to-yellow span is one call's own work, a tick's paint-and-restore
# pair inside it is subtracted, and the yellow-to-restore span is the
# player's own burn of the timers' accumulated cost. cost.sh drives it.
import re, sys

FRAME = 160256                    # a PAL ST frame in CPU cycles
rows = []
for line in open(sys.argv[1]):
    m = re.search(r"write col addr=ff8240 col=(\w+) video_cyc_w=(\d+).*pc=([0-9a-f]+)", line)
    if m and int(m.group(3), 16) < 0xE00000:
        rows.append((m.group(1).upper(), int(m.group(2))))

def delta(a, b):
    d = b - a
    return d + FRAME if d < 0 else d

work, ticks, burn = [], [], []
i = 0
while i < len(rows):
    col, t0 = rows[i]
    if col != "700":
        i += 1
        continue
    j = i + 1
    sliver = 0
    while j < len(rows) and rows[j][0] != "770":
        c, t = rows[j]
        if c != "700" and j + 1 < len(rows) and rows[j + 1][0] == "700":
            d = delta(t, rows[j + 1][1])
            sliver += d
            ticks.append(d)
            j += 2
            continue
        j += 1
    if j >= len(rows):
        break
    gross = delta(t0, rows[j][1])
    if gross < 60000:             # a span across a pause is noise
        work.append(gross - sliver)
        if j + 1 < len(rows) and rows[j + 1][0] not in ("700", "770"):
            burn.append(delta(rows[j][1], rows[j + 1][1]))
    i = j + 1

if not work:
    print("no spans")
    sys.exit(1)
w = sorted(work)
n = len(w)
line = (f"calls {n}  avg {sum(w)/n:6.0f}  p99 {w[int(n*0.99)]:5d}"
        f"  max {w[-1]:5d} cyc ({w[-1]/512:5.2f} lines)")
if ticks:
    t = sorted(ticks)
    line += f"  | ticks/frame {len(t)/n:5.2f}  tick avg {sum(t)/len(t):4.0f} cyc"
if burn:
    b = sorted(burn)
    line += f"  | timer burn avg {sum(b)/len(b)/512:5.2f} max {b[-1]/512:5.2f} lines"
print(line)
