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

    @Test
    void parsesLargestAdapterMemoryFromWindowsRegistryOutput() {
        assertEquals(OptionalLong.of(25757220864L),
                LocalModelHardware.parseAdapterMemory("536870912\r\n25757220864\r\n"));
        assertEquals(OptionalLong.empty(), LocalModelHardware.parseAdapterMemory("error\r\n"));
    }

    @Test
    void recommendsSmallModelsOnComputersWithoutAUsableGpu() {
        List<ModelOption> options = List.of(
                new ModelOption("llama3.2:latest", 2 * GIB, 4 * GIB, 8 * GIB),
                new ModelOption("qwen3:4b", 3 * GIB, 4 * GIB, 8 * GIB),
                new ModelOption("qwen3:8b", 6 * GIB, 8 * GIB, 16 * GIB),
                new ModelOption("qwen2.5vl:7b", 6 * GIB, 8 * GIB, 16 * GIB));
        LocalModelHardware cpuOnly = new LocalModelHardware(16 * GIB, OptionalLong.empty(), 100 * GIB);
        assertEquals("qwen3:4b", cpuOnly.defaultModelId(options));
        assertEquals("Okay", cpuOnly.speedEstimate(options.get(1)));
        assertEquals("Slow", cpuOnly.speedEstimate(options.get(2)));

        LocalModelHardware midRange = new LocalModelHardware(32 * GIB, OptionalLong.of(8 * GIB), 100 * GIB);
        assertEquals("qwen3:8b", midRange.defaultModelId(options));
        assertEquals("Fast", midRange.speedEstimate(options.get(2)));

        LocalModelHardware tiny = new LocalModelHardware(8 * GIB, OptionalLong.of(GIB), 100 * GIB);
        assertEquals("qwen3:4b", tiny.defaultModelId(options));
    }
}