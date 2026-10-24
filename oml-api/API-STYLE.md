# oml-api 风格指南

本文件约束 `oml-api` / `oml-api-26_3` 公开面的 API 形状。选了 Kotlin，就要用 Kotlin 的方式表达 API；
新增能力时对照本文件设计，改旧能力时顺手向它收敛。现有代码与本文件冲突的地方，以本文件为目标。

## 五条规则

### 1. 声明树用 receiver lambda，receiver 就是声明本身

配置一个东西，用带接收者的尾 lambda；receiver 是被配置的对象（或其声明类），**不许再造一层 facade/builder 包装**。
`ContentRegistry` 是样板：

```kotlin
registry.declareItem("test_sword") {
    maxDurability = 592
    attackDamage = 7.0
}
```

一次性出现的子声明（`blockEntity { }`、`generateAsOre { }`）必须用 `check` 拒绝二次声明。

### 2. 反应用尾 lambda，命名动词化

事件的注册、方块行为钩子、命令的 `executes { }`，都是 `onX { }` / `register { }` 形状的尾 lambda。
回调参数携带游戏对象本身，不携带包装层。

### 3. 动作用游戏类型上的扩展函数

对游戏对象做单个动作（生成实体、传送、点火、读写方块状态），落在 `oml-api-26_3` 的扩展函数/属性上，
不分配包装对象。扩展函数命名的词汇应该是玩家和 mod 作者认识的游戏语义词，不是内部类的方法名。

### 4. 类型化句柄优先于字符串寻址

凡是"运行期按名字取值"的地方，只要名字在声明期已知，就必须返回可传递的**类型化句柄**，
让取值点的类型进入编译期检查。命令参数是样板：声明时返回 `OMLArgument<T>`，handler 里 `source[handle]`
取回 `T`——拼错、类型错都在编译期爆。字符串寻址只允许用于真正的动态场景（配置键、方块实体数据键）。

### 5. 稳定层的词汇表不引用 vanilla 内部名

`oml-api`（版本无关层）的 API 名字必须是 OML/游戏语义词汇。vanilla 的类名、方法名、组件键名
只允许出现在 KDoc 里作为映射说明，以及 `oml-api-26_3` 和 adapter 的实现里。反例是 `destroyTime`
（`BlockBehaviour.Properties` 的方法名）——26.x 哪天改名，稳定层的"源码级稳定"承诺就漏了。
正例的写法是 ore 声明：API 叫 `veinSize`，KDoc 注一句 "vanilla `size`"。

## 什么时候不用 DSL

DSL 是手段不是目标，以下场景普通函数就是对的形状，硬套 lambda 反而更差：

- **对游戏对象的单个动作**：`player.teleport(...)`、`first.ignite(60)` 是好代码，
  不要发明 `player.action { teleportTo(...) }`。
- **只读查询**：`level.blockIdAt(pos)`、`server.gameRuleValue(id)` 保持普通函数。
- **没有配置树的东西**：一个参数能说清的 API 不要造 receiver；两个位置参数能说清的不要造 DSL。
- **receiver 里只有一个成员可用**：说明它不配当一个作用域。

## 稳定性口径

风格与稳定性的分工见 [README.md](README.md) 的三层契约。本文件补一条边界：
`oml-api` 对 mod 的承诺是 26.x 线内**源码级稳定**——所以本文件的五条规则在 `oml-api` 层是硬约束，
在 `oml-api-26_3` 层是倾向（那里允许跟随游戏形状，但仍然鼓励规则 3 的扩展函数风格）。
