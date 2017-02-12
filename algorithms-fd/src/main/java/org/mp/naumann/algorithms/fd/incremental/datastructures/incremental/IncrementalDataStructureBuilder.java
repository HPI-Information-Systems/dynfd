package org.mp.naumann.algorithms.fd.incremental.datastructures.incremental;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import org.mp.naumann.algorithms.fd.hyfd.PLIBuilder;
import org.mp.naumann.algorithms.fd.incremental.CompressedDiff;
import org.mp.naumann.algorithms.fd.incremental.CompressedRecords;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFDConfiguration;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFDConfiguration.PruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.datastructures.DataStructureBuilder;
import org.mp.naumann.algorithms.fd.incremental.datastructures.MapCompressedRecords;
import org.mp.naumann.algorithms.fd.incremental.datastructures.PositionListIndex;
import org.mp.naumann.algorithms.fd.structures.Dictionary;
import org.mp.naumann.algorithms.fd.utils.CollectionUtils;
import org.mp.naumann.algorithms.fd.utils.PliUtils;
import org.mp.naumann.database.statement.DeleteStatement;
import org.mp.naumann.database.statement.InsertStatement;
import org.mp.naumann.database.statement.Statement;
import org.mp.naumann.database.statement.UpdateStatement;
import org.mp.naumann.processor.batch.Batch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class IncrementalDataStructureBuilder implements DataStructureBuilder {

    private final IncrementalPLIBuilder pliBuilder;
    private final IncrementalFDConfiguration version;
    private final List<String> columns;
    private final List<Integer> pliOrder;
    private final IncrementalClusterMapBuilder clusterMapBuilder;
    private final Dictionary<String> dictionary;

    private List<? extends PositionListIndex> plis;
    private final MapCompressedRecords compressedRecords;

    public IncrementalDataStructureBuilder(PLIBuilder pliBuilder, IncrementalFDConfiguration version, List<String> columns) {
        this(pliBuilder, version, columns, pliBuilder.getPliOrder());
    }

    public IncrementalDataStructureBuilder(PLIBuilder pliBuilder, IncrementalFDConfiguration version, List<String> columns, List<Integer> pliOrder) {
        this.pliOrder = pliOrder;
        this.pliBuilder = new IncrementalPLIBuilder(pliOrder);
        this.version = version;
        this.columns = columns;
        this.dictionary = new Dictionary<>(pliBuilder.isNullEqualNull());
        int nextRecordId = pliBuilder.getNumLastRecords();
        this.compressedRecords = new MapCompressedRecords(nextRecordId, pliOrder.size());
        this.clusterMapBuilder = new IncrementalClusterMapBuilder(columns.size(), nextRecordId, dictionary);
        initialize(pliBuilder.getClusterMaps(), nextRecordId);
    }

    private void initialize(List<HashMap<String, IntArrayList>> oldClusterMaps, int nextRecordId) {
        List<Integer> inserted = IntStream.range(0, nextRecordId).boxed().collect(Collectors.toList());
        List<Map<Integer, IntArrayList>> clusterMaps = new ArrayList<>(oldClusterMaps.size());
        for (HashMap<String, IntArrayList> oldClusterMap : oldClusterMaps) {
            Map<Integer, IntArrayList> clusterMap = new HashMap<>();
            for (Entry<String, IntArrayList> cluster : oldClusterMap.entrySet()) {
                int dictValue = dictionary.getOrAdd(cluster.getKey());
                clusterMap.put(dictValue, cluster.getValue());
            }
            clusterMaps.add(clusterMap);
        }
        plis = pliBuilder.fetchPositionListIndexes(clusterMaps);
        List<Map<Integer, Integer>> invertedPlis = invertPlis(clusterMaps);
        for (int recordId : inserted) {
            compressedRecords.put(recordId, fetchRecordFrom(recordId, invertedPlis));
        }
    }

    @Override
    public CompressedDiff update(Batch batch) {
        clusterMapBuilder.flush();
        Set<Integer> inserted = addRecords(batch.getInsertStatements());
        Set<Integer> insertedUpdate = addUpdateRecords(batch.getUpdateStatements());
        inserted.addAll(insertedUpdate);

        Set<Integer> deleted = removeRecords(batch.getDeleteStatements());
        Set<Integer> deletedUpdate = removeUpdateRecords(batch.getUpdateStatements());
        deleted.addAll(deletedUpdate);
        Map<Integer, int[]> deletedDiff = new HashMap<>(deleted.size());
        deleted.forEach(i -> deletedDiff.put(i, getCompressedRecord(i)));

        updateDataStructures(inserted, deleted);

        Map<Integer, int[]> insertedDiff = new HashMap<>(inserted.size());
        inserted.forEach(i -> insertedDiff.put(i, getCompressedRecord(i)));

        return new CompressedDiff(insertedDiff, deletedDiff, new HashMap<>(0), new HashMap<>(0));
    }

    private Set<Integer> removeRecords(List<DeleteStatement> deletes) {
        Set<Integer> ids = new HashSet<>();
        for (Statement delete : deletes) {
            Map<String, String> valueMap = delete.getValueMap();
            List<String> values = columns.stream().map(valueMap::get).collect(Collectors.toList());
            Collection<Integer> removed = removeRecord(values);
            ids.addAll(removed);
        }
        return ids;
    }

    private Set<Integer> removeUpdateRecords(List<UpdateStatement> updates) {
        Set<Integer> ids = new HashSet<>();
        for (UpdateStatement update : updates) {
            Map<String, String> valueMap = update.getOldValueMap();
            List<String> values = columns.stream().map(valueMap::get).collect(Collectors.toList());
            Collection<Integer> removed = removeRecord(values);
            ids.addAll(removed);
        }
        return ids;
    }

    private Collection<Integer> removeRecord(List<String> record) {
        List<IntArrayList> clusters = new ArrayList<>();
        for (PositionListIndex pli : plis) {
            String value = record.get(pli.getAttribute());
            int dictValue = dictionary.getOrAdd(value);
            IntArrayList cluster = pli.getCluster(dictValue);
            if (cluster == null || cluster.isEmpty()) {
                return Collections.emptyList();
            }
            clusters.add(cluster);
        }
        Set<Integer> matching = CollectionUtils.intersection(clusters);
        clusters.forEach(c -> c.removeAll(matching));
        return matching;
    }

    private Set<Integer> addRecords(List<InsertStatement> inserts) {
        Set<Integer> inserted = new HashSet<>();
        for (InsertStatement insert : inserts) {
            Map<String, String> valueMap = insert.getValueMap();
            List<String> values = columns.stream().map(valueMap::get).collect(Collectors.toList());
            int id = clusterMapBuilder.addRecord(values);
            inserted.add(id);
        }
        return inserted;
    }

    private Set<Integer> addUpdateRecords(List<UpdateStatement> updates) {
        Set<Integer> updated = new HashSet<>();
        for (UpdateStatement update : updates) {
            Map<String, String> valueMap = update.getValueMap();
            List<String> values = columns.stream().map(valueMap::get).collect(Collectors.toList());
            int id = clusterMapBuilder.addRecord(values);
            updated.add(id);
        }
        return updated;
    }

    private void updateDataStructures(Collection<Integer> inserted, Collection<Integer> deleted) {
        updatePlis();
        updateCompressedRecords(inserted, deleted);
        if (version.usesClusterPruning() || version.usesEnhancedClusterPruning()) {
            List<Map<Integer, IntArrayList>> clusterMaps = clusterMapBuilder.getClusterMaps();
            Map<Integer, Set<Integer>> newClusters = null;
            if (version.usesEnhancedClusterPruning()) {
                newClusters = new HashMap<>(plis.size());
            }
            int i = 0;
            for (PositionListIndex pli : plis) {
                int attribute = pli.getAttribute();
                Set<Integer> clusterIds = clusterMaps.get(attribute).keySet();
                if (version.usesClusterPruning()) {
                    pli.setClustersWithNewRecords(clusterIds);
                }
                if (version.usesEnhancedClusterPruning()) {
                    newClusters.put(i, clusterIds);
                }
                i++;
            }
            if (version.usesEnhancedClusterPruning()) {
                Map<Integer, Set<Integer>> otherClustersWithNewRecords = newClusters;
                plis.forEach(pli -> pli.setOtherClustersWithNewRecords(otherClustersWithNewRecords));
            }
        }
        if (version.usesInnerClusterPruning()) {
            plis.forEach(pli -> pli.setNewRecords(inserted));
        }
    }

    private void updateCompressedRecords(Collection<Integer> inserted, Collection<Integer> deleted) {
        List<Map<Integer, IntArrayList>> clusterMaps = clusterMapBuilder.getClusterMaps();
        List<Map<Integer, Integer>> invertedPlis = invertPlis(clusterMaps);
        for (int recordId : inserted) {
            compressedRecords.put(recordId, fetchRecordFrom(recordId, invertedPlis));
        }
        for (int recordId : deleted) {
            compressedRecords.remove(recordId);
        }
    }

    private static int[] fetchRecordFrom(int recordId, List<Map<Integer, Integer>> invertedPlis) {
        int numAttributes = invertedPlis.size();
        int[] record = new int[numAttributes];
        for (int i = 0; i < numAttributes; i++) {
            record[i] = invertedPlis.get(i).getOrDefault(recordId, PliUtils.UNIQUE_VALUE);
        }
        return record;
    }

    private List<Map<Integer, Integer>> invertPlis(List<Map<Integer, IntArrayList>> clusterMaps) {
        List<Map<Integer, Integer>> invertedPlis = new ArrayList<>();
        for (int clusterId : pliOrder) {
            Map<Integer, Integer> invertedPli = new HashMap<>();

            for (Entry<Integer, IntArrayList> cluster : clusterMaps.get(clusterId).entrySet()) {
                for (int recordId : cluster.getValue()) {
                    invertedPli.put(recordId, cluster.getKey());
                }
            }
            invertedPlis.add(invertedPli);
        }
        return invertedPlis;
    }

    private void updatePlis() {
        plis = pliBuilder.fetchPositionListIndexes(clusterMapBuilder.getClusterMaps());
    }

    @Override
    public List<? extends PositionListIndex> getPlis() {
        return plis;
    }

    @Override
    public CompressedRecords getCompressedRecords() {
        return compressedRecords;
    }

    @Override
    public int getNumRecords() {
        return compressedRecords.size();
    }

    private int[] getCompressedRecord(int record) {
        return version.usesPruningStrategy(PruningStrategy.ANNOTATION) || version.usesPruningStrategy(PruningStrategy.SIMPLE)? compressedRecords.get(record) : null;
    }

}
