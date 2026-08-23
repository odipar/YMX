# The pinned corpus

Thirty-five real tunes and the `.ymx` each one packs to. A change that alters
a packed file announces itself in `PinnedCorpusTest`, on tunes that were
chosen for their variety rather than for being first in the directory.

The thirty-two YM files come from a 544-file collection, taken by covering
features rather than by sampling: both dialects, every effect class the
collection holds - digidrums, SID voices, and tunes with no effect at all -
each distinct drum rate, the tunes that put two and three voices on the
envelope at once, looping and non-looping, and the extremes of length (4 to
58,716 frames) and size (162 bytes to 55 KB). The three `.YMR` files carry
what no YM dump in the collection does: sync-buzzers, samples and squares on
all three voices at once, retunes that leave the timer running, and one tune
that plays once where the other two loop.

Each tune is packed by its own front end at the default options, through the
same `org.ym6.Ymx` and `org.ymr.Ymr` entry points the tools call, so what is
pinned is what a user gets. Packing is deterministic: the same source and options give the same
bytes.

To repin after an intended change:

    mvn test -Dymx.pin=refresh

That rewrites every pinned file instead of comparing it, and the diff is the
review.
