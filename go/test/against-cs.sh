#!/bin/sh
# against-cs.sh - the bar this tree is held to: the same bytes as the C#
# tree, for the same input at every unit size.
#
#   go/test/against-cs.sh file [more...]
#
# Build the Go tool first: go build -o /tmp/go-st4 ./cmd/st4
# The C# tool refuses to overwrite an output, so each pair is removed
# before the run - a stale file compared against a fresh one passes for
# nothing.
R=/Users/rapido/git/YMX
W=/tmp/st4cmp; rm -rf $W; mkdir -p $W
pass=0; fail=0
for k in 1 2 4; do
  for f in "$@"; do
    n=$(basename "$f" | tr -d ' ')
    g="$W/go-$k-$n.st4"; c="$W/cs-$k-$n.st4"
    rm -f "$g" "$c"
    /tmp/go-st4 -k$k "$f" "$g" >/dev/null 2>&1
    YMX_REPO=$R dotnet $R/dotnet/bin/Release/net10.0/ymx.dll st4 -k$k "$f" "$c" >/dev/null 2>&1
    if [ ! -f "$g" ] || [ ! -f "$c" ]; then
      fail=$((fail+1)); echo "  k=$k $n: a tool produced nothing"; continue
    fi
    if cmp -s "$g" "$c"; then pass=$((pass+1))
    else fail=$((fail+1)); echo "  MISMATCH k=$k $n go=$(wc -c <"$g"|tr -d ' ') cs=$(wc -c <"$c"|tr -d ' ')"; fi
  done
done
echo "  byte-identical: $pass   differing: $fail"
