#!/usr/bin/env python3
"""Symbol gate for ReflectMaster 2.0.

Companion to static_gate.py. Without a compiler the most likely remaining
failures are "member does not exist" typos: calling `OUi.buttom(...)`,
`W.promt(...)`, `ConfigStore.saveHook(...)` and so on. This script builds a
declaration table for every Kotlin `object` / `class` in the project and then
checks every `Receiver.member` expression whose receiver is one of our own
singleton objects.

It deliberately only checks receivers that are *named types* (objects, classes
with nested types, companion constants), because those can be resolved without
type inference. Instance receivers would need a real type checker.

Exit code 0 means every referenced member exists.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

errors = []


def walk(base):
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        for f in filenames:
            if f.endswith(".kt") or f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def strip_comments(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if text[i:i + 3] == '"""':
            i += 3
            while i < n and text[i:i + 3] != '"""':
                i += 1
            i += 3
            continue
        out.append(c)
        i += 1
    return "".join(out)


DECL_RE = re.compile(
    r'\b(?:class|interface|object|enum\s+class)\s+([A-Za-z0-9_]+)'
)
MEMBER_RE = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:(?:public|private|internal|protected|override|open|abstract|final|'
    r'static|lateinit|suspend|inline|operator|infix|const|data|value|companion)\s+)*'
    r'(?:fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)',
    re.M
)
JAVA_MEMBER_RE = re.compile(
    r'^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?'
    r'[\w<>\[\],\s.?]+?\s+([A-Za-z0-9_]+)\s*\(',
    re.M
)
JAVA_FIELD_RE = re.compile(
    r'^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?'
    r'[\w<>\[\],.?]+\s+([A-Za-z0-9_]+)\s*[;=]',
    re.M
)

# name -> set of members
table = {}
# nested type owners, so `ScriptHost.Output` is accepted
nested = {}

for path in walk(SRC):
    text = strip_comments(open(path, encoding="utf-8").read())
    if path.endswith(".kt"):
        # top level containers declared in this file
        containers = DECL_RE.findall(text)
        members = set(MEMBER_RE.findall(text))
        for c in containers:
            table.setdefault(c, set()).update(members)
            nested.setdefault(c, set()).update(containers)
        # file name based facade (top level declarations)
        base = os.path.basename(path)[:-3]
        table.setdefault(base + "Kt", set()).update(members)
    else:
        cls = os.path.basename(path)[:-5]
        members = set(JAVA_MEMBER_RE.findall(text)) | set(JAVA_FIELD_RE.findall(text))
        table.setdefault(cls, set()).update(members)
        nested.setdefault(cls, set()).update(DECL_RE.findall(text))

# Kotlin synthetic accessors: `val foo` is readable as `foo`, and Java sees
# getFoo()/setFoo(); inside Kotlin both spellings can appear for properties.
for name, members in list(table.items()):
    extra = set()
    for m in members:
        extra.add(m)
    table[name] = members | extra

# Objects/classes we allow as an expression receiver.
RECEIVERS = {
    "OUi", "W", "Ui", "Reflect", "Values", "Registry", "Config", "ConfigStore",
    "HookConfig", "RmLog", "LogStore", "ScriptHost", "ScriptSyntax",
    "RemoteChannel", "ActivityTracker", "KeyTrigger", "CustomHooks",
    "ConsoleController", "ModuleBridge", "ObjectActions", "ViewPicker",
    "Surfaces", "ScriptState", "HooksActivity", "ScriptEditActivity",
    "LogActivity", "ConfigProvider",
}

# Members provided by the language / superclasses that we must not flag.
ALLOWED = {
    "class", "javaClass", "toString", "equals", "hashCode", "notify", "notifyAll",
    "wait", "getClass", "INSTANCE", "Companion", "values", "valueOf", "name",
    "ordinal", "copy", "component1", "component2", "component3", "component4",
    "component5", "component6", "component7", "invoke",
}

USE_RE = re.compile(r'(?<![\w."])([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)')


def resolves(recv, member):
    """True when `recv.member` can be resolved against our declaration table."""
    declared = table.get(recv, set())
    if member in declared:
        return True
    if member in nested.get(recv, set()):
        return True
    cap = member[0].upper() + member[1:]
    if ("get" + cap) in declared or ("set" + cap) in declared or ("is" + cap) in declared:
        return True
    # Java view of a Kotlin property: getFoo() / setFoo() / isFoo() -> foo
    for prefix in ("get", "set", "is"):
        if member.startswith(prefix) and len(member) > len(prefix):
            prop = member[len(prefix):]
            prop = prop[0].lower() + prop[1:]
            if prop in declared:
                return True
    return False


for path in walk(SRC):
    text = strip_comments(open(path, encoding="utf-8").read())
    where = os.path.relpath(path, ROOT)
    for m in USE_RE.finditer(text):
        recv, member = m.group(1), m.group(2)
        if recv not in RECEIVERS:
            continue
        if member in ALLOWED:
            continue
        if resolves(recv, member):
            continue
        line = text[:m.start()].count("\n") + 1
        declared = table.get(recv, set())
        errors.append("%s:%d  %s.%s 未声明（可用: %s）" % (
            where, line, recv, member,
            ", ".join(sorted(x for x in declared if not x.startswith("get"))[:14]) or "<none>"))

# Java side: X.Y.INSTANCE.member
INST_RE = re.compile(r'([A-Z][A-Za-z0-9_]*)\.INSTANCE\.([a-zA-Z_][A-Za-z0-9_]*)')
for path in walk(SRC):
    if not path.endswith(".java"):
        continue
    text = strip_comments(open(path, encoding="utf-8").read())
    where = os.path.relpath(path, ROOT)
    for m in INST_RE.finditer(text):
        recv, member = m.group(1), m.group(2)
        if resolves(recv, member):
            continue
        line = text[:m.start()].count("\n") + 1
        errors.append("%s:%d  %s.INSTANCE.%s 未声明" % (where, line, recv, member))

if errors:
    seen = set()
    for e in errors:
        if e in seen:
            continue
        seen.add(e)
        print("ERROR " + e)
    print("\n符号门禁失败：%d 处未声明引用" % len(seen))
    sys.exit(1)

print("符号门禁通过：%d 个声明类型，%d 个受检接收者，0 处未声明引用"
      % (len(table), len(RECEIVERS)))
