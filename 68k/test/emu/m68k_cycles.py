"""The MC68000 instruction knowledge bench_bits needs: rmac listing parsing
and per-instruction cycle counts. Extracted from odipar/ST1's
68k/test/emu/cycle_model.py, which remains the authority for ST1's timing
tables; this copy carries only what a benchmark harness must know."""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Instruction:
    offset: int
    size: int
    mnemonic: str
    operands: str

def parse_listing(path: Path) -> tuple[dict[int, Instruction], dict[str, int]]:
    instructions: dict[int, Instruction] = {}
    instruction_re = re.compile(
        r"^\s*\d+\s+([0-9A-F]{8})\s+([0-9A-Fx]+)\s+"
        r"(\S+)(?:\s+([^;\s]+))?"
    )
    symbol_re = re.compile(r"^\s*(\S+)\s+([0-9A-F]{16})\s+[atdb]\s*$")
    symbols: dict[str, int] = {}
    for line in path.read_text().splitlines():
        match = instruction_re.match(line)
        if match:
            offset = int(match.group(1), 16)
            encoded = match.group(2)
            instruction = Instruction(
                offset, len(encoded) // 2, match.group(3).lower(),
                (match.group(4) or "").lower(),
            )
            if offset in instructions:
                raise AssertionError(f"duplicate instruction at {offset:#x}")
            instructions[offset] = instruction
            continue
        match = symbol_re.match(line)
        if match:
            symbols[match.group(1)] = int(match.group(2), 16)
    if not instructions:
        raise AssertionError(f"no instructions parsed from {path}")
    return instructions, symbols

CONDITIONALS = {
    "beq", "bne", "bcc", "bcs", "bmi", "bpl", "ble", "bls", "bgt",
    "bge", "blt", "bhi",
}

def fixed_cycles(instruction: Instruction) -> int | None:
    """MC68000 cycles, or None for a conditional branch/DBF."""
    mnemonic = instruction.mnemonic
    root = mnemonic.split(".")[0]
    operands = instruction.operands
    if root in CONDITIONALS or root.startswith("db"):
        return None
    if root == "bra":
        return 10
    if root == "bsr":
        return 18
    if root == "rts":
        return 16
    if root == "jmp" and "(pc," in operands:
        return 14
    if root == "lea" and re.fullmatch(r"[^()]+\(a\d\),a\d", operands):
        return 8
    if root in {"moveq", "swap"}:
        return 4
    if root in {"move", "movea"}:
        source, destination = operands.split(",")
        if re.fullmatch(r"[ad]\d", source) and re.fullmatch(r"[ad]\d", destination):
            return 4
        if (re.fullmatch(r"\(a\d\)\+", source)
                and re.fullmatch(r"d\d", destination)):
            return 12 if mnemonic.endswith(".l") else 8
        if (re.fullmatch(r"\(a\d\)\+", source)
                and re.fullmatch(r"\(a\d\)\+", destination)):
            return 20 if mnemonic.endswith(".l") else 12
        raise KeyError(instruction)
    if root in {"clr", "neg", "tst"} and re.fullmatch(r"d\d", operands):
        return 4
    if root == "addx" and re.fullmatch(r"d\d,d\d", operands):
        return 8 if mnemonic.endswith(".l") else 4
    if root in {"add", "sub", "and", "cmp"}:
        source, destination = operands.split(",")
        if source.startswith("#") and re.fullmatch(r"d\d", destination):
            return 16 if mnemonic.endswith(".l") else 8
        if (re.fullmatch(r"[ad]\d", source)
                and re.fullmatch(r"d\d", destination)):
            return 8 if mnemonic.endswith(".l") else 4
        raise KeyError(instruction)
    if root in {"addq", "subq"} and re.fullmatch(r"#[^,]+,d\d", operands):
        return 8 if mnemonic.endswith(".l") else 4
    if root in {"adda", "suba"}:
        source, destination = operands.split(",")
        if re.fullmatch(r"[ad]\d", source) and re.fullmatch(r"a\d", destination):
            return 8
        raise KeyError(instruction)
    if root in {"lsl", "lsr", "roxr"}:
        match = re.fullmatch(r"#(\d+),d\d", operands)
        if match:
            return 6 + 2 * int(match.group(1))
        raise KeyError(instruction)
    raise KeyError(instruction)
