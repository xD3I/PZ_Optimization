#!/usr/bin/env python3
"""Bytecode-parity audit of the overridden game classes (2026-09-23).

The overrides are Vineflower decompiles of the game's own classes, recompiled. Vineflower sometimes renders a
method wrongly and the result still compiles: a loop ending in an unconditional break (IsoChunk.AddVehicles_OnZone:
only the first row of parking stalls spawned cars), a dropped operand (`chance *= 2` as `case 5 -> 2`), a dropped
cast that turns a call into self-recursion (IsoGameCharacter.CanSee). None of these touch a `// pzopt:` line, so no
review of our edits finds them. This audit compares every method of build/classes/<override> that carries no pzopt
edit with the jar's copy in build/stock/ (both written by scripts/build.sh) and fails when they differ.

"Differ" is judged on a compiler-neutral fingerprint, since javac and the game's compiler lay out the same source
slightly differently (local slots, branch polarity, checkcasts, constant-pool order): the multiset of
  - calls (name + descriptor, owner dropped: Vineflower may pick another static type), field reads / writes,
  - constants (iconst / bipush / sipush / ldc values, iinc deltas),
  - arithmetic, logic, conversion, array, monitor and throw opcodes (returns are left out: the game's compiler
    repeats a return where javac jumps to a shared one),
  - object / array creation types, switch case keys,
  - the number of loops (distinct backward-branch targets).
A method counts as edited (and is skipped) when a source line it compiles from, or the line above one, contains
`pzopt`, when a pzopt line lies inside its line range, or when it references a pzopt class. Methods the jar lacks
are ours (skipped). Lambda bodies are compared per class as one pool (their numbering differs between compilers).

Usage: scripts/bytecode-audit.py [--classes build/classes] [--stock build/stock] [--src src/overrides]
                                [--allow scripts/bytecode-audit.allow] [-v] [class ...]
Exit 0 = every unedited method matches; 1 = mismatches (listed with the fingerprint difference).
The allow file lists `Class#method(args)` entries (one per line, `#` comments after two spaces) that were checked by
hand and differ for a benign reason; each needs a reason.
"""
import argparse
import collections
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

KEEP_OPS = set("""
iadd ladd fadd dadd isub lsub fsub dsub imul lmul fmul dmul idiv ldiv fdiv ddiv irem lrem frem drem
ineg lneg fneg dneg ishl lshl ishr lshr iushr lushr iand land ior lor ixor lxor
i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i d2l d2f i2b i2c i2s
iaload laload faload daload aaload baload caload saload iastore lastore fastore dastore aastore bastore castore sastore
arraylength athrow monitorenter monitorexit
aconst_null
""".split())
CMP_OPS = {"fcmpl": "fcmp", "fcmpg": "fcmp", "dcmpl": "dcmp", "dcmpg": "dcmp", "lcmp": "lcmp"}
BRANCH = re.compile(r"^(if\w*|goto(_w)?)$")

METHOD_HDR = re.compile(r"^  (\S.*\));$|^  (static \{\});$|^  (\S.*);$")
INSN = re.compile(r"^\s+(\d+): (\w+)\s*(.*)$")
LINE = re.compile(r"^\s+line (\d+): (\d+)$")


def javap(classpath, classes):
    out = subprocess.run(["javap", "-c", "-p", "-l", "-cp", classpath] + classes,
                         capture_output=True, text=True)
    if out.returncode != 0 and not out.stdout:
        sys.exit(f"javap failed: {out.stderr.strip()}")
    return out.stdout


def norm_decl(decl):
    # drop modifiers and generics so the key is name(params) — stable between compilers
    decl = re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", decl))
    decl = decl.replace("static {}", "<clinit>()")
    m = re.search(r"([\w$<>]+)\(([^)]*)\)", decl)
    if not m:
        return None
    name = m.group(1)
    if "(" in decl and name == decl.split("(")[0].split()[-1] and "." in name:
        name = "<init>"
    return f"{name}({m.group(2)})"


def parse(text):
    """{class: {method_key: {"tokens": Counter, "lines": set}}}"""
    classes = {}
    cls = None
    cur = None
    for raw in text.splitlines():
        m = re.match(r"^(?:public |protected |private |final |abstract |static |sealed |non-sealed |strictfp )*"
                     r"(?:class|interface|enum|record|@interface) ([\w.$]+)", raw)
        if m:
            cls = m.group(1)
            classes[cls] = {}
            cur = None
            continue
        if cls is None:
            continue
        if raw.startswith("  ") and not raw.startswith("   ") and raw.rstrip().endswith(";"):
            decl = raw.strip().rstrip(";")
            if "(" not in decl and decl != "static {}":
                cur = None  # a field
                continue
            key = norm_decl(decl)
            if key and key.split("(")[0] == cls.split(".")[-1].split("$")[-1]:
                key = "<init>(" + key.split("(", 1)[1]
            cur = {"tokens": collections.Counter(), "lines": set()}
            classes[cls][key] = cur
            continue
        if cur is None:
            continue
        mi = INSN.match(raw)
        if mi:
            off, op, rest = int(mi.group(1)), mi.group(2), mi.group(3)
            tok = token(op, rest, off)
            if isinstance(tok, tuple):
                # a loop = a distinct back-edge target: the game's compiler jumps back once per `continue`,
                # javac funnels them through one goto
                cur.setdefault("heads", set()).add(tok[1])
                cur["tokens"]["loops"] = len(cur["heads"])
            elif tok:
                cur["tokens"][tok] += 1
            continue
        ml = LINE.match(raw)
        if ml:
            cur["lines"].add(int(ml.group(1)))
    return classes


def token(op, rest, off):
    comment = rest.split("//", 1)[1].strip() if "//" in rest else ""
    if op in KEEP_OPS:
        return op
    if op in CMP_OPS:
        return CMP_OPS[op]
    if op.startswith("invoke"):
        if op == "invokedynamic":
            m = re.search(r"InvokeDynamic #\d+:(\w+):(\S+)", comment)
            return f"indy {m.group(1)}{m.group(2)}" if m else "indy"
        m = re.search(r"Method (?:[\w/$]+\.)?(\"?[\w<>$]+\"?):(\S+)", comment)
        return f"call {m.group(1).strip(chr(34))}{m.group(2)}" if m else f"call ?{comment}"
    if op in ("getfield", "putfield", "getstatic", "putstatic"):
        m = re.search(r"Field (?:[\w/$]+\.)?([\w$]+):", comment)
        return f"{op[:3]} {m.group(1) if m else comment}"
    if op.startswith("iconst_") or op.startswith("lconst_") or op.startswith("fconst_") or op.startswith("dconst_"):
        t, v = op.split("_")
        return f"const {t[0]} {'-1' if v == 'm1' else v}"
    if op in ("bipush", "sipush"):
        return f"const i {rest.split()[0]}"
    if op.startswith("ldc"):
        m = re.search(r"(int|float|long|double|String|class) (.*)$", comment)
        if m:
            kind, val = m.group(1), m.group(2)
            if kind in ("float", "double"):
                val = val.rstrip("fdFD")
            return f"const {kind[0]} {val}"
        return f"const ? {comment}"
    if op == "iinc":
        return f"iinc {rest.split(',')[-1].strip()}"
    if op in ("new", "anewarray", "multianewarray", "instanceof"):
        m = re.search(r"class (\S+)", comment)
        return f"{op} {m.group(1) if m else comment}"
    if op == "newarray":
        return f"newarray {rest.strip()}"
    if op in ("tableswitch", "lookupswitch"):
        return "switch"
    if BRANCH.match(op):
        try:
            target = int(rest.split()[0])
        except (ValueError, IndexError):
            return None
        return ("loop-head", target) if target <= off else None
    return None


def benign(lost, gained):
    """Compiler-layout differences that cannot change behaviour."""
    lost, gained = collections.Counter(lost), collections.Counter(gained)
    # `if (x) return true; return false;` (jar) vs `return x;` (javac): one extra iconst_0 + iconst_1 pair each
    pairs = min(lost["const i 0"], lost["const i 1"])
    lost["const i 0"] -= pairs
    lost["const i 1"] -= pairs
    # `i = i + k` (jar: const k + iadd, or isub for a negative step) vs javac's `i += k` on a local (iinc k)
    for tok, n in list(gained.items()):
        if tok.startswith("iinc ") and n > 0:
            k = int(tok.split()[1])
            op = "iadd" if k > 0 else "isub"
            c = f"const i {abs(k)}"
            m = min(n, lost[c], lost[op])
            gained[tok] -= m
            lost[c] -= m
            lost[op] -= m
    # Vineflower's `(byte) b` on a value that already is a byte (the jar has no conversion there): i2b of a byte is a no-op
    for op in ("i2b", "i2c", "i2s"):
        gained[op] = 0
    return not +lost and not +gained


def pzopt_lines(src_path):
    try:
        with open(src_path, encoding="utf-8", errors="replace") as f:
            return {i + 1 for i, l in enumerate(f) if "pzopt" in l}
    except OSError:
        return set()


def edited(m, marks):
    if not m["lines"]:
        return False
    for ln in m["lines"]:
        if ln in marks or ln - 1 in marks:
            return True
    lo, hi = min(m["lines"]), max(m["lines"])
    if hi - lo < 2000 and any(lo <= k <= hi for k in marks):
        return True
    return any("pzopt" in t for t in m["tokens"])


def pool_lambdas(methods):
    pooled = {k: v for k, v in methods.items() if not k.startswith("lambda$")}
    lam = [v for k, v in methods.items() if k.startswith("lambda$")]
    if lam:
        c = collections.Counter()
        lines = set()
        for v in lam:
            c.update(v["tokens"])
            lines |= v["lines"]
        pooled["<lambdas>"] = {"tokens": c, "lines": lines, "parts": lam}
    return pooled


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", default=os.path.join(REPO, "build/classes"))
    ap.add_argument("--stock", default=os.path.join(REPO, "build/stock"))
    ap.add_argument("--src", default=os.path.join(REPO, "src/overrides"))
    ap.add_argument("--allow", default=os.path.join(REPO, "scripts/bytecode-audit.allow"))
    ap.add_argument("-v", action="store_true", help="also list the edited / added methods skipped")
    ap.add_argument("only", nargs="*", help="class names (zombie.iso.IsoChunk) to audit; default all")
    a = ap.parse_args()

    allow = set()
    if os.path.exists(a.allow):
        for l in open(a.allow):
            l = l.split("  #", 1)[0].strip()
            if l and not l.startswith("#"):
                allow.add(l)

    files = []
    for root, _, names in os.walk(a.stock):
        for n in names:
            if n.endswith(".class"):
                files.append(os.path.relpath(os.path.join(root, n), a.stock)[:-6].replace("\\", "/").replace("/", "."))
    files.sort()
    if a.only:
        files = [f for f in files if f.split("$")[0] in a.only]
    if not files:
        sys.exit(f"no stock classes under {a.stock} (run scripts/build.sh first)")

    stock = parse(javap(a.stock, files))
    ours = parse(javap(a.classes, files))

    checked = skipped = 0
    bad = []
    for cls in files:
        s_methods, o_methods = stock.get(cls, {}), ours.get(cls, {})
        outer = cls.split("$")[0]
        src = os.path.join(a.src, outer.replace(".", "/") + ".java")
        if not os.path.exists(src):
            continue  # a hand-written shim (src/shims, e.g. TISLogoState), not a decompile
        marks = pzopt_lines(src)
        s_methods, o_methods = pool_lambdas(s_methods), pool_lambdas(o_methods)
        for key, sm in s_methods.items():
            name = f"{cls}#{key}"
            om = o_methods.get(key)
            if om is None:
                if key == "<lambdas>" or key.startswith("access$"):
                    continue
                bad.append((name, "missing from the override (removed or renamed)", None, None))
                continue
            if edited(om, marks):
                skipped += 1
                if a.v:
                    print(f"  edited, skipped: {name}")
                continue
            checked += 1
            lost, gained = sm["tokens"] - om["tokens"], om["tokens"] - sm["tokens"]
            if not lost and not gained or benign(lost, gained) or name in allow:
                continue
            bad.append((name, None, lost, gained))

    for name, why, lost, gained in bad:
        print(f"MISMATCH {name}")
        if why:
            print(f"    {why}")
            continue
        for t, n in sorted(lost.items()):
            print(f"    jar only:      {n} x {t}")
        for t, n in sorted(gained.items()):
            print(f"    override only: {n} x {t}")
    print(f"bytecode audit: {checked} unedited methods compared, {skipped} edited skipped, {len(bad)} mismatches")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
