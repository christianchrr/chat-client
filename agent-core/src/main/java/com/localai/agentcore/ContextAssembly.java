package com.localai.agentcore;

import com.localai.contracts.ContextManifest;

public record ContextAssembly(String prompt, ContextManifest manifest) {
}
