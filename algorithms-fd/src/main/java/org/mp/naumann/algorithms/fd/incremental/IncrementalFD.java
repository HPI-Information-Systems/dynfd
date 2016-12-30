package org.mp.naumann.algorithms.fd.incremental;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import org.mp.naumann.algorithms.IncrementalAlgorithm;
import org.mp.naumann.algorithms.benchmark.speed.BenchmarkLevel;
import org.mp.naumann.algorithms.benchmark.speed.SpeedBenchmark;
import org.mp.naumann.algorithms.exceptions.AlgorithmExecutionException;
import org.mp.naumann.algorithms.fd.FDIntermediateDatastructure;
import org.mp.naumann.algorithms.fd.FDLogger;
import org.mp.naumann.algorithms.fd.FunctionalDependency;
import org.mp.naumann.algorithms.fd.hyfd.FDList;
import org.mp.naumann.algorithms.fd.incremental.bloom.AdvancedBloomPruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.bloom.BloomPruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.bloom.SimpleBloomPruningStrategy;
import org.mp.naumann.algorithms.fd.incremental.simple.SimplePruningStrategy;
import org.mp.naumann.algorithms.fd.structures.FDSet;
import org.mp.naumann.algorithms.fd.structures.FDTree;
import org.mp.naumann.algorithms.fd.structures.IntegerPair;
import org.mp.naumann.algorithms.fd.structures.PLIBuilder;
import org.mp.naumann.algorithms.fd.structures.PositionListIndex;
import org.mp.naumann.algorithms.result.ResultListener;
import org.mp.naumann.database.data.ColumnIdentifier;
import org.mp.naumann.processor.batch.Batch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

public class IncrementalFD implements IncrementalAlgorithm<IncrementalFDResult, FDIntermediateDatastructure> {

    private static final boolean VALIDATE_PARALLEL = true;
    private static final float EFFICIENCY_THRESHOLD = 0.01f;
	private static final boolean SAMPLING = true;
	private IncrementalFDVersion version = IncrementalFDVersion.LATEST;

	private final List<String> columns;
	private FDTree posCover;
	private final String tableName;
	private final List<ResultListener<IncrementalFDResult>> resultListeners = new ArrayList<>();
	private MemoryGuardian memoryGuardian = new MemoryGuardian(true);
	private FDIntermediateDatastructure intermediateDatastructure;
	private boolean initialized = false;

	private IncrementalPLIBuilder incrementalPLIBuilder;
	private BloomPruningStrategy advancedBloomPruning;
	private SimplePruningStrategy simplePruning;
	private BloomPruningStrategy bloomPruning;
	private FDSet negCover;

	public IncrementalFD(List<String> columns, String tableName, IncrementalFDVersion version){
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
		this.resultListeners.add(listener);
	}

	@Override
	public void initialize() {
		this.posCover = intermediateDatastructure.getPosCover();
		this.negCover = intermediateDatastructure.getNegCover();
		PLIBuilder pliBuilder = intermediateDatastructure.getPliBuilder();
		List<Integer> pliSequence = pliBuilder.getPliOrder();
		List<HashMap<String, IntArrayList>> clusterMaps = intermediateDatastructure.getPliBuilder().getClusterMaps();
		if(version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.BLOOM){
			bloomPruning = new SimpleBloomPruningStrategy(columns, pliBuilder.getNumLastRecords(), pliSequence);
			bloomPruning.initialize(clusterMaps);
		}
		if(version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.BLOOM_ADVANCED){
			advancedBloomPruning = new AdvancedBloomPruningStrategy(columns, pliBuilder.getNumLastRecords(), pliSequence, posCover);
			advancedBloomPruning.initialize(clusterMaps);
		}
		if (version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.SIMPLE) {
			simplePruning = new SimplePruningStrategy(columns);
		}
		incrementalPLIBuilder = new IncrementalPLIBuilder(pliBuilder, this.version, this.columns);
	}

	@Override
	public IncrementalFDResult execute(Batch batch) throws AlgorithmExecutionException {
		if (!initialized) {
			FDLogger.log(Level.FINE, "Initializing IncrementalFD");
			initialize();
			initialized = true;
		}
		FDLogger.log(Level.FINE, "Started IncrementalFD for new Batch");
		SpeedBenchmark.begin(BenchmarkLevel.METHOD_HIGH_LEVEL);
		CardinalitySet existingCombinations = null;
		if (version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.BLOOM) {
			existingCombinations = bloomPruning.getExistingCombinations(batch);
		}
		if (version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.BLOOM_ADVANCED) {
			existingCombinations = advancedBloomPruning.getExistingCombinations(batch);
		}
		CompressedDiff diff = incrementalPLIBuilder.update(batch);
		List<PositionListIndex> plis = incrementalPLIBuilder.getPlis();
		int[][] compressedRecords = incrementalPLIBuilder.getCompressedRecord();
		if (version.getPruningStrategy() == IncrementalFDVersion.PruningStrategy.SIMPLE) {
			existingCombinations = simplePruning.getExistingCombinations(diff);
		}
		FDLogger.log(Level.FINE, "Finished collecting existing combinations");
		Validator validator = new Validator(negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD, VALIDATE_PARALLEL, memoryGuardian, this);
		Sampler sampler = new Sampler(negCover, posCover, compressedRecords, plis, EFFICIENCY_THRESHOLD,
				intermediateDatastructure.getValueComparator(), this.memoryGuardian);
		Inductor inductor = new Inductor(negCover, posCover, this.memoryGuardian);

        List<IntegerPair> comparisonSuggestions = new ArrayList<>();

        validator.setExistingCombinations(existingCombinations);
		int i = 1;
		do {
			FDLogger.log(Level.FINE, "Started round " + i);
			if (SAMPLING) {
				FDLogger.log(Level.FINE, "Enriching negative cover");
				FDList newNonFds = sampler.enrichNegativeCover(comparisonSuggestions);
				FDLogger.log(Level.FINE, "Updating positive cover");
				inductor.updatePositiveCover(newNonFds);
			}
			FDLogger.log(Level.FINE, "Validating positive cover");
			comparisonSuggestions = validator.validatePositiveCover();
			SpeedBenchmark.lap(BenchmarkLevel.METHOD_HIGH_LEVEL, "Round "+i++);
		} while (comparisonSuggestions != null);
		int pruned = validator.getPruned();
		int validations = validator.getValidations();
		FDLogger.log(Level.FINE, "Pruned " + pruned + " validations");
		FDLogger.log(Level.FINE, "Made " + validations + " validations");
		List<FunctionalDependency> fds = new ArrayList<>();
		posCover.addFunctionalDependenciesInto(fds::add, this.buildColumnIdentifiers(), plis);
		SpeedBenchmark.end(BenchmarkLevel.METHOD_HIGH_LEVEL, "Processed one batch, inner measuring");
		return new IncrementalFDResult(fds, validations, pruned);
	}

	@Override
	public void setIntermediateDataStructure(FDIntermediateDatastructure intermediateDataStructure) {
		this.intermediateDatastructure = intermediateDataStructure;
	}

	protected ObjectArrayList<ColumnIdentifier> buildColumnIdentifiers() {
		ObjectArrayList<ColumnIdentifier> columnIdentifiers = new ObjectArrayList<>(this.columns.size());
		for (String attributeName : this.columns)
			columnIdentifiers.add(new ColumnIdentifier(this.tableName, attributeName));
		return columnIdentifiers;
	}

}
