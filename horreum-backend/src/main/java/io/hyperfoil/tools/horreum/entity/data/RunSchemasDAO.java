package io.hyperfoil.tools.horreum.entity.data;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

@Entity(name = "RunSchema")
@Table(
    name = "run_schemas"
)
// This entity is owned by RunDAO.schemas
public class RunSchemasDAO extends PanacheEntityBase {

    public static final String FIND_RUN_SCHEMAS_FROM_RUN_DATA_NATIVE = """
        WITH rs AS (
            SELECT id, testid, 0 AS type, NULL AS key, data->>'$schema' AS uri, 0 AS source FROM run WHERE id = ?1
            UNION SELECT id, testid, 1 AS type, values.key, values.value->>'$schema' AS uri, 0 AS source FROM run, jsonb_each(run.data) as values WHERE id = ?1 AND jsonb_typeof(data) = 'object'
            UNION SELECT id, testid, 2 AS type, (row_number() OVER () - 1)::::text AS key, value->>'$schema' as uri, 0 AS source FROM run, jsonb_array_elements(data) WHERE id = ?1 AND jsonb_typeof(data) = 'array'
            UNION SELECT id, testid, 2 AS type, (row_number() OVER () - 1)::::text AS key, value->>'$schema' as uri, 1 AS source FROM run, jsonb_array_elements(metadata) WHERE id = ?1 AND metadata IS NOT NULL
        ) SELECT rs.id as runid, rs.testid, rs.uri, schema.id as schemaid, rs.key, rs.type, rs.source
            FROM rs JOIN schema ON schema.uri = rs.uri
        """;

    @EmbeddedId
    public RunSchemasId id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schemaid")
    public SchemaDAO schema;

    @Embeddable
    public static class RunSchemasId {

        @NotNull
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "runid")
        public RunDAO run;

        @NotNull
        @Column(name="testid")
        public Integer testId;

        @NotNull
        public String uri;

        @NotNull
        public Integer type;

        @NotNull
        public Integer source;

        public String key;
    }


}
