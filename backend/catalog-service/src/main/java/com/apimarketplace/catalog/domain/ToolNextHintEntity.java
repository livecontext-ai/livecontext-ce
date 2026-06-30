package com.apimarketplace.catalog.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Entity for tool_next_hint table.
 * Stores hints for the LLM about what to do after using a tool.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("tool_next_hint")
public class ToolNextHintEntity {

    @Id
    private UUID id;

    @Column("api_tool_id")
    private UUID apiToolId;

    @Column("tool_name_id")
    private UUID toolNameId;

    @Column("hint")
    private String hint;

    @Column("next_tool_name")
    private String nextToolName;

    @Column("next_tool_id")
    private UUID nextToolId;

    @Column("priority")
    private Integer priority;

    @Column("condition_expression")
    private String conditionExpression;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Long createdAt;

    @Column("updated_at")
    private Long updatedAt;
}
