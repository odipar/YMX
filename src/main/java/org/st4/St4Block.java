package org.st4;

import org.jspecify.annotations.Nullable;

/**
 * One block of a parse, chained to the block before it: the last block of a
 * parse is the parse. {@code bits} is the cost of the chain through this
 * block, {@code index} the last unit it covers, and {@code offset} its kind:
 * zero a literal run, positive a match from that many units back in the
 * output, negative a copy from the literal stream whose source starts that
 * many units back in the output and is literal there. The compressor writes
 * a copy as an offset beyond the window.
 */
public record St4Block(int bits, int index, int offset, @Nullable St4Block chain) {}
