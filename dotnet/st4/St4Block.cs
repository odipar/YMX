// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>
/// One block of a parse, chained to the block before it: the last block of a
/// parse is the parse.
/// </summary>
/// <param name="Bits">The cost of the chain through this block.</param>
/// <param name="Index">The last unit the block covers.</param>
/// <param name="Offset">
/// Zero for a literal run; positive for a match from that many units back in
/// the output; negative for a copy from the literal stream whose source
/// starts that many units back in the output and is literal there. The
/// compressor writes a copy as an offset beyond the window.
/// </param>
/// <param name="Chain">The block before, or <see langword="null"/> for the parser's fake head.</param>
public sealed record St4Block(int Bits, int Index, int Offset, St4Block? Chain);
