package top.enderliquid;

import javax.sound.midi.*;
import org.aeonbits.owner.Accessible;
import org.aeonbits.owner.Config;
import org.aeonbits.owner.ConfigFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class BadAppleMidiConverter {
    public static final String CONFIG_PATH = "./config.properties";
    public static final String DEFAULT_INPUT_PATH = "./input/bad_apple.mid";
    public static final String DEFAULT_OUTPUT_PATH = "./output/bad_apple_melody.bin";

    @Config.Sources({"file:" + CONFIG_PATH})
    public interface ConvertConfig extends Accessible {
        @DefaultValue(DEFAULT_INPUT_PATH)
        String inputPath();
        @DefaultValue(DEFAULT_OUTPUT_PATH)
        String outputPath();
    }

    private static ConvertConfig initConfig() {
        try {
            Path configFilePath = Paths.get(CONFIG_PATH);
            ConvertConfig config = ConfigFactory.create(ConvertConfig.class);
            if (!Files.exists(configFilePath)) {
                Files.createDirectories(configFilePath.getParent());
                try (FileOutputStream out = new FileOutputStream(configFilePath.toFile())) {
                    config.store(out, "BadAppleMidiConverter Config");
                }
                System.out.println("已创建默认配置文件: " + CONFIG_PATH);
                System.out.println("请编辑配置文件后重新运行");
                System.exit(0);
            }
            List<String> invalidFields = new ArrayList<>();
            if (config.inputPath() == null) invalidFields.add("inputPath");
            if (config.outputPath() == null) invalidFields.add("outputPath");
            if (!invalidFields.isEmpty()) {
                throw new RuntimeException(
                        String.format("配置参数错误: %s", String.join(", ", invalidFields))
                );
            }
            return config;
        } catch (IOException e) {
            System.err.println("错误: 初始化配置文件失败: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    // 内部类：保存绝对时间(ms)和频率(Hz)
    public static class ToneEvent {
        public double timeMs;
        public double frequency;
        public ToneEvent(double timeMs, double frequency) {
            this.timeMs = timeMs;
            this.frequency = frequency;
        }
    }

    // 辅助方法：判断事件优先级，确保同一时刻 NOTE_OFF 优先于 NOTE_ON 处理（用于连音情况）
    private static int getEventTypePrior(MidiMessage message) {
        if (message instanceof ShortMessage sm) {
            int cmd = sm.getCommand();
            int velocity = sm.getData2();
            if (cmd == ShortMessage.NOTE_OFF || (cmd == ShortMessage.NOTE_ON && velocity == 0)) {
                return 0; // NOTE_OFF 最优先
            }
            if (cmd == ShortMessage.NOTE_ON && velocity > 0) {
                return 1; // NOTE_ON 其次
            }
        }
        return 2; // 其他事件
    }

    // 状态合并方法：如果同一毫秒发生多个状态改变，以最后一个为准
    private static void addEvent(List<ToneEvent> events, double timeMs, double frequency) {
        ToneEvent lastEvent = events.get(events.size() - 1);
        if (lastEvent.timeMs == timeMs) {
            lastEvent.frequency = frequency; // 同一时间覆盖旧状态
            return;
        }
        events.add(new ToneEvent(timeMs, frequency));
    }

    public static void convertMidiToFile(String inputPath, String outputPath) {
        File midiFile = new File(inputPath);

        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(midiFile);
        } catch (IOException e) {
            throw new RuntimeException("MIDI文件读取失败");
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException("MIDI文件数据错误");
        }

        int resolution = sequence.getResolution(); // 解析度（每四分音符的Tick数）

        // 提取所有轨道事件放入一个集合（处理包含Tempo Map的0轨和包含音符的1轨）
        List<MidiEvent> allEvents = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                allEvents.add(track.get(i));
            }
        }

        // 按 Tick 时间排序；若 Tick 相同，则先处理关闭音符，再处理开启音符
        allEvents.sort(
                Comparator.comparingLong(MidiEvent::getTick)
                          .thenComparingInt(e -> getEventTypePrior(e.getMessage()))
        );

        List<ToneEvent> events = new ArrayList<>();
        events.add(new ToneEvent(0, 0)); // 初始化状态：0ms 时频率为 0
        double currentMs = 0;
        long lastTick = 0;
        int tempoUs = 500000; // 默认Tempo：120 BPM -> 500,000 微秒/四分音符

        int lastNote = -1;
        for (MidiEvent event : allEvents) {
            long tick = event.getTick();
            MidiMessage message = event.getMessage();

            // 计算自上次事件经过了多少毫秒并累加
            long deltaTicks = tick - lastTick;
            if (deltaTicks > 0) {
                currentMs += deltaTicks * (tempoUs / 1000.0) / resolution;
                lastTick = tick;
            }

            // 解析速度改变事件 (Tempo Change)
            if (message instanceof MetaMessage mm) {
                if (mm.getType() == 0x51) {
                    byte[] data = mm.getData();
                    tempoUs = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                }
            }

            // 解析音符事件
            else if (message instanceof ShortMessage sm) {
                int cmd = sm.getCommand();
                int note = sm.getData1();
                int velocity = sm.getData2();
                if (cmd == ShortMessage.NOTE_ON && velocity > 0) {
                    // MIDI音高转频率计算公式：f = 440 * 2^((note - 69) / 12)
                    double freq = 440.0 * Math.pow(2.0, (note - 69.0) / 12.0);
                    addEvent(events, currentMs, freq);
                    lastNote = note;
                } else if (cmd == ShortMessage.NOTE_OFF || (cmd == ShortMessage.NOTE_ON && velocity == 0)) {
                    // 如果不相等，说明音符发生了重叠
                    // 此时新的音符正在播放或已播放完毕
                    // 应该忽略旧音符的停止逻辑
                    if (note == lastNote) {
                        addEvent(events, currentMs, 0); // 频率置为0表示消音
                        lastNote = -1; // 重置为无音符状态，避免发送重复的 NOTE OFF
                    }
                }
            }
        }
        try (BufferedOutputStream bof = new BufferedOutputStream(new FileOutputStream(outputPath))) {
            // 写入文件头：事件总数 (4 bytes, 小端)
            ByteBuffer header = ByteBuffer.allocate(4);
            header.order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(events.size());
            bof.write(header.array());

            System.out.println("转换结果:");
            System.out.printf("%10s%8s%n","时间(ms)","频率(Hz)");
            System.out.println("------------------------");
            for (ToneEvent event : events) {
                ByteBuffer buffer = ByteBuffer.allocate(8);
                buffer.order(ByteOrder.LITTLE_ENDIAN); // 小端模式输出
                buffer.putInt((int) event.timeMs); // 时间 (4 bytes)
                buffer.putInt((int) event.frequency); // 频率 (4 bytes)
                bof.write(buffer.array());
                System.out.printf("%10d%8d%n", (int) event.timeMs,(int) event.frequency);
            }
            System.out.println("------------------------");
            System.out.println("共 " + events.size() + " 个事件，已写入: " + outputPath);
        } catch (IOException e) {
            throw new RuntimeException("输出到文件失败");
        }
    }

    public static void main(String[] args) {
        try {
            ConvertConfig config = initConfig();
            convertMidiToFile(config.inputPath(), config.outputPath());
        } catch (RuntimeException e) {
            System.err.printf("错误: %s%n%s",
                    e.getMessage(),
                    Arrays.stream(e.getStackTrace())
                          .map(StackTraceElement::toString)
                          .collect(Collectors.joining(System.lineSeparator()))
            );
        }
    }
}