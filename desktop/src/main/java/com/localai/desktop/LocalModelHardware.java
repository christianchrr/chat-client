package com.localai.desktop;

import com.localai.contracts.ModelOption;
import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

public record LocalModelHardware(
        long systemRamBytes,
        OptionalLong gpuVramBytes,
        long freeDiskBytes,
        boolean freeDiskKnown) {
    public LocalModelHardware(long systemRamBytes, OptionalLong gpuVramBytes, long freeDiskBytes) {
        this(systemRamBytes, gpuVramBytes, freeDiskBytes, true);
    }

    public LocalModelHardware {
        if (systemRamBytes < 0 || gpuVramBytes == null || freeDiskBytes < 0) {
            throw new IllegalArgumentException("Hardware measurements are invalid");
        }
    }

    public static LocalModelHardware detect(Path dataDirectory) {
        long ram = 0;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof OperatingSystemMXBean operatingSystemMXBean) {
            ram = operatingSystemMXBean.getTotalMemorySize();
        }
        long disk = 0;
        boolean diskKnown = false;
        try {
            Path location = Files.exists(dataDirectory) ? dataDirectory : dataDirectory.toAbsolutePath().getParent();
            FileStore store = Files.getFileStore(location);
            disk = store.getUsableSpace();
            diskKnown = true;
        } catch (IOException | SecurityException e) {
            diskKnown = false;
        }
        return new LocalModelHardware(ram, detectGpuVram(), disk, diskKnown);
    }

    public String advisory(ModelOption option) {
        if (systemRamBytes < option.recommendedRamBytes()) {
            return "Needs about " + gibibytes(option.recommendedRamBytes())
                    + " GB system RAM; detected " + gibibytes(systemRamBytes) + " GB";
        }
        if (gpuVramBytes.isEmpty()) {
            return "GPU memory not detected; performance is uncertain";
        }
        if (gpuVramBytes.getAsLong() < option.recommendedVramBytes()) {
            return "Needs about " + gibibytes(option.recommendedVramBytes())
                    + " GB VRAM; detected " + gibibytes(gpuVramBytes.getAsLong())
                    + " GB. It may be slow or fail to load";
        }
        return "Fits detected hardware";
    }

    public boolean hasEnoughDisk(ModelOption option) {
        return freeDiskKnown && freeDiskBytes >= option.estimatedDownloadBytes();
    }

    public String diskAdvisory(ModelOption option) {
        if (!freeDiskKnown) {
            return "Free disk space could not be checked";
        }
        return hasEnoughDisk(option)
                ? "Requires about " + gibibytes(option.estimatedDownloadBytes()) + " GB free disk"
                : "Not enough free disk space; requires about "
                        + gibibytes(option.estimatedDownloadBytes()) + " GB";
    }

    /** Rough expectation of reply speed: Fast (fully on the GPU), Okay (usable), or Slow (partly or fully on the CPU). */
    public String speedEstimate(ModelOption option) {
        if (systemRamBytes < option.recommendedRamBytes()) {
            return "Slow";
        }
        long vram = gpuVramBytes.isPresent() ? gpuVramBytes.getAsLong() : 0;
        if (vram >= option.recommendedVramBytes()) {
            return "Fast";
        }
        if (vram >= option.recommendedVramBytes() * 3 / 4) {
            return "Okay";
        }
        return option.estimatedDownloadBytes() <= 3L * 1024 * 1024 * 1024 ? "Okay" : "Slow";
    }

    public String defaultModelId(List<ModelOption> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("At least one model option is required");
        }
        List<ModelOption> textModels = options.stream().filter(option -> !isVisionModel(option)).toList();
        List<ModelOption> candidates = (textModels.isEmpty() ? options : textModels).stream()
                .filter(option -> systemRamBytes >= option.recommendedRamBytes())
                .filter(this::hasEnoughDisk)
                .toList();
        return candidates.stream()
                .filter(option -> "Fast".equals(speedEstimate(option)))
                .max(Comparator.comparingLong(ModelOption::recommendedVramBytes))
                .or(() -> candidates.stream()
                        .filter(option -> "Okay".equals(speedEstimate(option)))
                        .max(Comparator.comparingLong(ModelOption::estimatedDownloadBytes)))
                .orElse(textModels.isEmpty() ? options.get(0) : textModels.get(0))
                .modelId();
    }

    private static boolean isVisionModel(ModelOption option) {
        String id = option.modelId().toLowerCase(java.util.Locale.ROOT);
        return id.contains("vision") || id.contains("vl:");
    }
    public String summary() {
        String gpu = gpuVramBytes.isPresent() ? gibibytes(gpuVramBytes.getAsLong()) + " GB VRAM" : "GPU VRAM unknown";
        String disk = freeDiskKnown ? gibibytes(freeDiskBytes) + " GB free disk" : "free disk unknown";
        return gibibytes(systemRamBytes) + " GB RAM, " + gpu + ", " + disk;
    }

    private static OptionalLong detectGpuVram() {
        OptionalLong nvidia = detectNvidiaVram();
        OptionalLong windows = detectWindowsAdapterVram();
        if (nvidia.isEmpty()) {
            return windows;
        }
        if (windows.isEmpty()) {
            return nvidia;
        }
        return OptionalLong.of(Math.max(nvidia.getAsLong(), windows.getAsLong()));
    }

    private static OptionalLong detectWindowsAdapterVram() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return OptionalLong.empty();
        }
        String script = "Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Class\\"
                + "{4d36e968-e325-11ce-bfc1-08002be10318}\\0*' -ErrorAction SilentlyContinue | "
                + "ForEach-Object { $v = $_.'HardwareInformation.qwMemorySize'; "
                + "if ($null -ne $v) { if ($v -is [byte[]]) { $v = [BitConverter]::ToUInt64($v,0) }; $v } }";
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OptionalLong.empty();
            }
            if (process.exitValue() != 0) {
                return OptionalLong.empty();
            }
            return parseAdapterMemory(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return OptionalLong.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static OptionalLong parseAdapterMemory(String output) {
        long max = output.lines()
                .map(String::trim)
                .filter(line -> line.matches("\\d{1,18}"))
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0);
        return max > 0 ? OptionalLong.of(max) : OptionalLong.empty();
    }

    private static OptionalLong detectNvidiaVram() {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OptionalLong.empty();
            }
            if (process.exitValue() != 0) {
                return OptionalLong.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            long maxMiB = output.lines()
                    .map(String::trim)
                    .filter(line -> line.matches("\\d+"))
                    .mapToLong(Long::parseLong)
                    .max()
                    .orElse(0);
            return maxMiB > 0 ? OptionalLong.of(maxMiB * 1024 * 1024) : OptionalLong.empty();
        } catch (IOException e) {
            return OptionalLong.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static long gibibytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024 * 1024));
    }
}
