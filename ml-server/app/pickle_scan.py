"""Static scan of the model files: every class or function a pickle names must be on the allowlist.

A pickle runs code when it is loaded: each GLOBAL or STACK_GLOBAL names an
importable object, and REDUCE, NEWOBJ and BUILD call it. This reads the opcodes
without loading anything, works out exactly which (module, name) pairs the file
would import, and refuses the file if any pair is not in `model-globals.txt`.

It is an allowlist on purpose. The public scanners (picklescan, modelscan)
block known bad modules, and bypasses through obscure helpers inside trusted
libraries are found regularly; an allowlist of exact pairs fails closed instead.
They also cannot read these files: joblib compresses the stream and writes
numpy arrays as raw bytes between opcodes, and both tools stop at the first
array (measured 2026-10-21: byte 5,378 of the Task 1 model's 23 MB, while
picklescan still reports no infected file). This scanner decompresses the
stream as joblib does and steps over each array's bytes using the shape and
dtype recorded just before them.

A pair is added to the allowlist by a reviewed change, after reading what it
does. Nothing here imports the model's own modules or calls what the file names.

    python -m app.pickle_scan models/            # exit 0 clean, 1 refused
    python -m app.pickle_scan --list models/     # every pair each file names, for review
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import io
import lzma
import math
import pickletools
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path

ALLOWLIST = Path(__file__).resolve().parents[1] / 'model-globals.txt'
PICKLE_SUFFIXES = ('.joblib', '.pkl', '.pickle')

# The wrapper joblib writes in place of each numpy array (joblib >= 0.10).
ARRAY_WRAPPER = ('joblib.numpy_pickle', 'NumpyArrayWrapper')
# Opcodes that reach objects by other means than a named global: refused outright.
NO_GLOBAL_PATH = {'PERSID', 'BINPERSID', 'EXT1', 'EXT2', 'EXT4', 'NEXT_BUFFER', 'READONLY_BUFFER'}
# numpy's dtype flag for "holds Python objects" (NPY_ITEM_HASOBJECT).
HASOBJECT = 0x01


class Refused(Exception):
    """The file cannot be shown to name only allowlisted globals."""


@dataclass(frozen=True)
class Global:
    module: str
    name: str


@dataclass
class Obj:
    """What REDUCE, NEWOBJ or INST would build: the callable, its arguments, and BUILD's state."""
    cls: object
    args: object = None
    state: object = None


class Mark:
    pass


class Opaque:
    pass


MARK, OPAQUE = Mark(), Opaque()


@dataclass
class Report:
    path: str
    globals: set = field(default_factory=set)
    arrays: int = 0


def _stream(raw: bytes) -> bytes:
    """The pickle stream, decompressed the way joblib's loader detects the format."""
    if raw.startswith(b'\x78'):
        return zlib.decompress(raw)
    if raw.startswith(b'\x1f\x8b'):
        return gzip.decompress(raw)
    if raw.startswith(b'BZ'):
        return bz2.decompress(raw)
    if raw.startswith(b'\xfd\x37\x7a\x58\x5a') or raw.startswith(b'\x5d\x00'):
        return lzma.decompress(raw)
    if raw.startswith(b'\x04\x22\x4d\x18'):
        raise Refused('lz4-compressed: not supported, save the model with zlib, gzip, bz2 or xz')
    if raw.startswith(b'ZF'):
        raise Refused('joblib <= 0.9 format: arrays live in side files, which this scan does not read')
    return raw


def _itemsize(dtype) -> tuple[int, bool]:
    """(bytes per element, holds objects) from a pickled numpy.dtype, read without building it."""
    if not (isinstance(dtype, Obj) and dtype.cls == Global('numpy', 'dtype')
            and isinstance(dtype.args, tuple) and dtype.args and isinstance(dtype.args[0], str)):
        raise Refused('an array whose dtype is not a plain numpy.dtype')
    code = dtype.args[0]
    state = dtype.state if isinstance(dtype.state, tuple) else ()
    if len(state) >= 8 and isinstance(state[7], int) and state[7] & HASOBJECT or code.startswith('O'):
        return 0, True
    if len(state) >= 5 and state[3] is not None:
        raise Refused(f'an array of structured dtype {code!r}: review it by hand')
    if len(state) >= 6 and isinstance(state[5], int) and state[5] > 0:
        return state[5], False
    import numpy  # only to read a type code such as 'f8'; no pickle is involved
    try:
        size = numpy.dtype(code).itemsize
    except TypeError as e:
        raise Refused(f'an array of unknown dtype {code!r}') from e
    if size == 0:
        raise Refused(f'an array of flexible dtype {code!r} with no element size')
    return size, False


def _skip_array(f: io.BytesIO, wrapper: Obj, report: Report, depth: int) -> None:
    """Step over the bytes joblib writes after a NumpyArrayWrapper's BUILD (joblib.numpy_pickle.write_array)."""
    state = wrapper.state
    if not isinstance(state, dict) or 'shape' not in state or 'dtype' not in state:
        raise Refused('a NumpyArrayWrapper without a readable shape and dtype')
    shape = state['shape']
    if not isinstance(shape, tuple) or not all(isinstance(n, int) and n >= 0 for n in shape):
        raise Refused('a NumpyArrayWrapper whose shape is not a tuple of counts')
    size, has_object = _itemsize(state['dtype'])
    report.arrays += 1
    if has_object:
        # Written as a nested pickle with its own stack; joblib loads it through the same find_class.
        _scan(f, report, depth + 1)
        return
    if state.get('numpy_array_alignment_bytes') is not None:
        pad = f.read(1)
        if len(pad) != 1:
            raise Refused('the file ends inside an array')
        f.seek(pad[0], io.SEEK_CUR)
    count = math.prod(shape) if shape else 1
    end = f.tell() + count * size
    if end > len(f.getbuffer()):
        raise Refused('an array runs past the end of the file')
    f.seek(end)


def _pop_mark(stack: list) -> list:
    for i in range(len(stack) - 1, -1, -1):
        if stack[i] is MARK:
            items = stack[i + 1:]
            del stack[i:]
            return items
    raise Refused('a malformed pickle: no MARK to pop to')


def _scan(f: io.BytesIO, report: Report, depth: int = 0) -> None:
    """Run the pickle's opcodes on a symbolic stack: values are tracked only as far as naming globals needs."""
    if depth > 8:
        raise Refused('arrays nested too deep')
    stack: list = []
    memo: dict = {}
    ops = pickletools.genops(f)
    while True:
        try:
            op, arg, _pos = next(ops)
        except StopIteration:
            raise Refused('the pickle ends without STOP') from None
        except ValueError as e:
            raise Refused(f'not a pickle stream here: {e}') from e
        name = op.name
        if name in NO_GLOBAL_PATH:
            raise Refused(f'{name} reaches objects without naming them')
        if name == 'STOP':
            return
        if name in ('PROTO', 'FRAME'):
            continue
        if name == 'GLOBAL':
            module, _, attr = arg.partition(' ')
            g = Global(module, attr)
            report.globals.add(g)
            stack.append(g)
        elif name == 'STACK_GLOBAL':
            attr, module = stack.pop(), stack.pop()
            if not (isinstance(module, str) and isinstance(attr, str)):
                raise Refused('STACK_GLOBAL with a name that is not a plain string')
            g = Global(module, attr)
            report.globals.add(g)
            stack.append(g)
        elif name == 'INST':
            module, _, attr = arg.partition(' ')
            g = Global(module, attr)
            report.globals.add(g)
            stack.append(Obj(g, tuple(_pop_mark(stack))))
        elif name == 'OBJ':
            items = _pop_mark(stack)
            if not items:
                raise Refused('OBJ without a class')
            stack.append(Obj(items[0], tuple(items[1:])))
        elif name == 'REDUCE':
            args, fn = stack.pop(), stack.pop()
            stack.append(Obj(fn, args))
        elif name == 'NEWOBJ':
            args, cls = stack.pop(), stack.pop()
            stack.append(Obj(cls, args))
        elif name == 'NEWOBJ_EX':
            _kwargs, args, cls = stack.pop(), stack.pop(), stack.pop()
            stack.append(Obj(cls, args))
        elif name == 'BUILD':
            state = stack.pop()
            target = stack[-1]
            if isinstance(target, Obj):
                target.state = state
                if target.cls == Global(*ARRAY_WRAPPER):
                    _skip_array(f, target, report, depth)
                    stack[-1] = OPAQUE  # joblib puts the array in the wrapper's place
        elif name == 'MARK':
            stack.append(MARK)
        elif name in ('POP',):
            stack.pop()
        elif name == 'POP_MARK':
            _pop_mark(stack)
        elif name == 'DUP':
            stack.append(stack[-1])
        elif name in ('PUT', 'BINPUT', 'LONG_BINPUT'):
            memo[arg] = stack[-1]
        elif name == 'MEMOIZE':
            memo[len(memo)] = stack[-1]
        elif name in ('GET', 'BINGET', 'LONG_BINGET'):
            if arg not in memo:
                raise Refused(f'memo {arg} read before it was written')
            stack.append(memo[arg])
        elif name in ('EMPTY_TUPLE',):
            stack.append(())
        elif name == 'TUPLE':
            stack.append(tuple(_pop_mark(stack)))
        elif name in ('TUPLE1', 'TUPLE2', 'TUPLE3'):
            n = int(name[-1])
            items = tuple(stack[-n:])
            del stack[-n:]
            stack.append(items)
        elif name == 'EMPTY_LIST':
            stack.append([])
        elif name == 'LIST':
            stack.append(_pop_mark(stack))
        elif name == 'APPEND':
            value = stack.pop()
            if isinstance(stack[-1], list):
                stack[-1].append(value)
        elif name == 'APPENDS':
            items = _pop_mark(stack)
            if isinstance(stack[-1], list):
                stack[-1].extend(items)
        elif name == 'EMPTY_DICT':
            stack.append({})
        elif name == 'DICT':
            items = _pop_mark(stack)
            stack.append(_dict(items))
        elif name == 'SETITEM':
            value, key = stack.pop(), stack.pop()
            _setitem(stack[-1], key, value)
        elif name == 'SETITEMS':
            items = _pop_mark(stack)
            for i in range(0, len(items) - 1, 2):
                _setitem(stack[-1], items[i], items[i + 1])
        elif name in ('EMPTY_SET', 'FROZENSET'):
            if name == 'FROZENSET':
                _pop_mark(stack)
            stack.append(OPAQUE)
        elif name == 'ADDITEMS':
            _pop_mark(stack)
        elif name in ('NONE',):
            stack.append(None)
        elif name in ('NEWTRUE', 'NEWFALSE'):
            stack.append(name == 'NEWTRUE')
        elif op.arg is not None and not op.stack_before:
            # A literal: ints, floats, strings, bytes (its value is the argument).
            stack.append(arg)
        else:
            raise Refused(f'opcode {name} is not one this scan models')


def _dict(items: list) -> dict:
    d: dict = {}
    for i in range(0, len(items) - 1, 2):
        _setitem(d, items[i], items[i + 1])
    return d


def _setitem(target, key, value) -> None:
    if isinstance(target, dict):
        try:
            target[key] = value
        except TypeError:
            pass  # an unhashable key holds nothing this scan needs


def scan(path: Path) -> Report:
    """Every (module, name) the file would import; raises Refused when that cannot be known."""
    report = Report(str(path))
    f = io.BytesIO(_stream(path.read_bytes()))
    _scan(f, report)
    if f.read(1):
        raise Refused('bytes after the pickle ends')
    return report


def load_allowlist(path: Path = ALLOWLIST) -> set:
    pairs = set()
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.split('#', 1)[0].strip()
        if line:
            module, _, attr = line.partition(' ')
            if not attr:
                raise ValueError(f'{path}: "{line}" is not "module name"')
            pairs.add(Global(module, attr.strip()))
    return pairs


def check(path: Path, allowed: set) -> list[str]:
    """Problems with one file; empty when every global it names is allowlisted."""
    try:
        report = scan(path)
    except Refused as e:
        return [f'{path.name}: refused: {e}']
    return [f'{path.name}: names {g.module}.{g.name}, which is not in {ALLOWLIST.name}'
            for g in sorted(report.globals - allowed, key=lambda g: (g.module, g.name))]


def model_files(target: Path) -> list[Path]:
    if target.is_file():
        return [target]
    return sorted(p for p in target.iterdir() if p.suffix in PICKLE_SUFFIXES)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Refuse model files that name a global outside the allowlist.')
    ap.add_argument('target', type=Path, help='a model file or a folder of them')
    ap.add_argument('--list', action='store_true', help='print every global each file names, for review')
    ap.add_argument('--allowlist', type=Path, default=ALLOWLIST)
    a = ap.parse_args(argv)
    files = model_files(a.target)
    if not files:
        print(f'no model files in {a.target}')
        return 1
    if a.list:
        for p in files:
            r = scan(p)
            print(f'# {p.name}: {len(r.globals)} globals, {r.arrays} arrays')
            for g in sorted(r.globals, key=lambda g: (g.module, g.name)):
                print(f'{g.module} {g.name}')
        return 0
    allowed = load_allowlist(a.allowlist)
    problems = [msg for p in files for msg in check(p, allowed)]
    for msg in problems:
        print(msg)
    if not problems:
        print(f'{len(files)} model files name only allowlisted globals')
    return 1 if problems else 0


if __name__ == '__main__':
    raise SystemExit(main())
