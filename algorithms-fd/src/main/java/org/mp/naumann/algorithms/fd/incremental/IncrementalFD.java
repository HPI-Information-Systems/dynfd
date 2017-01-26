package org.mp.naumann.algorithms.fd.incremental;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import org.apache.lucene.util.OpenBitSet;
import org.mp.naumann.algorithms.IncrementalAlgorithm;
import org.mp.naumann.algorithms.benchmark.speed.BenchmarkLevel;
import org.mp.naumann.algorithms.benchmark.speed.SpeedBenchmark;
import org.mp.naumann.algorithms.exceptions.AlgorithmExecutionException;
import org.mp.naumann.algorithms.fd.FDIntermediateDatastructure;
import org.mp.naumann.algorithms.fd.FDLogger;
import org.mp.naumann.algorithms.fd.FunctionalDependency;
import org.mp.naumann.algorithms.fd.hyfd.FDList;
import org.mp.naumann.algorithms.fd.hyfd.PLIBuilder;
import org.mp.naumann.algorithms.fd.incremental.datastructures.DataStructureBuilder;
import org.mp.naumann.algorithms.fd.incremental.datastructures.PositionListIndex;
import org.mp.naumann.algorithms.fd.incremental.datastructures.incremental.IncrementalDataStructureBuilder;
import org.mp.naumann.algorithms.fd.incremental.datastructures.recompute.RecomputeDataStructureBuilder;
import org.mp.naumann.algorithms.fd.incremental.pruning.bloom.AllCombinationsBloomGenerator;
import org.mp.naumann.algorithms.fd.incremental.pruning.bloom.BloomPruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.pruning.bloom.CurrentFDBloomGenerator;
import org.mp.naumann.algorithms.fd.incremental.pruning.simple.ExistingValuesPruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.validator.GeneralizingValidator;
import org.mp.naumann.algorithms.fd.incremental.validator.SpecializingValidator;
import org.mp.naumann.algorithms.fd.incremental.violations.ViolationCollection;
import org.mp.naumann.algorithms.fd.structures.FDSet;
import org.mp.naumann.algorithms.fd.structures.FDTree;
import org.mp.naumann.algorithms.fd.structures.IntegerPair;
import org.mp.naumann.algorithms.result.ResultListener;
import org.mp.naumann.database.data.ColumnIdentifier;
import org.mp.naumann.processor.batch.Batch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class IncrementalFD implements IncrementalAlgorithm<IncrementalFDResult, FDIntermediateDatastructure> {

    private static final boolean VALIDATE_PARALLEL = true;
    private static final float EFFICIENCY_THRESHOLD = 0.01f;
    private IncrementalFDConfiguration version = IncrementalFDConfiguration.LATEST;

    private final List<String> columns;
    private FDTree posCover;
    private final String tableName;
    private final List<ResultListener<IncrementalFDResult>> resultListeners = new ArrayList<>();
    private MemoryGuardian memoryGuardian = new MemoryGuardian(true);
    private FDIntermediateDatastructure intermediateDatastructure;
    private boolean initialized = false;

    private DataStructureBuilder dataStructureBuilder;
    private BloomPruningStrategy advancedBloomPruning;
    private ExistingValuesPruningStrategy simplePruning;
    private BloomPruningStrategy bloomPruning;
    private FDSet negCover;
    private ViolationCollection violationCollection;

    public IncrementalFD(List<String> columns, String tableName, IncrementalFDConfiguration version) {
        this(columns, tableName);
        this.version = version;
    }


    public IncrementalFD(List<String> columns, String tableName) {
        this.columns = columns;
        this.tableName = tableName;
    }


    @Override
    public Collection<ResultListener<IncrementalFDResult>> getResultListeners() {
        return resultListeners;
    }

    @Override
    public void addResultListener(ResultListener<IncrementalFDResult> listener) {
        if(listener != null)
            this.resultListeners.add(listener);
    }

    @Override
    public void initialize() {
        this.posCover = intermediateDatastructure.getPosCover();
        this.negCover = intermediateDatastructure.getNegCover();
        this.violationCollection = intermediateDatastructure.getViolatingValues();

        PLIBuilder pliBuilder = intermediateDatastructure.getPliBuilder();

        List<Integer> pliOrder = pliBuilder.getPliOrder();
        List<String> orderedColumns = pliOrder.stream().map(columns::get).collect(Collectors.toList());
        List<HashMap<Integer, IntArrayList>> clusterMaps = intermediateDatastructure.getPliBuilder().getClusterMaps();
        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.BLOOM)) {
            bloomPruning = new BloomPruningStrategy(orderedColumns).addGenerator(new AllCombinationsBloomGenerator(2));
            bloomPruning.initialize(clusterMaps, pliBuilder.getNumLastRecords(), pliOrder, pliBuilder.getDictionary());
        }

        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.BLOOM_ADVANCED)) {
            advancedBloomPruning = new BloomPruningStrategy(orderedColumns)
                    .addGenerator(new CurrentFDBloomGenerator(posCover));
            advancedBloomPruning.initialize(clusterMaps, pliBuilder.getNumLastRecords(), pliOrder, pliBuilder.getDictionary());
        }
        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.SIMPLE)) {
            simplePruning = new ExistingValuesPruningStrategy(columns);
        }
        if(version.recomputesDataStructures()) {
            dataStructureBuilder = new RecomputeDataStructureBuilder(pliBuilder, this.version, this.columns, pliOrder);
        } else {
            dataStructureBuilder = new IncrementalDataStructureBuilder(pliBuilder, this.version, this.columns, pliOrder);
        }
    }

    @Override
    public IncrementalFDResult execute(Batch batch) throws AlgorithmExecutionException {
        if (!initialized) {
            FDLogger.log(Level.FINE, "Initializing IncrementalFD");
            initialize();
            FDLogger.log(Level.FINEST, intermediateDatastructure.getViolatingValues().toString());
            initialized = true;
        }

        FDLogger.log(Level.FINE, "Started IncrementalFD for new Batch");
        SpeedBenchmark.begin(BenchmarkLevel.METHOD_HIGH_LEVEL);

         SpeedBenchmark.begin(BenchmarkLevel.UNIQUE);
        CompressedDiff diff = dataStructureBuilder.update(batch);
        SpeedBenchmark.end(BenchmarkLevel.UNIQUE, "BUILD DIFF");

        List<PositionListIndex> plis = dataStructureBuilder.getPlis();
        CompressedRecords compressedRecords = dataStructureBuilder.getCompressedRecord();
        //validateTopDown(batch, diff, plis, compressedRecords);
        validateBottomUp(diff, compressedRecords, plis);
        List<FunctionalDependency> fds = new ArrayList<>();
        posCover.addFunctionalDependenciesInto(fds::add, this.buildColumnIdentifiers(), plis);
        SpeedBenchmark.end(BenchmarkLevel.METHOD_HIGH_LEVEL, "Processed one batch, inner measuring");
        return new IncrementalFDResult(fds, 0, 0);
    }

    protected void validateTopDown(Batch batch, CompressedDiff diff, List<PositionListIndex> plis, CompressedRecords compressedRecords) throws AlgorithmExecutionException {
        SpecializingValidator validator = new SpecializingValidator(version, negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD, VALIDATE_PARALLEL, memoryGuardian);
        IncrementalSampler sampler = new IncrementalSampler(negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD,
                intermediateDatastructure.getValueComparator(), this.memoryGuardian);

        IncrementalInductor inductor = new IncrementalInductor(negCover, posCover, this.memoryGuardian);
        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.BLOOM)) {
            validator.addValidationPruner(bloomPruning.analyzeBatch(batch));
        }
        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.BLOOM_ADVANCED)) {
            validator.addValidationPruner(advancedBloomPruning.analyzeBatch(batch));
        }
        if (version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.SIMPLE)) {
            validator.addValidationPruner(simplePruning.analyzeDiff(diff));
        }

        FDLogger.log(Level.FINE, "Finished building pruning strategies");


        List<IntegerPair> comparisonSuggestions;
        int i = 1;
        do {
            FDLogger.log(Level.FINE, "Started round " + i);
            FDLogger.log(Level.FINE, "Validating positive cover");
            comparisonSuggestions = validator.validatePositiveCover();
            if (version.usesSampling() && comparisonSuggestions != null) {
                FDLogger.log(Level.FINE, "Enriching negative cover");
                FDList newNonFds = sampler.enrichNegativeCover(comparisonSuggestions);
                FDLogger.log(Level.FINE, "Updating positive cover");
                inductor.updatePositiveCover(newNonFds);
            }

            FDLogger.log(Level.FINE, "Validating positive cover");
            comparisonSuggestions = validator.validatePositiveCover();
            SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Round " + i++);
        } while (comparisonSuggestions != null);
        // Return result
        int pruned = validator.getPruned();
        int validations = validator.getValidations();
        FDLogger.log(Level.FINE, "Pruned " + pruned + " validations");
        FDLogger.log(Level.FINE, "Made " + validations + " validations");
    }

    public void validateBottomUp(CompressedDiff diff, CompressedRecords compressedRecords, List<PositionListIndex> plis) throws AlgorithmExecutionException {
        if(version.usesPruningStrategy(IncrementalFDConfiguration.PruningStrategy.ANNOTATION)){


            //FDTree posCover = new FDTree(columns.size(), -1);
            SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Finished basic setup.");
            GeneralizingValidator validator = new GeneralizingValidator(version, negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD, VALIDATE_PARALLEL, memoryGuardian);

            IncrementalInductor inductor = new IncrementalInductor(negCover, posCover, this.memoryGuardian);
            SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Initialised valdiator and inductor");
            List<OpenBitSet> affected = violationCollection.getAffected(negCover, diff.getDeletedRecords());
            SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Received affected records");

            IncrementalSampler sampler = new IncrementalSampler(negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD,
                    intermediateDatastructure.getValueComparator(), this.memoryGuardian);

            int induct = inductor.generalizePositiveCover(posCover, affected, violationCollection.getInvalidFds(), columns.size());
            FDLogger.log(Level.INFO, "Added " + induct + " candidates to check, depth now at "+posCover.getDepth());
            SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Inducted candidates into positive cover");
            boolean theresmore;
            int i = 0;
            do{
                theresmore = validator.validatePositiveCover();
                SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Round " + i++);
            } while (theresmore);

            int pruned = validator.getPruned();
            int validations = validator.getValidations();
            FDLogger.log(Level.FINE, "Pruned " + pruned + " validations");
            FDLogger.log(Level.FINE, "Made " + validations + " validations");
        }
    }

    @Override
    public void setIntermediateDataStructure(FDIntermediateDatastructure intermediateDataStructure) {

        this.intermediateDatastructure = intermediateDataStructure;
    }

    private ObjectArrayList<ColumnIdentifier> buildColumnIdentifiers() {
        ObjectArrayList<ColumnIdentifier> columnIdentifiers = new ObjectArrayList<>(this.columns.size());
        for (String attributeName : this.columns)
            columnIdentifiers.add(new ColumnIdentifier(this.tableName, attributeName));
        return columnIdentifiers;
    }

}
