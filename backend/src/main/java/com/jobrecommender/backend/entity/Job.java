package com.jobrecommender.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Node
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @org.springframework.data.neo4j.core.schema.Property("job_link")
    private String jobLink;

    @org.springframework.data.neo4j.core.schema.Property("job_title")
    private String title;
    private String type;
    private String level;

    @Relationship(type = "REQUIRES", direction = Relationship.Direction.OUTGOING)
    private List<Skill> skills = new ArrayList<>();

    @Relationship(type = "LOCATED_IN", direction = Relationship.Direction.OUTGOING)
    private Location location;
    
    @Relationship(type = "POSTED", direction = Relationship.Direction.INCOMING)
    private Company company;
}