package com.apimarketplace.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centralized hard limits for the orchestrator - applied across render, execution,
 * and CRUD paths. Prevents unbounded result sets from consuming heap.
 *
 * <p>Render guards:
 * <ul>
 *   <li>{@code maxItemsPerRender} - carousel epoch/item page-size cap.
 *   <li>{@code maxRowsPerVariable} - per-variable Collection clamp (render path).
 *   <li>{@code maxResolvedVariableBytes} - cumulative bytes budget across resolved variables.
 *   <li>{@code maxStorageRowBytes} - SQL-level size predicate on storage rows.
 *   <li>{@code maxPayloadBytes} - rendered HTML bytes cap (datasource path).
 * </ul>
 *
 * <p>Execution guards:
 * <ul>
 *   <li>{@code maxOutputRows} - CRUD Read row cap. Applied as SQL LIMIT on the
 *       datasource query AND as a post-fetch truncation safety net. Prevents large
 *       tables (e.g. 540-row cumulative email table) from entering heap.
 * </ul>
 */
@Component
@ConfigurationProperties("orchestrator.limits")
public class OrchestratorLimitsConfig {

    public enum OnExceed { truncate, fail }

    private int maxItemsPerRender = 100;
    private int maxRowsPerVariable = 200;
    private int maxOutputRows = 500;
    private int maxResolvedVariableBytes = 512_000;
    private int maxStorageRowBytes = 10_485_760;
    private int maxPayloadBytes = 2 * 1024 * 1024;
    private OnExceed onExceed = OnExceed.truncate;

    public int getMaxItemsPerRender() { return maxItemsPerRender; }
    public void setMaxItemsPerRender(int v) { this.maxItemsPerRender = Math.max(1, v); }

    public int getMaxRowsPerVariable() { return maxRowsPerVariable; }
    public void setMaxRowsPerVariable(int v) { this.maxRowsPerVariable = Math.max(1, v); }

    public int getMaxOutputRows() { return maxOutputRows; }
    public void setMaxOutputRows(int v) { this.maxOutputRows = Math.max(1, v); }

    public int getMaxResolvedVariableBytes() { return maxResolvedVariableBytes; }
    public void setMaxResolvedVariableBytes(int v) { this.maxResolvedVariableBytes = Math.max(1024, v); }

    public int getMaxStorageRowBytes() { return maxStorageRowBytes; }
    public void setMaxStorageRowBytes(int v) { this.maxStorageRowBytes = Math.max(1024, v); }

    public int getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(int v) { this.maxPayloadBytes = Math.max(1024, v); }

    public OnExceed getOnExceed() { return onExceed; }
    public void setOnExceed(OnExceed v) { this.onExceed = v != null ? v : OnExceed.truncate; }
}
