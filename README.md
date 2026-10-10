# OhMyLoader (OML)

一个面向 Minecraft Java Edition 26.x 的模组加载器：自研字节码注入引擎（替代 Mixin 底层）、两层 mod API
（版本无关的声明与调度 + 直接以 26.3 原生类型表达的游戏访问层）、数据驱动的声明式内容注册，客户端与专用
服务端双端支持。当前稳定主线为 26.3，全线运行在 Java 27 上。

## 特性

- **自研字节码注入引擎**：声明式注入 DSL + Mixin 类合并（`@Shadow` / `@Overwrite` / `@Accessor` …），
  启动期对全部规则做存在性 / 签名 / `require` 自检，杜绝"规则静默失效"。
- **统一 mod API**：`@Mod` 入口 + 函数式事件注册（tick（整服务端与逐世界）/ 服务端与玩家生命周期（含重生）/
  方块与实体的破坏、右键、使用 / 伤害（数值可改写）/ 死亡 / 聊天 / GUI / 世界加载 / 区块开始运行与卸载 / HUD /
  帧率限制 …）。
  事件载荷直接携带游戏自己的对象（`ServerPlayer`、`BlockPos`、`BlockState`、`DamageSource`、`Screen`…），
  mod 拿到的就是游戏正在用的那一个，没有包装层也没有逃生舱。
- **26.3 游戏访问 API**（`org.ohmyloader.api26_3`）：`OMLGame.server` / `OMLGame.client` 就是
  `MinecraftServer` / `Minecraft` 本身，其余能力全部是以游戏类型为实现者的扩展函数与属性——不分配包装对象，
  也就没有跨 tick 失效的句柄。实体生成与查询（含活体专用入口）、运动向量、属性基础值与常驻修正、
  主动造成伤害（走的正是会触发 `LIVING_HURT` 的那条路径）、点火、
  方块状态（属性名寻址）读写、粒子（含自带参数的尘埃 / 物品 / 方块）、天气（读实时等级，写走 `/weather`
  同一条路径）、游戏规则按 id 读写、按维度时钟（26.3 的 `world.clock`：计数 / 速率 / 暂停 / 具名时刻标记）、
  时间线表按 id 读回（`clockDrivenBy` / `timeMarkersOf` / `markerIdsDriving`，含 `/time set` 不展示的标记）、
  维度与维度类型读回（`dimensionTypeIds` / `dimensionTypeId` / `dimensionTypeById`，世界本身就是游戏为
  数据包条目建起来的 `ServerLevel`）、强制加载票据（`forceLoadChunk` 走游戏的 `TicketType.FORCED`，读回同一张票据表）、
  方块存储（`containerAt` 返回游戏自己的 `Container`：槽位读写遵守方块自身的拒绝规则、可列出正在使用它的
  玩家）、玩家所用菜单与关闭、穿戴槽（`itemIn` / `equip` / `unequip` 按槽名与 id 寻址，能否进某槽由游戏的
  `isEquippableInSlot` 判定，被拒不改动原穿戴物）、效果、音效、传送、权限等级、区块加载判定与物品取出都在这一层。
  `OMLScheduler`（版本无关）把非游戏线程的工作排回所属侧的 tick 队列。
- **声明式内容注册**：`ContentRegistry` 声明方块 / 物品 / 配方 / 掉落 / 伤害类型 / 时钟与时刻标记 /
  维度类型与维度 / 行为钩子 / BlockEntity / 矿石世界生成；方块与物品在注册表冻结点材料化为原生内容，配方、
  掉落、伤害类型、时钟 / 时间线与维度则写成数据包 JSON 经 OML 注入的资源包走 vanilla 自己的重载路径。
- **注册表与标签读取**：直接读 `BuiltInRegistries` 与数据包表，`org.ohmyloader.api26_3` 的
  `byId` / `ids` / `contains` / `membersOf` / `tagsOf` 扩展按 id 寻址（未注册的 id 返回 null，不返回默认值），
  mod 自己注册的内容与原版条目同等可查。
- **自定义网络载荷**：`OMLNetworkProvider` 声明通道 + `OMLPacketBuffer` 自有编解码，双端按通道名路由
  （复用 vanilla 固定的两个 payload 包 id），单人集成服务端与专用服务端共用同一份声明。
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
        event.cancel() // 拦截聊天（cancellable 事件由 cancel() 取消，canceled 只读）
      }
      // event.packet 就是收到的那条 Clientbound...Packet，不再只给 OML 预先消化过的文本
    }
    Events.BLOCK_BREAK.register { event ->
      if (event.state.blockId == "minecraft:bedrock") event.cancel() // 玩家与坐标都是游戏对象
    }
  }
}
```

> mod 的编译类路径由官方 Gradle 插件挂好：`oml-api`、`oml-api-26_3`（按 `oml { minecraftVersion }`
> 推导坐标）、游戏本体 jar 与该版本运行库。因此 mod 的类**不能**放在 `org.ohmyloader.` 前缀下——那一段
> 是 parent-first，模组类会被启动器层定义，而启动器层没有游戏运行库，直接引用 `net.minecraft.*` 会以
> `NoClassDefFoundError` 收场。

修改游戏内部未暴露的逻辑时，直接写注入规则（无需 Mixin）：规则放在一个 `@RuleSource` 标注、实现
`RuleSetProvider` 的类里，由 loader 在启动期读取并自检。

```kotlin
@RuleSource("my_mod")
object MyRules : RuleSetProvider {
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
}
```

> mod 以 jar 形式分发。纯声明式内容可以免代码：把 `content.toml` 连同自带 `assets/` 打成 **`.oml` 归档**
> （普通 zip 改名）放进 `mods/`，归档名即命名空间。散装 `.toml` 文件不再受支持——它无法携带贴图，
> 方块会渲染成缺失材质——loader 会对它明确报错并跳过。

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
  `oml-api`（版本无关的 mod API：事件基元、内容声明、调度、配置、网络、注入与 mixin）、
  `oml-api-26_3`（26.3 版本面向 API：事件载荷与扩展函数直接命名游戏类型，必须由主加载器按 child-first
  定义）、`oml-content`（版本无关的内容注册与 `.oml`/TOML 装载）、
  `oml-launcher`（自举头）、`oml-adapter-common`（各版本共享的钩子、事件桥、内容材料化与网络桥）、
  `oml-adapter-26_3` 与 `oml-adapter-snapshot`
  （版本驱动层，薄）、`oml-native`（zig 构建的 C 库，Zstd 编解码）、`oml-devtools`（下载器）、
  `oml-installer`（安装器，含 Java 8 引导 Stub）。
- **调试开关**：`-Doml.injection.verify=fail|warn|off`（注入规则自检，默认 fail）；
  `-Doml.diagnostics=<任意非 false 的值>`（改写耗时与规模统计，开关是布尔的，不取值）。
- **installLauncher**：各 adapter 模块上的任务，把 OML 装进真实启动器（版本隔离必填：
  `-PomlIsolation=true|false`，安装 id 用 `-PomlInstallId`，服务端实例加 `-PomlSide=server`）。
- **平台**：工具链覆盖 windows / linux / osx（含 arm64 变体）；双端 E2E 门禁在 Linux 上真机验证，
  Windows 侧尚未实测。
- **路线图**：26.4 正式版适配（快照跟踪中）· 多版本矩阵（长期）· 分发渠道。

## 许可证

本项目依据 **AGPL-3.0** 许可证开源。
