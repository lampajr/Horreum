package io.hyperfoil.tools.horreum.api.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.databind.node.ObjectNode;

@Schema(type = SchemaType.OBJECT, description = "A map of label names to label values with the associated datasetId and runId")
public class ExportedLabelValues {
    @Schema
    public LabelValueMap values;
    @Schema(type = SchemaType.INTEGER, description = "the run id that created the dataset", example = "101")
    public Integer runId;
    @Schema(type = SchemaType.INTEGER, description = "the unique dataset id", example = "101")
    public Integer datasetId;

    @NotNull
    @Schema(type = SchemaType.STRING, implementation = Instant.class, description = "Start timestamp", example = "2019-09-26T07:58:30.996+0200")
    public Instant start;
    @NotNull
    @Schema(type = SchemaType.STRING, implementation = Instant.class, description = "Stop timestamp", example = "2019-09-26T07:58:30.996+0200")
    public Instant stop;

    public ExportedLabelValues() {
    }

    public ExportedLabelValues(LabelValueMap v, Integer runId, Integer datasetId, Instant start, Instant stop) {
        this.values = v;
        this.runId = runId;
        this.datasetId = datasetId;
        this.start = start;
        this.stop = stop;

    }

    public static List<ExportedLabelValues> parse(List<Object[]> nodes) {
        if (nodes == null || nodes.isEmpty())
            return new ArrayList<>();
        List<ExportedLabelValues> fps = new ArrayList<>();
        nodes.forEach(objects -> {
            ObjectNode node = (ObjectNode) objects[0];
            Integer runId = Integer.parseInt(objects[1] == null ? "-1" : objects[1].toString());
            Integer datasetId = Integer.parseInt(objects[2] == null ? "-1" : objects[2].toString());
            Instant start = (Instant) objects[3];
            Instant stop = (Instant) objects[4];
            if (node.isObject()) {
                fps.add(new ExportedLabelValues(LabelValueMap.fromObjectNode(node), runId, datasetId, start, stop));
            } else {
                //TODO alert that something is wrong in the db response
            }
        });
        return fps;
    }
}
