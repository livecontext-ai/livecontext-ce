package com.apimarketplace.catalog.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("tool_signals")
public class ToolSignalEntity {
    @Id
    @Column("tool_id")
    private UUID toolId;
    
    @Column("action")
    private String action;
    
    @Column("resource")
    private String resource;
    
    @Column("provider")
    private String provider;
    
    @Column("method")
    private String method;
    
    @Column("requires_user_credentials")
    private Boolean requiresUserCredentials;
    
    @Column("run_scope")
    private String runScope;
    
    @Column("is_active")
    private Boolean isActive;
    
    @Column("popularity")
    private Integer popularity;
    
    @Column("success_rate")
    private BigDecimal successRate;
    
    @Column("latency_ms_p50")
    private Integer latencyMsP50;
    
    @Column("updated_at")
    private Long updatedAt;
}
