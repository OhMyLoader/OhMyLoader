# OhMyLoader (OML)

一个面向 Minecraft Java Edition 26.x 的模组加载器：自研字节码注入引擎（替代 Mixin 底层）、统一的高层
mod API、数据驱动的声明式内容注册，客户端与专用服务端双端支持。当前稳定主线为 26.3，全线运行在 Java 27 上。

## 特性

- **自研字节码注入引擎**：声明式注入 DSL + Mixin 类合并（`@Shadow` / `@Overwrite` / `@Accessor` …），
  启动期对全部规则做存在性 / 签名 / `require` 自检，杜绝"规则静默失效"。
- **统一 mod API**：`@Mod` 入口 + 函数式事件注册（tick / 聊天 / GUI / 世界加载 / 帧率限制 …），
  面向高层语义编程，不接触版本内部结构；原生游戏对象随时可通过 `e.platform` 逃生舱访问。
- **声明式内容注册**：`ContentRegistry` 声明方块 / 物品 / 配方，在注册表冻结点材料化为原生内容，
  走 vanilla 自己的校验与数据包路径。
- **mod 资产注入**：每个 mod jar 的 `assets/` 经注入的资源包对游戏完整可见（任意命名空间、任意目录）。
- **双端支持**：客户端与专用服务端独立钩子；`oml.side` 选边。
- **零映射**：26.x jar 未混淆，类名 / 成员名直接可读，无重映射链路。

## 安装

从 [Releases](https://github.com/OhMyLoader/OhMyLoader/releases) 下载安装器，`java -jar` 启动，选择
目标启动器（标准 / Prism / 专用服务端）、游戏版本与目录即可。游戏本体由启动器自行下载，安装器只装 OML 层。

## 写一个 mod

```kotlin
@Mod(id = "my_mod", name = "My Mod", version = "1.0.0")
class MyMod : OMLModInitializer {
  override fun onInitialize(context: ModContext) {
    Events.CLIENT_TICK.register { /* 每帧处理 */ }
    Events.CHAT_RECEIVED.register { event ->
      if (event.message.contains("bad_word")) {
        event.canceled = true // 拦截聊天
      }
    }
  }
}
```

修改游戏内部未暴露的逻辑时，直接写注入规则（无需 Mixin）：

```kotlin
override fun rules(): RuleSet = injection {
  classTarget("net/minecraft/client/Minecraft") {
    method("runTick") {
      atHead { call("com/example/MyRules", "onGameTick", "()V") }
      require(1) // 启动期强校验，杜绝规则静默失效
    }
    field("proxy", desc = "Ljava/net/Proxy;") {
      makePublic()
      removeFinal()
    }
  }
}
```

> 数据驱动内容包（`.toml` 声明方块 / 物品，免代码）在路线图上，当前尚未实现；mod 目前以 jar 形式分发。

## 开发构建

仓库拆分为三部分，互相只按 Maven 坐标消费：本仓（loader）、`OhMyLoaderGradle`（Gradle 插件）、
`OhMyLoaderTestMod`（端到端验证模组）。开发闭环：

```bash
# 本仓改动后发布到本地 Maven 仓库：
./gradlew publishToMavenLocal
# 在 OhMyLoaderTestMod 构建并启动游戏（自动拉取游戏 jar / 运行库 / natives / 资产）：
./gradlew runClient
./gradlew runServer
# 全量编译与单元测试（本仓）：
./gradlew build
# 产出安装器 fat jar（写入 dist/）：
./gradlew :oml-installer:shadowJar
```

**追快照**：testmod 的 `oml { minecraftVersion.set("snapshot") }` —— 运行期解析为版本清单里的
`latest.snapshot`，adapter 使用 `oml-adapter-snapshot`（跟随快照迭代的工作副本），新快照发布无需改任何配置。

### 工具链

JDK 27（Zulu）· Gradle 9.8.0 · Kotlin 2.5.0-Beta1（当前唯一声明 JVM 27 目标的 Kotlin 线；
`build-logic` 因 `kotlin-dsl` 绑定 Gradle 内嵌编译器，暂以 JVM 26 目标运行，合法无害）。

### 追加说明

- **模块结构**：`oml-core`（加载器内核：类加载 / 注入执行器 / Mixin 前端与类合并 / SPI）、
  `oml-api`（mod 面向的 API）、`oml-launcher`（自举头）、`oml-adapter-26_3` 与 `oml-adapter-snapshot`
  （版本驱动层，薄）、`oml-native`（zig 构建的 C 库，Zstd 编解码）、`oml-devtools`（下载器）、
  `oml-installer`（安装器，含 Java 8 引导 Stub）。
- **调试开关**：`-Doml.injection.verify=fail|warn|off`（注入规则自检，默认 fail）；
  `-Doml.diagnostics=inject`（改写耗时与规模统计）。
- **installLauncher**：各 adapter 模块上的任务，把 OML 装进真实启动器（版本隔离必填：
  `-PomlIsolation=true|false`，安装 id 用 `-PomlInstallId`，服务端实例加 `-PomlSide=server`）。
- **平台**：工具链覆盖 windows / linux / osx（含 arm64 变体）；端到端真机验证的基线是 Windows。
- **路线图**：26.4 正式版适配（快照跟踪中）· 数据驱动内容轨 · 多版本矩阵（长期）。

## 许可证

本项目依据 **AGPL-3.0** 许可证开源。
