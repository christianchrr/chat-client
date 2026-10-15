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
        return new LocalModelHardware(ram, detectNvidiaVram(), disk, diskKnown);
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

    public String defaultModelId(List<ModelOption> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("At least one model option is required");
        }
        if (gpuVramBytes.isEmpty()) {
            return options.get(0).modelId();
        }
        return options.stream()
                .filter(option -> systemRamBytes >= option.recommendedRamBytes())
                .filter(option -> gpuVramBytes.getAsLong() >= option.recommendedVramBytes())
                .filter(this::hasEnoughDisk)
                .max(Comparator.comparingLong(ModelOption::recommendedVramBytes))
                .orElse(options.get(0))
                .modelId();
    }

    public String summary() {
        String gpu = gpuVramBytes.isPresent() ? gibibytes(gpuVramBytes.getAsLong()) + " GB VRAM" : "GPU VRAM unknown";
        String disk = freeDiskKnown ? gibibytes(freeDiskBytes) + " GB free disk" : "free disk unknown";
        return gibibytes(systemRamBytes) + " GB RAM, " + gpu + ", " + disk;
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
