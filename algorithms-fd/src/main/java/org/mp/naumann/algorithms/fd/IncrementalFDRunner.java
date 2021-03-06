package org.mp.naumann.algorithms.fd;

import org.mockito.Mockito;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFD;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFDConfiguration;
import org.mp.naumann.algorithms.fd.incremental.IncrementalFDResult;
import org.mp.naumann.algorithms.fd.utils.IncrementalFDResultListener;
import org.mp.naumann.algorithms.result.ResultListener;
import org.mp.naumann.data.ResourceConnector;
import org.mp.naumann.database.ConnectionException;
import org.mp.naumann.database.DataConnector;
import org.mp.naumann.database.Table;
import org.mp.naumann.database.jdbc.JdbcDataConnector;
import org.mp.naumann.database.utils.ConnectionManager;
import org.mp.naumann.processor.BatchProcessor;
import org.mp.naumann.processor.SynchronousBatchProcessor;
import org.mp.naumann.processor.batch.source.StreamableBatchSource;
import org.mp.naumann.processor.batch.source.csv.FixedSizeCsvBatchSource;
import org.mp.naumann.processor.handler.database.DatabaseBatchHandler;

import java.util.List;

public interface IncrementalFDRunner {

    void afterInitial(List<FunctionalDependency> dependencyList);

    void afterIncremental(IncrementalFDResultListener listener);

    default ResultListener<IncrementalFDResult> getResultListener() {
        return null;
    }

    default void run(IncrementalFDRunConfiguration runConfig, IncrementalFDConfiguration algoConfig) throws ConnectionException {
        try (DataConnector dc
                     = new JdbcDataConnector(
                ConnectionManager.getCsvConnection(
                        runConfig.getResourceType(), runConfig.getSeparator())
        )) {

            // execute initial algorithm
            Table table = dc.getTable(runConfig.getSchema(), runConfig.getTableName());
            HyFDInitialAlgorithm hyfd = new HyFDInitialAlgorithm(algoConfig, table);
            List<FunctionalDependency> fds = hyfd.execute();
            afterInitial(fds);

            FDIntermediateDatastructure ds = hyfd.getIntermediateDataStructure();

            // create batch source & processor for inserts
            String batchFile = ResourceConnector.getResourcePath(ResourceConnector.FULL_BATCHES, runConfig.getBatchFileName());
            StreamableBatchSource batchSource = new FixedSizeCsvBatchSource(batchFile, runConfig.getSchema(),
                    runConfig.getTableName(), runConfig.getBatchSize());
            DatabaseBatchHandler databaseBatchHandler = Mockito.mock(DatabaseBatchHandler.class);
            BatchProcessor batchProcessor = new SynchronousBatchProcessor(batchSource, databaseBatchHandler);

            // create incremental algorithm
            IncrementalFD algorithm = new IncrementalFD(runConfig.getTableName(),
                    algoConfig);
//            algorithm.setEfficiencyThreshold(0.2f);
            IncrementalFDResultListener listener = new IncrementalFDResultListener();
            algorithm.addResultListener(listener);
            algorithm.addResultListener(getResultListener());
            algorithm.initialize(ds);

            // process batch
            batchProcessor.addBatchHandler(algorithm);
            batchSource.startStreaming();
            afterIncremental(listener);
        }
    }


}
