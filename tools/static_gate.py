#!/usr/bin/env python3
"""Static gate for ReflectMaster 2.0.

The project is refactored without ever invoking a compiler, so this script is
the safety net. It re-checks the classes of mistake that a compiler would have
caught, plus the mistakes that are specific to this project:

  1. bracket balance in every .kt / .java file (comments and string literals
     stripped, including Kotlin raw strings and char literals)
  2. unterminated single-line string literals (a literal that swallows a newline)
  3. well-formedness of every XML resource + the manifest
  4. every `@type/name` reference inside XML resolves to a real resource
  5. every `R.type.name` reference inside Kotlin/Java resolves to a real resource
  6. every manifest `android:name` class exists in the sources
  7. the class named by assets/xposed_init exists
  8. every `import formatfa.reflectmaster...` resolves to a declared type
  9. hook-side packages stay framework-only (no androidx / Material) and avoid
     Kotlin stdlib APIs newer than 1.4, because inside a hooked process the
     winning kotlin-stdlib may be the host app's older copy
 10. known-bad legacy patterns are gone (android.support, TYPE_APPLICATION,
     MODE_WORLD_READABLE, /sdcard, jcenter, XSharedPreferences.canRead)
 11. build files agree on module list, SDK levels and the wrapper version

Exit code 0 means the gate passed.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_SRC = os.path.join(ROOT, "app", "src", "main")
JAVA_DIR = os.path.join(APP_SRC, "java")
RES_DIR = os.path.join(APP_SRC, "res")

errors = []
warnings = []

# Types synthesised by AGP; they are never declared in the sources.
GENERATED_TYPES = {
    "formatfa.reflectmaster.R",
    "formatfa.reflectmaster.BuildConfig",
    "formatfa.reflectmaster.Manifest",
}


def err(where, msg):
    errors.append("%s: %s" % (where, msg))


def warn(where, msg):
    warnings.append("%s: %s" % (where, msg))


# --------------------------------------------------------------------- inputs

def walk(base, exts):
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build")]
        for f in filenames:
            if any(f.endswith(e) for e in exts):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


sources = walk(JAVA_DIR, (".kt", ".java"))
layouts = walk(os.path.join(RES_DIR, "layout"), (".xml",))
xmls = walk(RES_DIR, (".xml",))
gradle_files = [os.path.join(ROOT, f) for f in
                ("build.gradle", "settings.gradle", "gradle.properties",
                 "app/build.gradle", "fake-script/build.gradle")]
gradle_files = [g for g in gradle_files if os.path.exists(g)]

print("sources: %d kt/java, %d layouts, %d xml resources" %
      (len(sources), len(layouts), len(xmls)))


def rel(p):
    return os.path.relpath(p, ROOT)


# ------------------------------------------------------ 1/2 source scanning

def strip_kt(text):
    """Return (code, in_string_newline_errors) with comments/strings blanked."""
    out = []
    i = 0
    n = len(text)
    line = 1
    str_start_line = 0
    problems = []
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] == "\n":
                    line += 1
                    out.append("\n")
                i += 1
            i += 2
            continue
        if c == '"' and text[i:i + 3] == '"""':
            # Kotlin raw string: runs to the next """ and may span lines.
            i += 3
            while i < n and text[i:i + 3] != '"""':
                if text[i] == "\n":
                    line += 1
                    out.append("\n")
                i += 1
            i += 3
            continue
        if c == '"':
            str_start_line = line
            i += 1
            closed = False
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    closed = True
                    i += 1
                    break
                if text[i] == "\n":
                    break
                i += 1
            if not closed:
                problems.append((str_start_line, "unterminated string literal"))
                # consume to end of line so scanning can continue
                while i < n and text[i] != "\n":
                    i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                if text[i] == "\n":
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out), problems


def strip_java(text):
    out = []
    i = 0
    n = len(text)
    line = 1
    problems = []
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] == "\n":
                    line += 1
                    out.append("\n")
                i += 1
            i += 2
            continue
        if c == '"':
            start = line
            i += 1
            closed = False
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    closed = True
                    i += 1
                    break
                if text[i] == "\n":
                    break
                i += 1
            if not closed:
                problems.append((start, "unterminated string literal"))
                while i < n and text[i] != "\n":
                    i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                if text[i] == "\n":
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out), problems


code_of = {}
for path in sources:
    text = open(path, encoding="utf-8").read()
    if path.endswith(".kt"):
        code, problems = strip_kt(text)
    else:
        code, problems = strip_java(text)
    code_of[path] = code
    for ln, msg in problems:
        err("%s:%d" % (rel(path), ln), msg)

    counts = {"(": 0, ")": 0, "{": 0, "}": 0, "[": 0, "]": 0}
    for ch in code:
        if ch in counts:
            counts[ch] += 1
    if counts["("] != counts[")"]:
        err(rel(path), "括号不平衡 ( %d vs ) %d" % (counts["("], counts[")"]))
    if counts["{"] != counts["}"]:
        err(rel(path), "花括号不平衡 { %d vs } %d" % (counts["{"], counts["}"]))
    if counts["["] != counts["]"]:
        err(rel(path), "方括号不平衡 [ %d vs ] %d" % (counts["["], counts["]"]))


# --------------------------------------------- 3/4/5 resource cross-reference

resources = {"string": set(), "color": set(), "dimen": set(), "style": set(),
             "drawable": set(), "mipmap": set(), "layout": set(), "id": set(),
             "array": set(), "plurals": set(), "bool": set(), "integer": set(),
             "attr": set()}

# values files declare string/color/dimen/style/...
for path in xmls:
    d = os.path.basename(os.path.dirname(path))
    if not d.startswith("values"):
        continue
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        err(rel(path), "XML 解析失败: %s" % e)
        continue
    for child in root:
        tag = child.tag
        name = child.get("name")
        if not name:
            continue
        if tag == "string":
            resources["string"].add(name)
        elif tag == "color":
            resources["color"].add(name)
        elif tag == "dimen":
            resources["dimen"].add(name)
        elif tag == "style":
            resources["style"].add(name)
        elif tag in ("string-array", "array", "integer-array"):
            resources["array"].add(name)
        elif tag == "bool":
            resources["bool"].add(name)
        elif tag == "integer":
            resources["integer"].add(name)
        elif tag == "attr":
            resources["attr"].add(name)
        elif tag == "item":
            t = child.get("type")
            if t and t in resources:
                resources[t].add(name)

# file based resources
for path in xmls + walk(RES_DIR, (".png", ".jpg", ".webp", ".9.png")):
    d = os.path.basename(os.path.dirname(path))
    if d.startswith("values"):
        continue
    base = os.path.basename(path)
    name = re.sub(r"\.(xml|png|jpg|webp)$", "", base)
    name = re.sub(r"\.9$", "", name)
    if d.startswith("layout"):
        resources["layout"].add(name)
    elif d.startswith("drawable"):
        resources["drawable"].add(name)
    elif d.startswith("mipmap"):
        resources["mipmap"].add(name)
    elif d.startswith("color"):
        resources["color"].add(name)

# ids declared in layouts and menus
for path in layouts + walk(os.path.join(RES_DIR, "menu"), (".xml",)):
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        continue
    for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', text):
        resources["id"].add(m.group(1))

# 3: XML well formed
for path in xmls:
    try:
        ET.parse(path)
    except ET.ParseError as e:
        err(rel(path), "XML 格式错误: %s" % e)

# 4: @type/name references inside XML
for path in xmls:
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'@(\+?)([a-z]+)/([A-Za-z0-9_.]+)', text):
        plus, kind, name = m.group(1), m.group(2), m.group(3)
        if plus:
            continue
        if kind == "android":
            continue
        if kind not in resources:
            err(rel(path), "未知资源类型 @%s/%s" % (kind, name))
            continue
        if name not in resources[kind]:
            # styles may reference parents from libraries (Widget.Material3.*)
            if kind == "style" and "." in name:
                continue
            err(rel(path), "缺失资源 @%s/%s" % (kind, name))

# 5: R.type.name references inside sources
for path in sources:
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'(?<![\w.])R\.([a-z]+)\.([A-Za-z0-9_]+)', text):
        kind, name = m.group(1), m.group(2)
        # skip android.R (preceded by "android.")
        start = m.start()
        prefix = text[max(0, start - 9):start]
        if prefix.endswith("android."):
            continue
        if kind not in resources:
            err(rel(path), "未知 R 类型 R.%s.%s" % (kind, name))
            continue
        if name not in resources[kind]:
            err(rel(path), "缺失资源 R.%s.%s" % (kind, name))

# every layout must be referenced by some source file
for name in sorted(resources["layout"]):
    if ("R.layout." + name) not in "".join(open(p, encoding="utf-8").read() for p in sources):
        warn("res/layout/%s.xml" % name, "布局没有被任何源文件引用")


# ------------------------------------------------------- 6/7 manifest checks

manifest_path = os.path.join(APP_SRC, "AndroidManifest.xml")
declared_types = {}
for path in sources:
    text = code_of[path]
    pkg_match = re.search(r'^\s*package\s+([A-Za-z0-9_.]+)', text, re.M)
    pkg = pkg_match.group(1) if pkg_match else ""
    for m in re.finditer(
            r'\b(?:public\s+|private\s+|internal\s+|abstract\s+|open\s+|sealed\s+|data\s+|final\s+|static\s+)*'
            r'(?:class|interface|object|enum\s+class|@interface)\s+([A-Za-z0-9_]+)', text):
        declared_types.setdefault(pkg + "." + m.group(1), path)

try:
    mroot = ET.parse(manifest_path).getroot()
except ET.ParseError as e:
    err("AndroidManifest.xml", "解析失败: %s" % e)
    mroot = None

if mroot is not None:
    manifest_pkg = "formatfa.reflectmaster"
    app = mroot.find("application")
    if app is None:
        err("AndroidManifest.xml", "缺少 <application>")
    else:
        nodes = [("application", app)]
        for tag in ("activity", "service", "receiver", "provider"):
            for el in app.findall(tag):
                nodes.append((tag, el))
        for tag, el in nodes:
            name = el.get("{http://schemas.android.com/apk/res/android}name")
            if not name:
                continue
            fq = name if name.startswith(manifest_pkg) else manifest_pkg + name
            if fq not in declared_types:
                err("AndroidManifest.xml", "<%s> 声明的类不存在: %s" % (tag, fq))
            if tag in ("activity", "service", "receiver", "provider"):
                exported = el.get("{http://schemas.android.com/apk/res/android}exported")
                has_filter = el.find("intent-filter") is not None
                if exported is None and (has_filter or tag == "provider"):
                    err("AndroidManifest.xml",
                        "%s 缺少 android:exported（targetSdk>=31 必须显式声明）" % fq)
        # manifest resource references
        text = open(manifest_path, encoding="utf-8").read()
        for m in re.finditer(r'@([a-z]+)/([A-Za-z0-9_.]+)', text):
            kind, name = m.group(1), m.group(2)
            if kind in resources and name not in resources[kind]:
                if kind == "style" and "." in name:
                    continue
                err("AndroidManifest.xml", "缺失资源 @%s/%s" % (kind, name))

    # xposed_init
    init_path = os.path.join(APP_SRC, "assets", "xposed_init")
    if not os.path.exists(init_path):
        err("assets/xposed_init", "文件不存在，LSPosed 无法加载模块")
    else:
        for line in open(init_path, encoding="utf-8"):
            cls = line.strip()
            if not cls or cls.startswith("#"):
                continue
            if cls not in declared_types:
                err("assets/xposed_init", "类不存在: %s" % cls)


# ------------------------------------------------------- 8 internal imports

known_pkgs = set()
for fq in declared_types:
    known_pkgs.add(fq.rsplit(".", 1)[0])

for path in sources:
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'^\s*import\s+(formatfa\.reflectmaster\.[A-Za-z0-9_.]+)', text, re.M):
        fq = m.group(1)
        if fq in GENERATED_TYPES:
            continue
        if fq in declared_types:
            continue
        # nested type: a.b.Outer.Inner
        parent = fq.rsplit(".", 1)[0]
        if parent in declared_types or parent in GENERATED_TYPES:
            continue
        if fq.endswith(".*"):
            continue
        err(rel(path), "import 无法解析: %s" % fq)


# ------------------------------------------- 9/10 hook-side purity + legacy

HOOK_SIDE_DIRS = ("hook", "overlay", "reflect", "provider")
NEWER_STDLIB = [
    (r'\.lowercase\(\)', "String.lowercase() 需要 Kotlin 1.5+"),
    (r'\.uppercase\(\)', "String.uppercase() 需要 Kotlin 1.5+"),
    (r'toBooleanStrictOrNull', "toBooleanStrictOrNull 需要 Kotlin 1.5+"),
    (r'firstNotNullOfOrNull', "firstNotNullOfOrNull 需要 Kotlin 1.5+"),
    (r'\bbuildList\b', "buildList 需要 Kotlin 1.6+"),
    (r'\bbuildMap\b', "buildMap 需要 Kotlin 1.6+"),
    (r'\bbuildString\b', "buildString 可用但请确认宿主 stdlib"),
    (r'\bgetOrDefault\(', "Map.getOrDefault 在低版本 stdlib 上可能缺失"),
    (r'kotlin\.time\.', "kotlin.time 需要较新 stdlib"),
    (r'\.removeSuffix\(', "removeSuffix 请确认宿主 stdlib"),
    (r'\bifEmpty\b', "ifEmpty 需要 Kotlin 1.3+"),
    (r'\bresultOf\b', "resultOf 需要 Kotlin 1.5+"),
    (r'runCatching', "runCatching 需要 Kotlin 1.3+"),
    (r'\bisNotEmpty\(\)\s*\?\s*', ""),
]

BANNED = [
    (r'android\.support\.', "旧 support 库，必须使用 AndroidX"),
    (r'\bjcenter\s*\(\s*\)', "jcenter 已停服"),
    (r'MODE_WORLD_READABLE', "API 24+ 抛 SecurityException"),
    (r'TYPE_APPLICATION\b(?!_)', "TYPE_APPLICATION 不能用于 addView，Android 11 会 BadTokenException"),
    (r'"/sdcard', "Android 11 分区存储下不可写"),
    (r'\bxsp\s*\.\s*canRead', "XSharedPreferences.canRead() 仅存在于 API 93，api-82.jar 没有"),
    (r'getDefaultDisplay\(\)', "API 30 起废弃，使用 resources.displayMetrics"),
    (r'\bAsyncTask\b', "API 30 起废弃"),
    (r'getDeviceId\(\)', "API 26+ 抛 SecurityException"),
]

# Scan the comment-stripped code so documentation about a bad pattern is not
# itself flagged as using it.
for path in sources:
    code = code_of[path]
    where = rel(path)
    for pattern, msg in BANNED:
        for m in re.finditer(pattern, code):
            line = code[:m.start()].count("\n") + 1
            err("%s:%d" % (where, line), "禁用模式 %s（%s）" % (pattern, msg))

for path in gradle_files:
    raw = open(path, encoding="utf-8").read()
    stripped = "\n".join(
        re.sub(r'(^|\s)//.*$', '', re.sub(r'^\s*#.*$', '', ln))
        for ln in raw.split("\n")
    )
    where = rel(path)
    for pattern, msg in BANNED:
        for m in re.finditer(pattern, stripped):
            line = stripped[:m.start()].count("\n") + 1
            err("%s:%d" % (where, line), "禁用模式 %s（%s）" % (pattern, msg))

for path in sources:
    parts = path.replace(os.sep, "/")
    hookside = any(("/" + d + "/") in parts for d in HOOK_SIDE_DIRS)
    is_core_shared = parts.endswith(("core/Config.kt", "core/ConfigStore.kt",
                                     "core/Log.kt", "core/LogStore.kt",
                                     "core/ModuleBridge.kt", "core/ScriptSyntax.kt"))
    if not (hookside or is_core_shared):
        continue
    code = code_of[path]
    where = rel(path)
    for pattern, msg in NEWER_STDLIB:
        if not msg:
            continue
        for m in re.finditer(pattern, code):
            line = code[:m.start()].count("\n") + 1
            warn("%s:%d" % (where, line), "hook 侧新 stdlib API: %s" % msg)

    # framework-only rule
    if any(("/" + d + "/") in parts for d in ("overlay", "hook", "reflect")):
        for m in re.finditer(r'^\s*import\s+(androidx\.[\w.]+|com\.google\.android\.material[\w.]*)',
                             code, re.M):
            line = code[:m.start()].count("\n") + 1
            err("%s:%d" % (where, line),
                "hook 侧禁止依赖 %s（宿主进程里版本不可控）" % m.group(1))
        # Resources of the module are NOT available inside the hooked process,
        # so overlay/hook/reflect must never touch R.* or the module UI.
        for m in re.finditer(r'(?<![\w.])R\.[a-z]+\.', code):
            line = code[:m.start()].count("\n") + 1
            err("%s:%d" % (where, line),
                "hook 侧不能使用模块资源 R.*（宿主进程里解析不到）")
        for m in re.finditer(r'import\s+formatfa\.reflectmaster\.(ui|R)\b', code):
            line = code[:m.start()].count("\n") + 1
            err("%s:%d" % (where, line), "hook 侧不能依赖模块 UI 层")


# ------------------------------------------------------------- 11 build files

def read(p):
    return open(p, encoding="utf-8").read()


settings = read(os.path.join(ROOT, "settings.gradle"))
for module in (":app", ":fake-script"):
    if ("'%s'" % module) not in settings and ('"%s"' % module) not in settings:
        err("settings.gradle", "缺少模块 %s" % module)
for dead in (":ScriptParser", ":Test"):
    if ("'%s'" % dead) in settings:
        err("settings.gradle", "仍在引用已删除的模块 %s" % dead)

app_gradle = read(os.path.join(ROOT, "app", "build.gradle"))
for key, minimum in (("minSdk", 24), ("targetSdk", 30), ("compileSdk", 34)):
    m = re.search(key + r"\s+(\d+)", app_gradle)
    if not m:
        err("app/build.gradle", "缺少 %s" % key)
    elif int(m.group(1)) < minimum:
        err("app/build.gradle", "%s=%s 低于要求的 %d" % (key, m.group(1), minimum))
if "namespace 'formatfa.reflectmaster'" not in app_gradle:
    err("app/build.gradle", "AGP 8 需要 namespace 声明")
if "compileOnly files('libs/api-82.jar')" not in app_gradle:
    err("app/build.gradle", "Xposed API 必须以 compileOnly 方式引入")
if not os.path.exists(os.path.join(ROOT, "app", "libs", "api-82.jar")):
    err("app/libs/api-82.jar", "Xposed API jar 缺失")

manifest_text = read(manifest_path)
if 'package="' in manifest_text.split(">", 1)[0]:
    err("AndroidManifest.xml", "AGP 8 不允许在 manifest 中声明 package 属性")
if "xposedminversion" not in manifest_text:
    err("AndroidManifest.xml", "缺少 xposedminversion meta-data")
if "QUERY_ALL_PACKAGES" not in manifest_text:
    err("AndroidManifest.xml", "缺少 QUERY_ALL_PACKAGES（Android 11 包可见性）")

wrapper = read(os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties"))
m = re.search(r"gradle-(\d+)\.(\d+)", wrapper)
if not m or int(m.group(1)) < 8:
    err("gradle-wrapper.properties", "Gradle 版本过低，AGP 8.x 需要 8.x")
if not os.path.exists(os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")):
    err("gradle/wrapper", "缺少 gradle-wrapper.jar")

root_gradle = read(os.path.join(ROOT, "build.gradle"))
if "mavenCentral()" not in root_gradle:
    err("build.gradle", "缺少 mavenCentral 仓库")


# ------------------------------------------------------------------- summary

print()
for w in warnings:
    print("WARN  " + w)
print()
if errors:
    for e in errors:
        print("ERROR " + e)
    print("\n静态门禁失败：%d 个错误，%d 个警告" % (len(errors), len(warnings)))
    sys.exit(1)

print("静态门禁通过：0 错误，%d 警告" % len(warnings))
print("  - %d 个源文件括号/字符串字面量平衡" % len(sources))
print("  - %d 个 XML 资源格式正确" % len(xmls))
print("  - %d 个字符串 / %d 个颜色 / %d 个 id / %d 个布局资源已定义" %
      (len(resources["string"]), len(resources["color"]),
       len(resources["id"]), len(resources["layout"])))
print("  - manifest 组件与 xposed_init 入口全部存在")
