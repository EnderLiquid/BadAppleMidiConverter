# BadAppleMidiConverter

将无音符重叠的 MIDI 文件转换为二进制音频事件序列的工具，可用于无源蜂鸣器播放。

## 功能

- 解析 MIDI 文件，提取音符事件
- 支持 Tempo 变化
- 输出时间-频率对的二进制数据

## 输出格式

二进制文件由文件头和事件数据组成：

**文件头** (4 字节, 小端):

| 偏移  | 类型  | 说明   |
| --- | --- | ---- |
| 0-3 | int | 事件总数 |

**事件数据** (每个事件 8 字节, 小端):

| 偏移  | 类型  | 说明         |
| --- | --- | ---------- |
| 0-3 | int | 绝对时间戳 (ms) |
| 4-7 | int | 频率 (Hz)    |

频率为 0 表示消音。

## 使用方法

1. 运行程序，自动生成 `config.properties`
2. 编辑配置文件指定输入输出路径
3. 再次运行执行转换

```properties
inputPath=./input/bad_apple.mid
outputPath=./output/bad_apple_melody.bin
```

## 依赖

- Java 21
- [owner](https://github.com/lviggiano/owner) - 配置管理

## 许可证

MIT License