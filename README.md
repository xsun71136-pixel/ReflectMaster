# 反射大师 ReflectMaster 2.0

运行时反射检查器 / LSPosed 模块。在目标应用内呼出悬浮控制台，浏览并修改字段、调用方法与构造函数、拾取任意 View、执行 FakeScript 脚本、挂载自定义 Hook。

本仓库基于 `FormatFa/ReflectMaster` 1.9.1 **完全重写**。1.x 的最后一次提交是 2020-06-06，在 Android 11 + LSPosed 环境下已完全不可用（详见 [docs/REFACTOR-2.0.md](docs/REFACTOR-2.0.md) 的问题清单与证据）。

- 版本：`2.0.0` (versionCode 20)
- 包名：`formatfa.reflectmaster`
- 构建链：Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / JDK 17 / compileSdk 35 / minSdk 24 / targetSdk 34
- Hook 接口：XposedBridge API 82（`compileOnly libs/api-82.jar`），兼容 LSPosed / EdXposed
- 脚本引擎：FakeScript 1.0.3（`fake-script` 模块，源码未改动）

---

## 1. 安装与启用

1. 安装 APK（`ReflectMaster-2.0.0-debug.apk` 可直接安装；`app-release.apk` 仅在配置了签名密钥时已签名）。
2. 打开 **LSPosed 管理器 → 模块 → 反射大师 → 启用**。
3. 在 **作用域** 中勾选目标应用。若希望模块首页显示「已激活」以及框架版本，请把 **反射大师自身** 也加入作用域。
4. 强制停止目标应用后重新打开。
5. 在目标应用内 **长按音量下键约 0.6 秒** 呼出控制台。

> 模块首页会显示配置读取来源（`XSharedPreferences` / `直接读取 XML` / `ContentProvider`），以及当前生效的配置版本号；目标进程内的控制台底部状态行也会显示同样信息，便于排查"装了但没反应"。

## 2. 呼出方式

| 方式 | 说明 |
| --- | --- |
| 长按音量下 0.6 秒（默认） | 按下时先吞掉事件，未达阈值就原样回放 DOWN/UP，**短按仍然正常调节音量** |
| 常驻悬浮球 | 边缘吸附、可拖动、位置可持久化；点按展开面板，长按隐藏 |
| 关闭 | 只保留自定义 Hook，不显示任何界面 |

控制台默认 **注入到目标 Activity 自己的 decorView**，因此不需要悬浮窗权限，也不会出现 1.x 的 `BadTokenException`；文本输入框可以正常弹出输入法。若确实需要跨 Activity 的系统级悬浮窗，可在首页打开「改用系统悬浮窗」，但**宿主应用必须自己拥有 `SYSTEM_ALERT_WINDOW` 权限**，否则自动回退到 decorView 注入。

## 3. 控制台

一页式面板 + 分页签，所有下钻都在同一棵视图树里完成，「返回」是栈弹出，不会像 1.x 那样堆叠出关不掉的窗口。

- **对象**：Activity 栈 / 最近 Dialog / Application / Service / 变量槽 / 视图拾取 / 查找类 / ClassLoader 链 / 刷新配置
- **字段**：含父类、含静态开关，关键字过滤；点击下钻或编辑；长按 → 编辑值、存入变量槽、查看完整值、复制字段名 / 签名 / Hook 串、类型专属操作
- **方法**：点击弹出参数框调用（每个参数一行，长按可从变量槽插入）；长按 → 复制签名、复制 Hook 串
- **构造**：列出全部构造函数，传参实例化后直接下钻
- **脚本**：内置编辑器 + 运行 + 输出面板，可加载首页保存的脚本
- **日志**：模块在目标进程产生的日志（同时通过 ContentProvider 回传到模块 App 的日志页）

### 值语法

| 写法 | 含义 |
| --- | --- |
| `$v0` … `$v31` | 变量槽引用 |
| `$null` | null（基本类型会报错） |
| `$this` / `$ctx` | 当前对象 / 当前 Context |
| `0x1F` / `-0x1F` / `0b1010` | 十六进制 / 二进制 |
| `123` `123L` `1.5f` `1.5d` | 整型 / 长整型 / 单精度 / 双精度 |
| `'c'` | char |
| `[1,2,3]` | 数组字面量（元素按组件类型递归解析） |
| `ORDINAL_NAME` 或序号 | 枚举常量 |
| 其它 | 字符串，支持 `\n` `\t` `\u0041` 转义 |

1.x 只认 `int / boolean / long / byte`，其它一律当字符串传，调用 `float`、`double`、`short`、`char`、数组或枚举参数的方法必然失败。

### 类型专属操作

`TextView` 读写文本、`View` 显示/隐藏/触发点击/布局信息/父级、`ViewGroup` 子 View 列表与视图树、`ImageView`/`Drawable`/`Bitmap` 存图、`Collection`/`Map`/数组分页浏览、`Intent`/`Bundle` 展开、`Throwable` 打印堆栈、`String` Base64/Hex 解码、`byte[]` 保存与 Hex 预览、`Class` 静态字段与无参构造。

存图与 `byte[]` 不再写 `/sdcard`（Android 11 分区存储下写不进去），而是通过 ContentProvider 回传到模块私有目录的 **收件箱**，在模块首页可导出到 `Android/data/formatfa.reflectmaster/files/rm_export/`。

## 4. 脚本 API（FakeScript）

入口函数：脚本为 `func main()`，Hook 为 `func hook(param)`。

```
rf.print / log / toast / copy / summon
rf.thiz / ctx / activity / app
rf.slot(i) / setSlot(i,v) / store(v) / slotCount / clearSlots
rf.findClass(name) / fields(o) / methods(o) / describe(o) / typeOf(o)
rf.get(o,name) / set(o,name,v)
rf.call0..call3(o,name,args) / callStatic0..1(cls,name,args)
rf.getStatic / setStatic / newInstance0..2 / inspect(o)
rf.setResult / getResult / argCount / getArg / setArg / hasThrowable / throwable / hookThis

io.sleep(ms) / xplog(o) / log(o) / exists(p)
io.readString(p) / readBytes(p) / writeFile(p,data)
io.saveInbox(name,data) / inboxList
io.now / uptime / id(o) / describe(o) / toHex(o) / fromHex(s)
io.base64Encode(o) / base64Decode(s)
```

示例：

```
func main()
    rf.print("当前对象: " + rf.describe(rf.thiz()))
    rf.fields(rf.thiz())
    # 改一个字段
    # rf.set(rf.thiz(), "vipLevel", 9)
    # 调一个方法
    # rf.print(rf.call0(rf.thiz(), "toString"))
end
```

```
func hook(param)
    rf.print("命中 " + rf.describe(rf.hookThis()) + " 参数 " + rf.argCount())
    # before：改写参数或直接短路返回
    # rf.setArg(0, 1)
    # rf.setResult(true)
    # after：改写返回值
    # rf.setResult(rf.getResult())
end
```

编辑器里的「检查语法」会真正跑一遍 FakeScript 解析器（不是正则糊弄），能定位到出错行。

## 5. 自定义 Hook

结构化规则：生效包名（留空 = 所有已选目标）、类全名、方法名、参数类型（每行一个）、执行时机（before/after）、脚本体、启用开关。

- 解析顺序：参数类型精确匹配（含父类）→ 同名同参数个数 → 无参数类型且同名方法唯一时直接命中。多重载且未写参数类型时会记录一条明确的失败原因。
- 规则解析失败只跳过该条并记日志，**不会**中断其余 Hook（1.x 直接抛异常，导致该进程的后续 Hook 全部丢失）。
- 目标进程 `onResume` 时按配置版本号自动重载，改完规则切回目标应用即可生效，无需重启。
- 脚本 inline 执行，因此 `rf.setResult()` / `rf.setArg()` 真正有效（1.x 的 after hook 另起线程执行，改写返回值完全无效，还每次调用泄漏一个线程）。

## 6. 构建

```bash
./gradlew :app:assembleDebug       # 可直接安装（debug 签名）
./gradlew :app:assembleRelease     # 未配置密钥时为 unsigned
```

可选的固定签名：在项目根放一个 `keystore.properties`（已在 `.gitignore` 中）：

```
storeFile=/abs/path/rm.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

CI（GitHub Actions）在存在 `RM_KEYSTORE_BASE64` / `RM_STORE_PASSWORD` / `RM_KEY_ALIAS` / `RM_KEY_PASSWORD` 四个 secret 时自动签名，产物在 `ReflectMaster-2.0.0-apks`。CI 会先跑 `tools/static_gate.py` 与 `tools/symbol_gate.py` 两道静态门禁。

`release` 变体 **故意不开混淆**：本工具的全部价值都建立在反射之上，R8 会重命名 FakeScript 通过 `getMethods()` 绑定的类，并重排检查器依赖的签名。

## 7. 目录

```
app/            模块 App（UI + Hook + 悬浮控制台）
  src/main/java/formatfa/reflectmaster/
    core/       配置模型、读写、日志、激活握手、语法检查（两侧共用）
    hook/       Xposed 入口、三级配置读取、生命周期追踪、按键触发、自定义 Hook、脚本宿主
    hook/bridge/rf.java, io.java   FakeScript 绑定（Java，见文件内说明）
    overlay/    悬浮控制台（纯框架控件，不依赖 androidx）
    provider/   ConfigProvider：跨进程配置读取 + 日志/文件回传
    reflect/    反射原语、值编解码、变量槽
    ui/         模块自身界面（Material 3）
    util/
fake-script/    FakeScript 1.0.3 VM（vendored，源码未改）
tools/          图标生成脚本 + 两道静态门禁
docs/           重构说明
```

## 8. 免责声明

本工具用于学习、调试与互操作性研究。修改其他应用的运行时数据可能违反其服务条款，由此产生的一切后果由使用者自行承担。
