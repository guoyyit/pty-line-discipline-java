# pty-line-discipline-java

带行规程（line discipline）的本地伪终端会话管理能力。上层组件（交互式工具、
远程会话桥接等）通过统一的 `PtySession` 抽象创建终端会话、写入输入、读取输出，
并接收由控制字符触发的信号，无需各自对接底层流。

## 核心模型

- `PtySessionManager`：会话/持有者的工厂与注册表，入口为 `PtySessionManager.create()`。
- `SessionHolder`：会话持有方（一个上层组件）。持有者退出（`close()`）时，其名下
  所有会话收到 HANGUP 并关闭，输入/输出缓冲与观察者注册状态全部释放，无半开连接。
- `PtySession`：单个伪终端会话，包含：
  - 输入流：终端（master）`writeInput` → 程序（slave）`readInput`；
  - 输出流：程序 `writeOutput` → 终端 `readOutput`；
  - `ControlParty`：接收 INTERRUPT / QUIT / HANGUP 信号的控制方；
  - `FlowControlHandle`：输出写入方观察高/低水位的 STOP / START 通知。

## 快速开始

```java
PtySessionManager manager = PtySessionManager.create();
SessionHolder holder = manager.createHolder("interactive-tool");
PtySession session = holder.createSession(SessionConfig.defaults());

ControlParty control = session.registerControlParty(
        "remote-1", SignalDeliveryPolicy.QUEUE, 64);

session.writeInput("hello\n".getBytes(StandardCharsets.UTF_8), 1000);
ReadResult line = session.readInput(64, 1000);          // 读到 "hello\n"

session.writeInput(new byte[]{ 0x03 }, 1000);           // ^C
PtySignal signal = control.awaitSignal(1000);           // INTERRUPT

holder.close();   // 或 session.close() / manager.shutdown()
```

## 行规程模式

通过 `SessionConfig.mode()` 配置，运行期可用 `session.setMode(...)` 原子切换。

### 规范模式 CANONICAL（默认）

- 输入按行缓冲，收到行结束符 `\n` 后整行一次性交付读取方；读取以“一行”为单位。
- 容量上限对整行写入同样生效：待编辑行中的每个字节都占用输入缓冲的一个
  预留槽位（`已缓冲字节 + 待编辑字节 ≤ 容量` 恒成立）。一行的总长度（含 `\n`）
  超过当前剩余容量时：
  - `OverflowPolicy.BLOCK`：写入方等待读取方腾出空间（以调用方超时为界，
    一次写入调用的总等待不超过该超时）；超时仍未凑齐时返回已接受字节数，
    其余计入 `bytesDiscarded` 并置 `timedOut=true`，未完成的行保留在待编辑
    缓冲中，可用 VKILL 清除或继续编辑；
  - `OverflowPolicy.DISCARD`：放不下的字节（含 `\n`）立即丢弃并计入
    `bytesDiscarded`，不阻塞。
  - 两种策略下读取方都不会读到残缺行，已提交的其他行也不会被覆盖；
    `inputBufferSize()` 在任何时刻都不超过配置容量。
- 编辑键实时改写尚未交付的待编辑行：
  - VERASE（默认 `0x7f`/DEL）：删除前一个字符；
  - VKILL（默认 `^U`，`0x15`）：删除整行；
  - VEOF（默认 `^D`，`0x04`）：待编辑行非空时立即冲刷该行（不带换行）；
    待编辑行为空时向读取方报告 EOF。
- VINTR / VQUIT / VSTOP / VSTART 等控制字符被解释，不作为普通输入字节交付。
- 一整行若大于调用方 `maxBytes`，会在后续读取中继续交付剩余部分。

### 原始模式 RAW

- 输入逐字节交付，不做行缓冲与编辑解释。
- 就绪语义由 `VMIN`（0..255）与 `VTIME`（毫秒）决定，可用
  `session.setVminVtime(vmin, vtimeMillis)` 调整：

| VMIN | VTIME | 行为 |
| --- | --- | --- |
| 0 | 0 | 纯非阻塞轮询，立即返回当前可用字节（可能为空）。 |
| 0 | >0 | 单次定时等待，VTIME 内有任意字节即返回，否则超时返回空。 |
| >0 | 0 | 阻塞直到至少 VMIN 字节（EOF/挂断除外）。 |
| >0 | >0 | 收到首字节后启动字节间计时器：凑齐 VMIN 即返回，或 VTIME 空闲后返回已到字节。 |

调用方超时参数（`readInput(maxBytes, timeoutMillis)`，`timeoutMillis > 0`）的
优先级高于上述 VMIN/VTIME 就绪语义：无论缓冲中是否已有部分字节，读取调用都
会在给定时间内返回。VMIN 未凑齐而调用方超时到达时，已缓冲的部分字节随本次
结果一并交付，并置 `timedOut=true` 以区别于“凑齐 VMIN 正常返回”
（`timedOut=false`）与 EOF/挂断等其他结束条件；`timeoutMillis = 0` 表示
不设置调用方超时，仅由 VMIN/VTIME 语义决定等待行为。

### 模式切换时未交付数据的处理

- 已经进入可交付输入队列的字节（含已完成的整行）一律保留，切换后仍可读出；
- 规范模式下正在编辑、尚未形成行的待编辑内容一律丢弃（语义等同于一次 VKILL）；
- 该规则对 CANONICAL 到 RAW 与 RAW 到 CANONICAL 两个方向一致。

## 特殊字符配置

`ControlCharacters` 独立配置 7 个特殊字符：`erase / kill / eof / intr / quit /
stop / start`。取值为字节值（0..255），或 `Defaults.DISABLED`（-1）表示禁用。

```java
ControlCharacters chars = ControlCharacters.defaults()
        .withIntr('z')                    // 把中断字符改成 z
        .withQuit(Defaults.DISABLED);    // 禁用退出字符
session.setControlCharacters(chars);
```

- 配置变更是原子的，即时对后续到达的字节生效；
- 已交付或正在交付（已在可交付队列中）的数据不受影响；
- 被禁用的控制字符按普通字节处理，不再触发编辑或信号。

默认值：VINTR=`^C`(0x03)、VQUIT=`^\`(0x1c)、VERASE=`DEL`(0x7f)、VKILL=`^U`(0x15)、
VEOF=`^D`(0x04)、VSTART=`^Q`(0x11)、VSTOP=`^S`(0x13)。

## 信号

- VINTR 投递 `INTERRUPT`，VQUIT 投递 `QUIT`；会话/持有者关闭时向每个控制方投递
  `HANGUP`。
- 同一次触发向每个已注册控制方恰好投递一次；单个控制方按触发顺序 FIFO 排队，
  不重复投递。
- 每控制方独立配置策略：
  - `SignalDeliveryPolicy.QUEUE`（默认）：信号进入有界队列，用 `pollSignal()` /
    `awaitSignal(timeout)` 按序取出；
  - `SignalDeliveryPolicy.DISCARD`：无法立即观察时丢弃，并通过 `droppedSignals()`
    给出可观测计数。
- 会话关闭后再触发的普通信号被丢弃；HANGUP 保证可被观察一次。

## 输出流控

- 输出缓冲达到高水位 `highWatermark` 时，向每个 `FlowControlHandle` 投递 STOP；
  回落到低水位 `lowWatermark` 以下时投递 START。
  - BLOCK 策略：写入方在流停止期间阻塞等待 START（尊重调用方超时），且在到达高水位
    之前即被软门控，避免越过水位、防止慢读取方丢数据；
  - DISCARD 策略：停止期间写入立即被拒，`WriteResult.flowStopped()` 返回 `true`。
- 输入侧 VSTOP / VSTART（仅规范模式解释）暂停/恢复输出交付：VSTOP 后已缓冲与
  新到的输出都暂不读出，VSTART 后恢复。
- 事件用 `pollEvent()` / `awaitEvent(timeout)` 消费；句柄事件队列满时丢弃并由
  `droppedEvents()` 计数，`isStopped()` 反映当前流状态。

## 并发与容量

- 同一会话支持多写入方与多读取方并发；字节按每次写入的字节块顺序交付，单个字节块
  不会交错；行规程状态转换与缓冲操作在会话锁下原子完成。
- 输入、输出缓冲均为有界环形缓冲，容量不会无界增长：
  - 容量判定与占用变更在会话锁下原子完成：多个写入方并发提交超过剩余容量的
    行数据时，任何时刻缓冲占用都不超过上限，每一行要么完整交付，要么按溢出
    策略完整拒绝；
  - `OverflowPolicy.BLOCK`：空间不足时阻塞（可带超时），`WriteResult.timedOut()`
    反映超时未全部写入；
  - `OverflowPolicy.DISCARD`：只接受放得下的字节，`bytesAccepted` /
    `bytesDiscarded()` 给出明确反馈。
- `WriteResult.bytesAccepted()` 只统计真正被保存或处理的字节；宣称接受但内容
  未完整保存的情况不会发生。
- `ReadResult` 通过 `data / eof / hangup / timedOut` 区分数据、EOF、挂断与超时
  （含调用方超时提前返回部分数据的场景）。

## 资源释放

- `session.close()` 幂等：投递 HANGUP、清空输入/输出缓冲、注销全部观察者、从管理器
  注册表移除；关闭后的写入返回 `closed=true`，读取排空后返回 `hangup=true`。
- `holder.close()` 关闭其名下全部会话；`manager.shutdown()` 关闭全部持有者与会话。

## 测试

```bash
mvn test
```

测试覆盖：两种行规程模式、编辑键组合、EOF、特殊字符重配置、信号投递顺序与
QUEUE/DISCARD、流控高/低水位与 VSTOP/VSTART、模式切换数据保留、并发多读写、
容量上限与生命周期清理；包括容量小于一行长度的规范模式整行写入、两种溢出策略
的超限反馈、容量接近上限时的编辑键行为、多写入方并发超容量提交、以及原始模式
各 VMIN/VTIME 组合下调用方超时的生效行为。
