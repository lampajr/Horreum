package io.hyperfoil.tools.horreum.api.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.databind.JsonNode;

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

    //

    /**
     * Parse a list of nodes, group them by run and dataset id
     * and compute the aggregated object by merging the all obj
     * with key the label name and as value the label value.
     * The nodes must match the following structure:
     * 0 - label name
     * 1 - label value
     * 2 - run id
     * 3 - dataset id
     * 4 - start time
     * 5 - stop time
     *
     * @param nodes
     * @return
     */
    public static List<ExportedLabelValues> parse(List<Object[]> nodes) {
        if (nodes == null || nodes.isEmpty())
            return new ArrayList<>();

        Map<RunDatasetKey, ExportedLabelValues> exportedLabelValues = nodes.stream()
                .collect(Collectors.groupingBy(
                        objects -> { // Create the key as a combination of runId and datasetId
                            Integer runId = (Integer) objects[2];
                            Integer datasetId = (Integer) objects[3];
                            return new RunDatasetKey(runId, datasetId);
                        },
                        LinkedHashMap::new, // preserve the order in which I receive the nodes
                        Collectors.collectingAndThen(
                                Collectors.toList(), // collect each group (by runId and datasetId) into a list of elements
                                (groupedObjects) -> { // process each group and create the corresponding ExportedLabelValues
                                    ExportedLabelValues exportedLabelValue = getExportedLabelValues(groupedObjects);

                                    groupedObjects.forEach(objects -> {
                                        // skip records where the labelName is null
                                        if (objects[0] != null) {
                                            String labelName = (String) objects[0];
                                            JsonNode node = (JsonNode) objects[1];
                                            exportedLabelValue.values.put(labelName, node);
                                        }
                                    });

                                    return exportedLabelValue;
                                })));

        return new ArrayList<>(exportedLabelValues.values());
    }

    private static ExportedLabelValues getExportedLabelValues(List<Object[]> groupedObjects) {
        Integer runId = (Integer) groupedObjects.get(0)[2];
        Integer datasetId = (Integer) groupedObjects.get(0)[3];
        Instant start = (Instant) groupedObjects.get(0)[4];
        Instant stop = (Instant) groupedObjects.get(0)[5];
        return new ExportedLabelValues(new LabelValueMap(), runId, datasetId, start, stop);
    }

    public record RunDatasetKey(Integer runId, Integer datasetId) {

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            RunDatasetKey that = (RunDatasetKey) o;

            if (!Objects.equals(runId, that.runId))
                return false;
            return Objects.equals(datasetId, that.datasetId);
        }
    }
}
