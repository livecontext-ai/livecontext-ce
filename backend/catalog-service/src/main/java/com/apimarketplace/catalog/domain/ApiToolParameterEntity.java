package com.apimarketplace.catalog.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Entity representing a parameter for an API tool
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("api_tool_parameters")
public class ApiToolParameterEntity implements Persistable<UUID> {
    
    @Transient
    private boolean isNew = true;
    
    @Id
    @Column("id")
    private UUID id;
    
    @Column("api_tool_id")
    private UUID apiToolId;
    
    @Column("parameter_type")
    private String parameterType; // header, path, query, body
    
    @Column("name")
    private String name;
    
    @Column("data_type")
    private String dataType;
    
    @Column("is_required")
    private Boolean isRequired;
    
    @Column("description")
    private String description;
    
    @Column("example_value")
    private String exampleValue;
    
    @Column("default_value")
    private String defaultValue;
    
    @Column("allowed_values")
    private String allowedValues;
    
    @Column("file_path")
    private String filePath;

    @Column("extras")
    private String extras;

    @Column("is_hidden")
    private Boolean isHidden;

    @Column("created_at")
    private Long createdAt;
    
    @Override
    public UUID getId() {
        return id;
    }
    
    @Override
    public boolean isNew() {
        return isNew;
    }
    
    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }
}
