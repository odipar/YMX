package org.st4;

import org.jspecify.annotations.Nullable;

/**
 * One block of an ST4 parse chain: a literal run when {@code offset == 0},
 * otherwise a match. {@code index} is the last unit the block covers, and
 * {@code bits} the cost of the whole chain up to and including it.
 */
public record St4Block(int bits, int index, int offset, @Nullable St4Block chain) {}
