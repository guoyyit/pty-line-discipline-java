# pty-line-discipline-java

带行规程（line discipline）的本地伪终端会话管理。上层组件通过统一的
`PtySession` 抽象创建会话、写入键盘输入、读取程序输出，并接收由控制字符
触发的信号，而不必直接对接底层流。

所有会话都带**有界输入/输出缓冲**、**单一锁保护的原子状态转换**，支持多
写入方与多读取方并发。

## 快速上手

```java
PtySessionManager manager = new PtySessionManager();
SessionHolder holder = manager.createHolder();

PtySession session = manager.createSession(holder);
session.setControlParty(new BufferedControlParty(64));

// 写入键盘输入（会经过行规程）
session.writeInput("ls\n".getBytes());

// 读取方读取已交付的行
byte[] buf = new byte[64];
int n = session.readInput(buf, 0, buf.length, 1, TimeUnit.SECONDS);

// 程序侧写入输出，用户侧读取
session.writeOutput("hello".getBytes(), 0, 5);

// 持有方退出：关联会话收到 HANGUP 并释放缓冲
holder.exit();
```

## 行规程模式

通过 `SessionOptions.setMode(...)` 或 `session.setMode(...)` 切换。

### 规范模式（`LineMode.CANONICAL`，默认）

- 输入按行缓冲，只有收到行结束符（默认 `\n`，可配置）后整行一次性交付。
- 擦除（默认 `DEL` / `0x7F`）删除待交付行最后一个字符；整行删除
  （`KILL`，默认 `Ctrl-U` / `0x15`）清空当前编辑行。
- 文件结束（`EOF`，默认 `Ctrl-D` / `0x04`）立即交付当前已输入内容，
  EOF 字节本身不交付。
- `readInput` 在有完整行可用前不会返回数据。

### 原始模式（`LineMode.RAW`）

- 输入逐字节直通，不做行编辑、不等待行结束符。
- 就绪语义遵循 termios 的 `VMIN` / `VTIME`（`VTIME` 单位为 0.1 秒）：
  - `VMIN > 0, VTIME = 0`：阻塞直到至少 `VMIN` 个字节可用（或调用方超时）。
  - `VMIN > 0, VTIME > 0`：字节间计时器，首字节到达后若 `VTIME` 内无新字节
    则返回当前已缓冲的字节（用于区分"整包"与"零散尾包"）。
  - `VMIN = 0, VTIME > 0`：纯读定时器，无数据时等待 `VTIME` 后返回 0，
    有数据立即返回。
  - `VMIN = 0, VTIME = 0`：读立即返回当前可用字节（可能为 0）。
- 调用方传入的超时始终是硬上限；超时到期但已有部分字节时返回这些字节，
  无字节时返回 0，不会静默丢数据。

两种模式下中断、退出、停止、启动控制字符都生效。

### 模式切换时未交付数据的处理

- 尚未结束的规范模式编辑行（用户正在输入、还未按回车的内容）在切换时
  **丢弃**，因为它仅存在于行规程的编辑状态中。
- 已经交付进入输入队列的数据（例如规范模式下已按回车的完整行、原始模式
  已接收的字节）**保留**，切换后仍可按先入先出读取。
- 流控状态不随模式切换改变。

## 特殊字符配置（`SpecialChars`）

可独立配置擦除、整行删除、文件结束、中断、退出、停止、启动、行结束符。
取值为字节值 `0..255`，或 `SpecialChars.DISABLED`（`-1`，表示该控制字符
关闭，对应字节按普通数据处理）。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `erase` | `0x7F` (DEL) | 删除前一个字符 |
| `kill` | `0x15` (Ctrl-U) | 删除整行 |
| `eof` | `0x04` (Ctrl-D) | 文件结束 / 立即交付 |
| `interrupt` | `0x03` (Ctrl-C) | 触发 `INTERRUPT` |
| `quit` | `0x1C` (Ctrl-\\) | 触发 `QUIT` |
| `stop` | `0x13` (Ctrl-S) | 暂停输出流 |
| `start` | `0x11` (Ctrl-Q) | 恢复输出流 |
| `lineEnd` | `0x0A` (`\n`) | 规范模式行结束符 |

`session.setSpecialChars(...)` 即时生效：只影响后续到达的字节，已经交付或
正在读取的数据不受影响。`getSpecialChars()` 返回深拷贝，修改后需重新 set。

## 信号投递

- 中断、退出控制字符到达时，按**触发顺序**向 `ControlParty` 投递
  `INTERRUPT` / `QUIT`；每个控制字节只投递一次。
- 规范模式下触发中断/退出会先把当前编辑行刷入输入队列（不丢弃用户已输入
  的内容），再投递信号。
- 控制方通过返回值给出可观测结果：
  - `DELIVERED`：接收方正在等待，信号被直接取走；
  - `QUEUED`：接收方未就绪，信号进入其内部有界队列；
  - `DROPPED`：队列已满或接收方主动丢弃。
  内置 `BufferedControlParty` 是线程安全的有界实现，可用 `poll()` /
  `take(timeout)` 按顺序取出 `SignalEvent`。
- 会话关闭时向控制方投递恰好一次 `HANGUP`，之后释放控制方引用；重复
  `close()` 不会重复投递。

## 输出流控与水位

输出缓冲超过**高水位**时向输出侧发出停止（语义上的 Ctrl-S），通过
`OutputFlowListener` 通知 `STOPPED`；读取方消费使缓冲回落到**低水位**
以下时发出启动（Ctrl-Q），通知 `RUNNING`。

- 流处于停止期间，写入方继续写入会得到 `WriteResult.rejected(FLOW_STOPPED)`，
  防止慢读取方丢数据；带超时的写入会等待恢复或返回该原因。
- 手动 Ctrl-S/Ctrl-Q 与高/低水位自动流控叠加：任一原因要求停止即停止，
  手动停止必须由 Ctrl-Q 解除，期间即使低于低水位也不会自动恢复。
- 多写入方并发写入时字节以整次提交为单位进入队列，读取方看到的字节顺序
  不会交错。

## 容量与溢出策略

`SessionOptions` 中输入/输出缓冲均有硬性容量上限：

```java
options.setInputCapacity(4096);
options.setOutputCapacity(4096);
options.setHighWaterMark(3072);   // 必须 <= outputCapacity
options.setLowWaterMark(1024);    // 必须 < highWaterMark
options.setInputOverflowPolicy(OverflowPolicy.BLOCK);
options.setOutputOverflowPolicy(OverflowPolicy.BLOCK);
```

- `BLOCK`（默认）：缓冲满时，非阻塞写入返回
  `WriteResult.rejected(BUFFER_FULL)`；带超时的写入阻塞到有空位或超时。
- `DROP`：写入尽可能放入剩余空间，`WriteResult.accepted(n)` 中的 `n`
  为实际接收字节数，多余字节被丢弃，缓冲永不无界增长。
- `writeInput` / `writeOutput` 返回 `WriteResult(accepted, bytesAccepted,
  reason)`，拒绝原因包括 `FLOW_STOPPED`、`BUFFER_FULL`、`CLOSED`。

## 会话持有方与生命周期

- `PtySessionManager.createSession()` / `createSession(holder)` 创建会话，
  会话在管理器中登记，可通过 `findSession(id)` 查找。
- `SessionHolder` 表示会话持有方（如一个交互式工具或远程连接）。
  `holder.exit()` 时其关联会话全部收到 `HANGUP`、关闭并从管理器注销，
  输入/输出缓冲与行规程编辑状态、控制方/监听器注册全部释放，不残留半开
  连接；对已退出 holder 新建的会话会立即关闭。
- 会话单独 `close()` 会自动从 manager 与 holder 注销，且幂等。

## 线程模型

每个 `PtySession` 用一把可重入锁保护输入队列、输出队列、行规程编辑状态、
流控状态和注册状态，因此行规程状态转换是原子的；阻塞读写基于
`Condition`，`close()` 会唤醒全部等待者。控制方/流控监听器回调在会话锁
之外不应回调会话的阻塞方法，以避免使用方侧死锁。

## 测试

```bash
mvn test
```

覆盖两种行规程、编辑键组合、特殊字符重配置与禁用、信号顺序与去重、
流控高低水位、容量上限的阻塞/丢弃策略、并发读写与关闭唤醒、持有方挂断
与资源释放。
