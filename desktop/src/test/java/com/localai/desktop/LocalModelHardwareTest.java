package com.localai.desktop;

import com.localai.contracts.ModelOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelHardwareTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void selectsLargestModelThatFitsDetectedHardwareAndDisk() {
        List<ModelOption> options = List.of(
                new ModelOption("qwen3:14b", 10 * GIB, 12 * GIB, 20 * GIB),
                new ModelOption("qwen3:30b", 20 * GIB, 22 * GIB, 32 * GIB),
                new ModelOption("qwen3:32b", 21 * GIB, 26 * GIB, 48 * GIB));
        LocalModelHardware hardware = new LocalModelHardware(64 * GIB, OptionalLong.of(24 * GIB), 100 * GIB);

        assertEquals("qwen3:30b", hardware.defaultModelId(options));
        assertEquals("Fits detected hardware", hardware.advisory(options.get(1)));
        assertTrue(hardware.advisory(options.get(2)).contains("VRAM"));
    }

    @Test
    void fallsBackToSmallestOptionWhenGpuMemoryCannotBeRead() {
        List<ModelOption> options = List.of(
                new ModelOption("qwen3:14b", 10 * GIB, 12 * GIB, 20 * GIB),
                new ModelOption("qwen3:30b", 20 * GIB, 22 * GIB, 32 * GIB));
        LocalModelHardware hardware = new LocalModelHardware(64 * GIB, OptionalLong.empty(), 100 * GIB);

        assertEquals("qwen3:14b", hardware.defaultModelId(options));
        assertTrue(hardware.advisory(options.getFirst()).contains("not detected"));
    }
}
