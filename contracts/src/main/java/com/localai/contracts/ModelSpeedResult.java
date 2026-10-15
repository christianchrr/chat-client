package com.localai.contracts;

public record ModelSpeedResult(String modelId, double tokensPerSecond, double loadSeconds, int tokens) {
}